package compilers.pipeline;

import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.SymbolTable.SymbolTableBuilder;
import compilers.flask.antlr_gen.FlaskLexer;
import compilers.flask.antlr_gen.FlaskParser;
import compilers.flask.ast.builder.ASTBuilder;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.generation.ContextExtractor;
import compilers.flask.generation.ProjectContext;
import compilers.flask.generation.RenderJob;
import compilers.flask.generation.RouteInfo;
import compilers.flask.semantic.SemanticAnalyzer;
import compilers.flask.semantic.validation.AstStructuralValidator;
import compilers.flask.semantic.validation.ScopeRuleChecker;
import compilers.html_css.render.JinjaRenderer;
import compilers.report.AstJsonWriter;
import compilers.report.GenerationLog;
import compilers.report.GenerationReportWriter;
import compilers.report.SemanticReportWriter;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * End-to-end generation orchestration (plan section 8.1):
 *
 * <pre>
 * parse app.py → structural/symbols/scope/semantic gates
 *   → ast_python.json + semantic_report.txt
 *   → (stop on semantic ERRORs, exit 1)
 * context extraction → (stop on extraction ERRORs, exit 2)
 * render every RenderJob → output/*.html
 * copy support files verbatim (app.py, style.css, script.js, templates/)
 * ast_jinja.json + generation_log.txt
 * </pre>
 */
public final class GenerationPipeline {

    /** Resolved project layout for one run. */
    public static final class ProjectPaths {
        public final Path projectDir;
        public final Path appPy;
        public final Path templateRoot;
        public final Path outputDir;
        public final Path reportsDir;

        public ProjectPaths(
                Path projectDir,
                Path appPy,
                Path templateRoot,
                Path outputDir,
                Path reportsDir) {
            this.projectDir = projectDir;
            this.appPy = appPy;
            this.templateRoot = templateRoot;
            this.outputDir = outputDir;
            this.reportsDir = reportsDir;
        }

        /**
         * @param source app.py or the project directory
         * @param outOverride --out DIR or null
         * @param reportsOverride --reports DIR or null
         */
        public static ProjectPaths resolve(
                Path source, Path outOverride, Path reportsOverride) throws IOException {
            Path normalized = source.toAbsolutePath().normalize();
            final Path projectDir;
            final Path appPy;
            if (Files.isDirectory(normalized)) {
                projectDir = normalized;
                appPy = normalized.resolve("app.py");
                if (!Files.isRegularFile(appPy)) {
                    throw new IOException("Project directory has no app.py: " + normalized);
                }
            } else {
                appPy = normalized;
                projectDir = normalized.getParent() == null
                        ? normalized.toAbsolutePath().getParent()
                        : normalized.getParent();
            }
            Path templates = projectDir.resolve("templates");
            Path templateRoot = Files.isDirectory(templates) ? templates : projectDir;
            Path outputDir = outOverride != null
                    ? outOverride.toAbsolutePath().normalize()
                    : projectDir.resolve("output");
            Path reportsDir = reportsOverride != null
                    ? reportsOverride.toAbsolutePath().normalize()
                    : projectDir.resolve("compiler_output");
            return new ProjectPaths(projectDir, appPy, templateRoot,
                    outputDir, reportsDir);
        }
    }

    /** Runs the whole generation stage for one project. */
    public GenerationResult run(ProjectPaths paths) throws IOException {
        Objects.requireNonNull(paths, "paths");
        DiagnosticReporter reporter = new DiagnosticReporter();
        GenerationLog log = new GenerationLog();
        List<String> generatedPages = new ArrayList<>();
        Files.createDirectories(paths.reportsDir);
        Files.createDirectories(paths.outputDir);
        int stalePagesRemoved = cleanGeneratedPages(paths.outputDir);
        String appLabel = paths.appPy.getFileName().toString();

        // ---------- 1. parse ----------
        SyntaxErrorCollector syntaxErrors =
                new SyntaxErrorCollector(reporter, appLabel);
        FlaskLexer lexer = new FlaskLexer(CharStreams.fromPath(paths.appPy));
        lexer.removeErrorListeners();
        lexer.addErrorListener(syntaxErrors);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        FlaskParser parser = new FlaskParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(syntaxErrors);
        FlaskParser.ProgramContext tree = parser.program();

        ProgramNode program = null;
        if (!reporter.hasErrors()) {
            ASTNode root = new ASTBuilder(appLabel).visit(tree);
            if (root instanceof ProgramNode) {
                program = (ProgramNode) root;
            } else {
                reporter.report(Diagnostics.invalidCodegenContext(
                        "Python AST builder produced no program", 0, 0, appLabel));
            }
        }
        if (program == null) {
            logCleanup(stalePagesRemoved, log);
            log.error("parsing failed; nothing to generate");
            return finish(1, paths, generatedPages, reporter, log, appLabel);
        }
        log.parse(appLabel + " -> Python AST ("
                + program.getStatements().size() + " top-level statements)");
        writeText(paths.reportsDir.resolve("ast_python.json"),
                AstJsonWriter.pythonAstJson(program));

        // ---------- 2. analysis gates (frozen order) ----------
        boolean structurallyValid =
                new AstStructuralValidator(reporter, appLabel).validate(program);
        if (structurallyValid) {
            SymbolTableBuilder symbols = new SymbolTableBuilder(reporter, appLabel);
            program.accept(symbols);
            new ScopeRuleChecker(symbols.getSymbolTable(), reporter, appLabel)
                    .check(program);
            new SemanticAnalyzer(symbols.getSymbolTable(), appLabel, reporter)
                    .analyze(program);
        }
        log.semantic(reporter.errors().size() + " error(s), "
                + reporter.warnings().size() + " warning(s)");
        logCleanup(stalePagesRemoved, log);
        if (reporter.hasErrors()) {
            log.error("semantic errors block generation (rule: valid program first)");
            return finish(1, paths, generatedPages, reporter, log, appLabel);
        }

        // ---------- 3. context extraction ----------
        ProjectContext context =
                new ContextExtractor(reporter, appLabel).extract(program);
        log.adopt(context.getLogLines());
        if (reporter.hasErrors()) {
            log.error("context extraction failed; no pages were generated");
            return finish(2, paths, generatedPages, reporter, log, appLabel);
        }

        // ---------- 4. rendering ----------
        if (!validateOutputNames(context.getRenderJobs(), reporter, log, appLabel)) {
            writeText(paths.reportsDir.resolve("ast_jinja.json"),
                    AstJsonWriter.jinjaAstJson(new LinkedHashMap<>()));
            log.done("0 page(s) generated; output-name collision blocked rendering");
            return finish(2, paths, generatedPages, reporter, log, appLabel);
        }

        Map<String, String> routeHrefs = routeHrefs(context);
        JinjaRenderer renderer = new JinjaRenderer(
                reporter, paths.templateRoot, routeHrefs);
        boolean renderFailed = false;
        for (RenderJob job : context.getRenderJobs()) {
            Map<String, Object> plainContext =
                    resolveUrlRefs(job.getContext(), routeHrefs, log);
            try {
                String html = renderer.render(job.getTemplateName(), plainContext);
                Path page = paths.outputDir.resolve(job.getOutputFileName());
                writeText(page, html);
                generatedPages.add(job.getOutputFileName());
                log.render("\"" + job.getTemplateName() + "\" -> output/"
                        + job.getOutputFileName()
                        + " (" + html.getBytes(StandardCharsets.UTF_8).length + " bytes)");
            } catch (JinjaRenderer.RenderFailure failure) {
                renderFailed = true;
                log.error(job.getTemplateName() + ": " + failure.getMessage());
            }
        }
        log.adopt(renderer.getLogLines());

        // ---------- 5. copy support files verbatim (frozen rule 1.5) ----------
        int copied = copySupportFiles(paths, log);

        // ---------- 6. reports ----------
        writeText(paths.reportsDir.resolve("ast_jinja.json"),
                AstJsonWriter.jinjaAstJson(renderer.getParsedRoots()));
        log.done(generatedPages.size() + " page(s) generated, "
                + copied + " support file(s) copied");

        int exitCode = renderFailed ? 2 : 0;
        return finish(exitCode, paths, generatedPages, reporter, log, appLabel, context);
    }

    // ==================== helpers ====================

    /** Removes only top-level HTML pages from the compiler-owned output folder. */
    private int cleanGeneratedPages(Path outputDir) throws IOException {
        int removed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDir)) {
            for (Path file : stream) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(file) && name.endsWith(".html")) {
                    Files.delete(file);
                    removed++;
                }
            }
        }
        return removed;
    }

    private void logCleanup(int removed, GenerationLog log) {
        if (removed > 0) {
            log.clean(removed + " stale generated page(s) removed");
        }
    }

    /** Prevents two templates/routes from silently overwriting one HTML page. */
    private boolean validateOutputNames(
            List<RenderJob> jobs,
            DiagnosticReporter reporter,
            GenerationLog log,
            String sourceFile) {
        Map<String, RenderJob> owners = new LinkedHashMap<>();
        boolean valid = true;
        for (RenderJob job : jobs) {
            String outputName = job.getOutputFileName();
            String key = outputName.toLowerCase(Locale.ROOT);
            RenderJob previous = owners.putIfAbsent(key, job);
            if (previous == null) {
                continue;
            }
            String message = "Output page collision: templates '"
                    + previous.getTemplateName() + "' (endpoint '"
                    + previous.getEndpoint() + "') and '" + job.getTemplateName()
                    + "' (endpoint '" + job.getEndpoint() + "') both generate '"
                    + outputName + "'";
            reporter.report(Diagnostics.invalidCodegenContext(
                    message, job.getLine(), job.getColumn(), sourceFile));
            log.error(message);
            valid = false;
        }
        return valid;
    }

    /** endpoint → href: the generated page when one exists, else the path. */
    private Map<String, String> routeHrefs(ProjectContext context) {
        Map<String, String> hrefs = new LinkedHashMap<>();
        for (RouteInfo route : context.getRoutes().values()) {
            hrefs.put(route.getEndpoint(), route.getPath());
        }
        for (RenderJob job : context.getRenderJobs()) {
            hrefs.put(job.getEndpoint(), job.getOutputFileName());
        }
        return hrefs;
    }

    /**
     * Replaces symbolic {@link ProjectContext.UrlRef} values with plain href
     * strings so only plain Java crosses the section 6.4 boundary.
     */
    private Map<String, Object> resolveUrlRefs(
            Map<String, Object> context, Map<String, String> hrefs, GenerationLog log) {
        IdentityHashMap<Object, Object> seen = new IdentityHashMap<>();
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            resolved.put(entry.getKey(),
                    resolveValue(entry.getValue(), hrefs, log, seen));
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private Object resolveValue(
            Object value,
            Map<String, String> hrefs,
            GenerationLog log,
            IdentityHashMap<Object, Object> seen) {
        if (value instanceof ProjectContext.UrlRef) {
            ProjectContext.UrlRef ref = (ProjectContext.UrlRef) value;
            String href = "static".equals(ref.getEndpoint())
                    ? String.valueOf(ref.getParams().getOrDefault("filename", ""))
                    : hrefs.get(ref.getEndpoint());
            if (href == null) {
                log.warn("url_for: unknown endpoint '" + ref.getEndpoint() + "'");
                return "#" + ref.getEndpoint();
            }
            return href;
        }
        if (value instanceof Map) {
            if (seen.containsKey(value)) {
                return seen.get(value);
            }
            Map<Object, Object> source = (Map<Object, Object>) value;
            Map<Object, Object> copy = new LinkedHashMap<>();
            seen.put(value, copy);
            for (Map.Entry<Object, Object> entry : source.entrySet()) {
                copy.put(entry.getKey(),
                        resolveValue(entry.getValue(), hrefs, log, seen));
            }
            return copy;
        }
        if (value instanceof List) {
            if (seen.containsKey(value)) {
                return seen.get(value);
            }
            List<Object> source = (List<Object>) value;
            List<Object> copy = new ArrayList<>(source.size());
            seen.put(value, copy);
            for (Object item : source) {
                copy.add(resolveValue(item, hrefs, log, seen));
            }
            return copy;
        }
        return value;
    }

    private int copySupportFiles(ProjectPaths paths, GenerationLog log)
            throws IOException {
        int copied = 0;
        List<String> names = new ArrayList<>();
        copied += copyFileOrDelete(paths.projectDir.resolve("app.py"),
                paths.outputDir.resolve("app.py"), names);
        copied += copyFileOrDelete(paths.projectDir.resolve("style.css"),
                paths.outputDir.resolve("style.css"), names);
        copied += copyFileOrDelete(paths.projectDir.resolve("script.js"),
                paths.outputDir.resolve("script.js"), names);
        copied += mirrorDirectory(paths.projectDir.resolve("templates"),
                paths.outputDir.resolve("templates"), "templates/", names);
        copied += mirrorDirectory(paths.projectDir.resolve("static"),
                paths.outputDir.resolve("static"), "static/", names);
        copied += mirrorDirectory(paths.projectDir.resolve("assets"),
                paths.outputDir.resolve("assets"), "assets/", names);
        if (!names.isEmpty()) {
            log.copy(String.join(", ", names));
        }
        return copied;
    }

    private int copyFileOrDelete(Path from, Path to, List<String> names)
            throws IOException {
        if (!Files.isRegularFile(from)) {
            Files.deleteIfExists(to);
            return 0;
        }
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        names.add(from.getFileName().toString());
        return 1;
    }

    private int mirrorDirectory(
            Path source, Path target, String label, List<String> names)
            throws IOException {
        deleteTree(target);
        if (!Files.isDirectory(source)) {
            return 0;
        }

        final int[] copied = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isRegularFile()) {
                    Path destination = target.resolve(source.relativize(file));
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    copied[0]++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        names.add(label);
        return copied[0];
    }

    private void deleteTree(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(
                    Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                    Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private GenerationResult finish(
            int exitCode,
            ProjectPaths paths,
            List<String> generatedPages,
            DiagnosticReporter reporter,
            GenerationLog log,
            String appLabel) throws IOException {
        return finish(exitCode, paths, generatedPages, reporter, log, appLabel, null);
    }

    private GenerationResult finish(
            int exitCode,
            ProjectPaths paths,
            List<String> generatedPages,
            DiagnosticReporter reporter,
            GenerationLog log,
            String appLabel,
            ProjectContext projectContext) throws IOException {
        String semanticReport = SemanticReportWriter.write(appLabel, reporter);
        log.report("report.html generated");
        String logText = log.text();
        writeText(paths.reportsDir.resolve("semantic_report.txt"), semanticReport);
        writeText(paths.reportsDir.resolve("generation_log.txt"), logText);
        writeText(paths.reportsDir.resolve("report.html"),
                GenerationReportWriter.write(
                        exitCode,
                        paths.outputDir,
                        paths.reportsDir,
                        generatedPages,
                        reporter,
                        semanticReport,
                        logText));
        return new GenerationResult(exitCode, paths.outputDir, paths.reportsDir,
                generatedPages, reporter, logText, projectContext);
    }

    private static void writeText(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static final class SyntaxErrorCollector extends BaseErrorListener {
        private final DiagnosticReporter reporter;
        private final String sourceFile;

        private SyntaxErrorCollector(DiagnosticReporter reporter, String sourceFile) {
            this.reporter = reporter;
            this.sourceFile = sourceFile;
        }

        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String message,
                RecognitionException exception) {
            reporter.report(Diagnostics.syntaxError(
                    message, line, Math.max(charPositionInLine, 0), sourceFile));
        }
    }
}
