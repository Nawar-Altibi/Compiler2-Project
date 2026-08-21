package Main;

import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.SymbolTable.SymbolTableBuilder;
import compilers.flask.Visitor.ASTPrinter;
import compilers.flask.antlr_gen.FlaskLexer;
import compilers.flask.antlr_gen.FlaskParser;
import compilers.flask.ast.builder.ASTBuilder;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.semantic.SemanticAnalyzer;
import compilers.flask.semantic.validation.AstStructuralValidator;
import compilers.flask.semantic.validation.ScopeRuleChecker;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ParseTree;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Thin, deterministic command-line boundary for the two compiler frontends.
 *
 * <p>Python/Flask inputs run the analysis front end (parse, structural
 * validation, symbol table, scope rules, semantic analysis). HTML/Jinja/CSS
 * inputs run the template front end. The generation pipeline (context
 * extraction + Jinja rendering + output/report writing) is wired here in
 * Phase 5 of the refactor plan.</p>
 */
public final class UnifiedMain {
    private static final String USAGE = String.join(System.lineSeparator(),
            "Usage: java Main.UnifiedMain <project-dir|app.py|file.html|file.jinja> [options]",
            "Generation (default for a project directory or app.py):",
            "  --out DIR      output folder for generated pages (default: <project>/output)",
            "  --reports DIR  compiler reports folder (default: <project>/compiler_output)",
            "  --quiet        suppress the stdout summary",
            "Analysis views (single file; disables generation for .py):",
            "  --ast          print the AST",
            "  --symbols      print the symbol table",
            "  --diagnostics  print all diagnostics",
            "  --debug        print Java stack traces on internal failures");

    private UnifiedMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Testable CLI entry point; owns presentation and exit-code mapping only. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        boolean debugRequested = args != null
                && Arrays.asList(args).contains("--debug");
        try {
            Options options = Options.parse(args);
            Path source = options.source.toAbsolutePath().normalize();
            boolean isDirectory = Files.isDirectory(source);
            if (!isDirectory && !Files.isRegularFile(source)) {
                throw new CliFailure("Source file does not exist or is not a regular file: "
                        + options.source);
            }

            if (isDirectory) {
                return runGeneration(source, options, out, err);
            }
            String fileName = source.getFileName().toString().toLowerCase(Locale.ROOT);
            if (fileName.endsWith(".py")) {
                // Plan section 8.2: generation is the default for app.py;
                // the analysis flags switch to teaching views instead.
                if (options.hasSelection) {
                    return runFlaskFrontEnd(source, options, out, err);
                }
                return runGeneration(source, options, out, err);
            }
            if (fileName.endsWith(".html") || fileName.endsWith(".jinja")) {
                return runHtmlCssCompiler(source, options, out, err);
            }
            throw new CliFailure("Unsupported file extension: " + source.getFileName());
        } catch (CliFailure failure) {
            err.println("CLI error: " + failure.getMessage());
            err.println(USAGE);
            return 3;
        } catch (Exception failure) {
            err.println("Internal error: " + safeMessage(failure));
            if (debugRequested) {
                failure.printStackTrace(err);
            }
            return 3;
        }
    }

    /** Runs the full generation pipeline and prints a short summary. */
    private static int runGeneration(
            Path source,
            Options options,
            PrintStream out,
            PrintStream err) throws Exception {
        final compilers.pipeline.GenerationPipeline.ProjectPaths paths;
        try {
            paths = compilers.pipeline.GenerationPipeline.ProjectPaths.resolve(
                    source, options.outDir, options.reportsDir);
        } catch (java.io.IOException badLayout) {
            throw new CliFailure(badLayout.getMessage());
        }

        compilers.pipeline.GenerationResult result =
                new compilers.pipeline.GenerationPipeline().run(paths);

        for (compilers.diagnostics.Diagnostic diagnostic
                : result.getReporter().diagnostics()) {
            if (diagnostic.isError() || diagnostic.isWarning()) {
                err.println(diagnostic);
            }
        }
        if (!options.quiet) {
            out.println("====== GENERATION ======");
            out.print(result.getLogText());
            out.println("output:  " + result.getOutputDir());
            out.println("reports: " + result.getReportsDir());
            out.println("dashboard: " + result.getReportsDir().resolve("report.html"));
        }
        return result.getExitCode();
    }

    /**
     * Runs the complete Python/Flask analysis front end with the frozen gate
     * order: parse, structural validation, symbol table, scope rules,
     * semantic analysis. Returns 1 on any source error, 0 when clean.
     */
    private static int runFlaskFrontEnd(
            Path source,
            Options options,
            PrintStream out,
            PrintStream err) throws Exception {
        String sourceLabel = source.getFileName().toString();
        AntlrErrorCollector syntaxErrors = new AntlrErrorCollector(sourceLabel, err);

        CharStream input = CharStreams.fromPath(source);
        FlaskLexer lexer = new FlaskLexer(input);
        lexer.removeErrorListeners();
        lexer.addErrorListener(syntaxErrors);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        FlaskParser parser = new FlaskParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(syntaxErrors);
        ParseTree tree = parser.program();
        if (syntaxErrors.hasErrors()) {
            return 1;
        }

        ASTNode root = new ASTBuilder(sourceLabel).visit(tree);
        if (!(root instanceof ProgramNode)) {
            err.println("[AST Error] " + sourceLabel);
            err.println("  Python AST builder produced no program");
            return 1;
        }
        ProgramNode program = (ProgramNode) root;

        DiagnosticReporter reporter = new DiagnosticReporter();
        boolean structurallyValid =
                new AstStructuralValidator(reporter, sourceLabel).validate(program);
        if (structurallyValid) {
            SymbolTableBuilder symbols = new SymbolTableBuilder(reporter, sourceLabel);
            program.accept(symbols);
            new ScopeRuleChecker(symbols.getSymbolTable(), reporter, sourceLabel)
                    .check(program);
            new SemanticAnalyzer(symbols.getSymbolTable(), sourceLabel, reporter)
                    .analyze(program);

            boolean teachingView = !options.hasSelection;
            if (teachingView || options.ast) {
                ASTPrinter printer = new ASTPrinter();
                printer.visitProgram(program);
                out.println("====== Flask AST ======");
                out.print(printer.getOutput());
            }
            if (teachingView || options.symbols) {
                out.println("====== Flask SYMBOL TABLE ======");
                out.print(symbols.getSymbolTable().format());
            }
        }

        boolean teachingView = !options.hasSelection;
        for (Diagnostic diagnostic : reporter.diagnostics()) {
            if (teachingView || options.diagnostics
                    || diagnostic.isError() || diagnostic.isWarning()) {
                err.println(diagnostic);
            }
        }
        return reporter.hasErrors() ? 1 : 0;
    }

    private static int runHtmlCssCompiler(
            Path source,
            Options options,
            PrintStream out,
            PrintStream err) throws Exception {
        AntlrErrorCollector syntaxErrors = new AntlrErrorCollector(source.toString(), err);
        CharStream input = CharStreams.fromPath(source);
        compilers.html_css.antlr.HtmlCssLexer lexer =
                new compilers.html_css.antlr.HtmlCssLexer(input);
        lexer.removeErrorListeners();
        lexer.addErrorListener(syntaxErrors);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        compilers.html_css.antlr.HtmlCssParser parser =
                new compilers.html_css.antlr.HtmlCssParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(syntaxErrors);
        ParseTree tree = parser.htmlDocument();
        if (syntaxErrors.hasErrors()) {
            return 1;
        }

        compilers.html_css.Visitor.HtmlAstBuilder builder =
                new compilers.html_css.Visitor.HtmlAstBuilder(err, options.debug);
        compilers.html_css.ast.HtmlNode ast = builder.visit(tree);
        if (ast == null) {
            err.println("[AST Error] " + source);
            err.println("  HTML AST builder produced no document");
            return 1;
        }

        boolean teachingView = !options.hasSelection;
        if (teachingView || options.ast) {
            out.println("====== HTML/CSS AST ======");
            ast.accept(new compilers.html_css.Visitor.AstPrintVisitor(out));
        }
        if (teachingView || options.symbols) {
            compilers.html_css.SymbolTable.SymbolTableBuilder symbolBuilder =
                    new compilers.html_css.SymbolTable.SymbolTableBuilder();
            compilers.html_css.SymbolTable.SymbolTable table = symbolBuilder.build(ast);
            out.println("====== HTML/CSS SYMBOL TABLE ======");
            out.print(table.printSymbolTable());
        }
        return 0;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private static final class AntlrErrorCollector extends BaseErrorListener {
        private final String sourceFile;
        private final PrintStream err;
        private int count;

        private AntlrErrorCollector(String sourceFile, PrintStream err) {
            this.sourceFile = sourceFile;
            this.err = err;
        }

        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String message,
                RecognitionException failure) {
            count++;
            err.println("[Parser Error] " + sourceFile + ":" + line + ":"
                    + Math.max(charPositionInLine, 0));
            err.println("  Syntax error: " + message);
        }

        private boolean hasErrors() {
            return count > 0;
        }
    }

    private static final class Options {
        private final Path source;
        private boolean ast;
        private boolean symbols;
        private boolean diagnostics;
        private boolean debug;
        private boolean quiet;
        private Path outDir;
        private Path reportsDir;
        private boolean hasSelection;
        private final Set<String> seen = new HashSet<>();

        private Options(Path source) {
            this.source = source;
        }

        private static Options parse(String[] args) {
            if (args == null || args.length == 0) {
                throw new CliFailure("A source file is required");
            }
            if (args[0] == null || args[0].trim().isEmpty()
                    || args[0].startsWith("--")) {
                throw new CliFailure("The first argument must be a source file");
            }
            final Path source;
            try {
                source = Paths.get(args[0]);
            } catch (RuntimeException invalidPath) {
                throw new CliFailure("Invalid source path", invalidPath);
            }
            Options result = new Options(source);
            for (int index = 1; index < args.length; index++) {
                String option = args[index];
                if (option == null) {
                    throw new CliFailure("Null command-line option");
                }
                switch (option) {
                    case "--ast":
                        result.once(option);
                        result.ast = true;
                        result.hasSelection = true;
                        break;
                    case "--symbols":
                        result.once(option);
                        result.symbols = true;
                        result.hasSelection = true;
                        break;
                    case "--diagnostics":
                        result.once(option);
                        result.diagnostics = true;
                        result.hasSelection = true;
                        break;
                    case "--debug":
                        result.once(option);
                        result.debug = true;
                        break;
                    case "--quiet":
                        result.once(option);
                        result.quiet = true;
                        break;
                    case "--out":
                        result.once(option);
                        result.outDir = pathValue(args, ++index, "--out");
                        break;
                    case "--reports":
                        result.once(option);
                        result.reportsDir = pathValue(args, ++index, "--reports");
                        break;
                    default:
                        throw new CliFailure("Unknown option: " + option);
                }
            }
            return result;
        }

        private void once(String option) {
            if (!seen.add(option)) {
                throw new CliFailure("Duplicate option: " + option);
            }
        }

        private static Path pathValue(String[] args, int index, String option) {
            if (index >= args.length || args[index] == null
                    || args[index].trim().isEmpty() || args[index].startsWith("--")) {
                throw new CliFailure(option + " requires a directory value");
            }
            try {
                return Paths.get(args[index]);
            } catch (RuntimeException invalidPath) {
                throw new CliFailure("Invalid " + option + " path", invalidPath);
            }
        }
    }

    private static final class CliFailure extends RuntimeException {
        private CliFailure(String message) {
            super(message);
        }

        private CliFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
