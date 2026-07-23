package Main;

import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.CompilerPhase;
import compilers.diagnostics.Diagnostics;
import compilers.flask.Visitor.ASTPrinter;
import compilers.flask.codegen.disasm.Disassembler;
import compilers.flask.pipeline.FlaskCompilationResult;
import compilers.flask.pipeline.FlaskCompilerPipeline;
import compilers.flask.vm.BytecodeVM;
import compilers.flask.vm.ExecutionLimits;
import compilers.flask.vm.VmCapabilities;
import compilers.flask.vm.VmResult;
import compilers.flask.vm.VmRuntimeException;
import compilers.flask.vm.flask.FlaskResponseNormalizer;
import compilers.flask.vm.flask.PyFlaskApp;
import compilers.flask.vm.flask.PyResponse;
import compilers.flask.vm.values.PyDict;
import compilers.flask.vm.values.PyValue;
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

/** Thin, deterministic command-line boundary for the two compiler frontends. */
public final class UnifiedMain {
    private static final String USAGE = String.join(System.lineSeparator(),
            "Usage: java Main.UnifiedMain <file.py|file.html> [options]",
            "Options:",
            "  --ast",
            "  --symbols",
            "  --diagnostics",
            "  --disassemble                 Python/Flask only",
            "  --run-bytecode                Python/Flask only",
            "  --invoke-route METHOD PATH    Python/Flask only",
            "  --allow-fs-read ROOT          Python/Flask only; read-only",
            "  --debug");

    private UnifiedMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Testable CLI entry point. It owns presentation and exit-code mapping,
     * while all reusable Flask compilation remains in {@link FlaskCompilerPipeline}.
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        boolean debugRequested = args != null
                && Arrays.asList(args).contains("--debug");
        String attemptedSource = sourceLabel(args);
        try {
            Options options = Options.parse(args);
            Path source = options.source.toAbsolutePath().normalize();
            if (!Files.isRegularFile(source)) {
                throw new CliFailure("Source file does not exist or is not a regular file: "
                        + options.source);
            }

            String fileName = source.getFileName().toString().toLowerCase(Locale.ROOT);
            if (fileName.endsWith(".py")) {
                return runFlaskCompiler(source, options, out, err);
            }
            if (fileName.endsWith(".html")) {
                options.rejectPythonOnlyHtmlOptions();
                return runHtmlCssCompiler(source, options, out, err);
            }
            throw new CliFailure("Unsupported file extension: " + source.getFileName());
        } catch (CliFailure failure) {
            err.println("CLI error: " + failure.getMessage());
            err.println(USAGE);
            return 3;
        } catch (Exception failure) {
            Diagnostic diagnostic = Diagnostics.internalCompilerError(
                    CompilerPhase.PIPELINE,
                    "Unexpected internal compiler failure: " + safeMessage(failure),
                    0,
                    0,
                    attemptedSource);
            err.println(diagnostic);
            if (debugRequested) {
                failure.printStackTrace(err);
            }
            return 3;
        }
    }

    private static int runFlaskCompiler(
            Path source,
            Options options,
            PrintStream out,
            PrintStream err) {
        VmCapabilities capabilities = VmCapabilities.NONE;
        if (options.fileSystemRoot != null) {
            try {
                capabilities = VmCapabilities.rootedFileSystemRead(options.fileSystemRoot);
            } catch (IllegalArgumentException invalidRoot) {
                throw new CliFailure("Invalid filesystem read root: "
                        + options.fileSystemRoot, invalidRoot);
            }
        }

        FlaskCompilationResult compilation =
                new FlaskCompilerPipeline().compile(source, "__main__");
        boolean teachingView = !options.hasSelection;

        if ((teachingView || options.ast) && compilation.hasAst()) {
            ASTPrinter printer = new ASTPrinter();
            printer.visitProgram(compilation.getAst());
            out.println("====== Flask AST ======");
            out.print(printer.getOutput());
        }
        if ((teachingView || options.symbols) && compilation.hasSymbolTable()) {
            out.println("====== Flask SYMBOL TABLE ======");
            out.print(compilation.getSymbolTable().format());
        }
        printDiagnostics(compilation, teachingView || options.diagnostics, err);

        if (compilation.hasInternalFailure()) {
            if (options.debug) {
                compilation.getInternalFailure().printStackTrace(err);
            }
            return 3;
        }
        if (compilation.getReporter().hasErrors()) {
            return 1;
        }

        if (options.disassemble) {
            out.print(new Disassembler().disassemble(
                    compilation.requireVerifiedModule()));
        }

        if (!options.runBytecode && options.routeMethod == null) {
            return 0;
        }

        BytecodeVM vm = new BytecodeVM(ExecutionLimits.DEFAULT, capabilities);
        VmResult moduleRun = vm.execute(compilation.requireVerifiedModule());
        out.print(moduleRun.getStdout());
        if (moduleRun.isFailure()) {
            err.println(moduleRun.getTraceback().format());
            return 2;
        }

        if (options.routeMethod != null) {
            PyValue appValue = moduleRun.getGlobals().get("app");
            if (!(appValue instanceof PyFlaskApp)) {
                err.println("RuntimeError: root module did not define a Flask 'app'");
                return 2;
            }
            VmResult routeRun = vm.invokeRoute(
                    (PyFlaskApp) appValue,
                    options.routeMethod,
                    options.routePath,
                    new PyDict());
            out.print(routeRun.getStdout());
            if (routeRun.isFailure()) {
                err.println(routeRun.getTraceback().format());
                return 2;
            }
            try {
                PyResponse response = FlaskResponseNormalizer.normalize(
                        routeRun.getReturnValue());
                out.println(FlaskResponseNormalizer.format(response));
            } catch (VmRuntimeException runtimeFailure) {
                err.println(runtimeFailure.getExceptionValue().getExceptionTypeName()
                        + ": " + runtimeFailure.getExceptionValue().getMessageText());
                return 2;
            }
        }
        return 0;
    }

    private static void printDiagnostics(
            FlaskCompilationResult compilation,
            boolean includeInformational,
            PrintStream err) {
        for (Diagnostic diagnostic : compilation.getReporter().diagnostics()) {
            if (includeInformational || diagnostic.isError() || diagnostic.isWarning()) {
                err.println(diagnostic);
            }
        }
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

    private static String sourceLabel(String[] args) {
        if (args == null || args.length == 0 || args[0] == null
                || args[0].trim().isEmpty() || args[0].startsWith("--")) {
            return "<unknown>";
        }
        try {
            return Paths.get(args[0]).toAbsolutePath().normalize().toString();
        } catch (RuntimeException invalidPath) {
            return "<unknown>";
        }
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
        private boolean disassemble;
        private boolean runBytecode;
        private String routeMethod;
        private String routePath;
        private Path fileSystemRoot;
        private boolean debug;
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
                    case "--disassemble":
                        result.once(option);
                        result.disassemble = true;
                        result.hasSelection = true;
                        break;
                    case "--run-bytecode":
                        result.once(option);
                        result.runBytecode = true;
                        result.hasSelection = true;
                        break;
                    case "--invoke-route":
                        result.once(option);
                        if (index + 2 >= args.length) {
                            throw new CliFailure(
                                    "--invoke-route requires METHOD and PATH");
                        }
                        result.routeMethod = requireValue(
                                args[++index], "HTTP method for --invoke-route");
                        result.routePath = requireValue(
                                args[++index], "request path for --invoke-route");
                        if (!result.routePath.startsWith("/")) {
                            throw new CliFailure("Route path must start with '/'");
                        }
                        result.hasSelection = true;
                        break;
                    case "--allow-fs-read":
                        result.once(option);
                        if (index + 1 >= args.length) {
                            throw new CliFailure("--allow-fs-read requires ROOT");
                        }
                        String root = requireValue(args[++index],
                                "root path for --allow-fs-read");
                        try {
                            result.fileSystemRoot = Paths.get(root)
                                    .toAbsolutePath().normalize();
                        } catch (RuntimeException invalidPath) {
                            throw new CliFailure("Invalid filesystem read root", invalidPath);
                        }
                        break;
                    case "--debug":
                        result.once(option);
                        result.debug = true;
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

        private void rejectPythonOnlyHtmlOptions() {
            if (disassemble || runBytecode || routeMethod != null
                    || fileSystemRoot != null) {
                throw new CliFailure("Python bytecode/runtime options cannot be used with HTML");
            }
        }

        private static String requireValue(String value, String label) {
            if (value == null || value.trim().isEmpty() || value.startsWith("--")) {
                throw new CliFailure("Missing or malformed " + label);
            }
            return value;
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
