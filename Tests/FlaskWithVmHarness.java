import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.SymbolTable.SymbolTableBuilder;
import compilers.flask.antlr_gen.FlaskLexer;
import compilers.flask.antlr_gen.FlaskParser;
import compilers.flask.ast.builder.ASTBuilder;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.codegen.FlaskBytecodeCompiler;
import compilers.flask.codegen.GenerationResult;
import compilers.flask.codegen.analysis.BindingAnalysisResult;
import compilers.flask.codegen.analysis.BindingResolver;
import compilers.flask.semantic.SemanticAnalyzer;
import compilers.flask.semantic.validation.AstStructuralValidator;
import compilers.flask.semantic.validation.ScopeRuleChecker;
import compilers.flask.vm.BytecodeVM;
import compilers.flask.vm.VmResult;
import compilers.flask.vm.values.PyBool;
import compilers.flask.vm.values.PyInt;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyValue;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Phase-7 context-manager matrix through generation, verification, and VM. */
public final class FlaskWithVmHarness {
    private static final String SOURCE_FILE = "phase7-with.py";
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        run("normal enter, target unpack, and exit",
                FlaskWithVmHarness::testNormalAndTarget);
        run("exception suppression and exception tuple",
                FlaskWithVmHarness::testSuppression);
        run("non-suppression propagates after exit",
                FlaskWithVmHarness::testNonSuppression);
        run("enter failure never calls exit",
                FlaskWithVmHarness::testEnterFailure);
        run("multiple managers exit right-to-left",
                FlaskWithVmHarness::testMultipleManagers);
        run("outer manager can suppress an inner exit failure",
                FlaskWithVmHarness::testOuterSuppressesInnerExitFailure);
        run("target binding failure still invokes exit",
                FlaskWithVmHarness::testTargetFailure);
        run("exit failure replaces the body exception",
                FlaskWithVmHarness::testExitFailure);
        run("return, break, and continue cross active managers",
                FlaskWithVmHarness::testAbruptTransfers);
        run("suppression preserves an outer handled exception",
                FlaskWithVmHarness::testOuterHandledState);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " with VM test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " context-manager tests passed.");
    }

    private static void testNormalAndTarget() {
        VmResult result = execute(lines(
                "log = ''",
                "class Pair:",
                "    def __enter__(self):",
                "        global log",
                "        log += 'E'",
                "        return (2, 3)",
                "    def __exit__(self, kind, value, traceback):",
                "        global log",
                "        log += 'X'",
                "        return False",
                "with Pair() as pair:",
                "    (left, right) = pair",
                "    total = left + right",
                "    log += 'B'"));
        success(result);
        equal(new PyString("EBX"), result.getGlobals().get("log"),
                "normal with order");
        equal(BigInteger.valueOf(5), integer(result, "total"),
                "with unpack target");
    }

    private static void testSuppression() {
        VmResult result = execute(lines(
                "log = ''",
                "saw_type = False",
                "saw_value = False",
                "saw_traceback = False",
                "class Suppress:",
                "    def __enter__(self):",
                "        return self",
                "    def __exit__(self, kind, value, traceback):",
                "        global log, saw_type, saw_value, saw_traceback",
                "        log += 'X'",
                "        saw_type = kind is ValueError",
                "        saw_value = isinstance(value, ValueError)",
                "        saw_traceback = not (traceback is None)",
                "        return True",
                "with Suppress():",
                "    log += 'B'",
                "    raise ValueError('hidden')",
                "log += 'A'"));
        success(result);
        equal(new PyString("BXA"), result.getGlobals().get("log"),
                "suppressed control flow");
        equal(PyBool.TRUE, result.getGlobals().get("saw_type"),
                "exit exception type");
        equal(PyBool.TRUE, result.getGlobals().get("saw_value"),
                "exit exception value");
        equal(PyBool.TRUE, result.getGlobals().get("saw_traceback"),
                "exit traceback value");
    }

    private static void testNonSuppression() {
        VmResult result = execute(lines(
                "log = ''",
                "class Keep:",
                "    def __enter__(self):",
                "        return self",
                "    def __exit__(self, kind, value, traceback):",
                "        global log",
                "        log += 'X'",
                "        return False",
                "try:",
                "    with Keep():",
                "        raise ValueError('kept')",
                "except ValueError:",
                "    log += 'H'"));
        success(result);
        equal(new PyString("XH"), result.getGlobals().get("log"),
                "non-suppressed exception");
    }

    private static void testEnterFailure() {
        VmResult result = execute(lines(
                "log = ''",
                "class Broken:",
                "    def __enter__(self):",
                "        global log",
                "        log += 'E'",
                "        raise ValueError('enter')",
                "    def __exit__(self, kind, value, traceback):",
                "        global log",
                "        log += 'X'",
                "        return False",
                "try:",
                "    with Broken():",
                "        log += 'B'",
                "except ValueError:",
                "    log += 'H'"));
        success(result);
        equal(new PyString("EH"), result.getGlobals().get("log"),
                "enter failure cleanup");
    }

    private static void testMultipleManagers() {
        VmResult result = execute(lines(
                "log = ''",
                "class Named:",
                "    def __init__(self, name):",
                "        self.name = name",
                "    def __enter__(self):",
                "        global log",
                "        log += 'E' + self.name",
                "        return self",
                "    def __exit__(self, kind, value, traceback):",
                "        global log",
                "        log += 'X' + self.name",
                "        return False",
                "with Named('1') as first, Named('2') as second:",
                "    log += 'B'"));
        success(result);
        equal(new PyString("E1E2BX2X1"), result.getGlobals().get("log"),
                "manager nesting order");
    }

    private static void testOuterSuppressesInnerExitFailure() {
        VmResult result = execute(lines(
                "log = ''",
                "completed = False",
                "class Outer:",
                "    def __enter__(self):",
                "        return self",
                "    def __exit__(self, kind, value, traceback):",
                "        global log",
                "        log += 'O'",
                "        return True",
                "class Inner:",
                "    def __enter__(self):",
                "        return self",
                "    def __exit__(self, kind, value, traceback):",
                "        global log",
                "        log += 'I'",
                "        raise ValueError('inner exit')",
                "with Outer(), Inner():",
                "    log += 'B'",
                "    raise TypeError('body')",
                "completed = True"));
        success(result);
        equal(new PyString("BIO"), result.getGlobals().get("log"),
                "nested exit replacement/suppression order");
        equal(PyBool.TRUE, result.getGlobals().get("completed"),
                "outer suppression continuation");
    }

    private static void testTargetFailure() {
        VmResult result = execute(lines(
                "log = ''",
                "class Scalar:",
                "    def __enter__(self):",
                "        return 1",
                "    def __exit__(self, kind, value, traceback):",
                "        global log",
                "        log += 'X'",
                "        return False",
                "holder = 1",
                "try:",
                "    with Scalar() as holder.value:",
                "        log += 'B'",
                "except AttributeError:",
                "    log += 'H'"));
        success(result);
        equal(new PyString("XH"), result.getGlobals().get("log"),
                "target failure invokes exit");
    }

    private static void testExitFailure() {
        VmResult result = execute(lines(
                "caught = False",
                "class Explode:",
                "    def __enter__(self):",
                "        return self",
                "    def __exit__(self, kind, value, traceback):",
                "        raise RuntimeError('exit')",
                "try:",
                "    with Explode():",
                "        raise ValueError('body')",
                "except RuntimeError:",
                "    caught = True"));
        success(result);
        equal(PyBool.TRUE, result.getGlobals().get("caught"),
                "exit exception replacement");
    }

    private static void testAbruptTransfers() {
        VmResult result = execute(lines(
                "log = ''",
                "class Mark:",
                "    def __enter__(self):",
                "        global log",
                "        log += 'E'",
                "        return self",
                "    def __exit__(self, kind, value, traceback):",
                "        global log",
                "        log += 'X'",
                "        return False",
                "def returning():",
                "    with Mark():",
                "        return 7",
                "returned = returning()",
                "for item in [1, 2, 3]:",
                "    with Mark():",
                "        if item == 1:",
                "            continue",
                "        break"));
        success(result);
        equal(BigInteger.valueOf(7), integer(result, "returned"),
                "return through with");
        equal(new PyString("EXEXEX"), result.getGlobals().get("log"),
                "abrupt with exits");
    }

    private static void testOuterHandledState() {
        VmResult result = execute(lines(
                "preserved = False",
                "class Suppress:",
                "    def __enter__(self):",
                "        return self",
                "    def __exit__(self, kind, value, traceback):",
                "        return True",
                "try:",
                "    raise ValueError('outer')",
                "except ValueError:",
                "    with Suppress():",
                "        raise TypeError('inner')",
                "    try:",
                "        raise",
                "    except ValueError:",
                "        preserved = True"));
        success(result);
        equal(PyBool.TRUE, result.getGlobals().get("preserved"),
                "outer handled exception preservation");
    }

    private static VmResult execute(String source) {
        GenerationResult generation = compile(source);
        check(generation.isSuccess(), diagnostics(generation.getReporter()));
        return new BytecodeVM().execute(generation.requireModule());
    }

    private static GenerationResult compile(String source) {
        ProgramNode program = build(source);
        DiagnosticReporter reporter = new DiagnosticReporter();
        if (!new AstStructuralValidator(reporter, SOURCE_FILE).validate(program)) {
            return GenerationResult.failure(reporter);
        }
        SymbolTableBuilder symbols = new SymbolTableBuilder(reporter, SOURCE_FILE);
        program.accept(symbols);
        new ScopeRuleChecker(symbols.getSymbolTable(), reporter, SOURCE_FILE)
                .check(program);
        new SemanticAnalyzer(symbols.getSymbolTable(), SOURCE_FILE, reporter)
                .analyze(program);
        if (reporter.hasErrors()) return GenerationResult.failure(reporter);
        BindingAnalysisResult bindings = new BindingResolver().resolve(program);
        return new FlaskBytecodeCompiler().compile(
                program, bindings, SOURCE_FILE, "__main__", reporter);
    }

    private static ProgramNode build(String source) {
        List<String> issues = new ArrayList<>();
        FlaskLexer lexer = new FlaskLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new CollectingErrorListener("lexer", issues));
        FlaskParser parser = new FlaskParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(new CollectingErrorListener("parser", issues));
        ASTNode root = new ASTBuilder(SOURCE_FILE).visit(parser.program());
        check(issues.isEmpty(), "syntax errors: " + issues + "\n" + source);
        check(root instanceof ProgramNode, "AST root is not ProgramNode");
        return (ProgramNode) root;
    }

    private static BigInteger integer(VmResult result, String name) {
        PyValue value = result.getGlobals().get(name);
        check(value instanceof PyInt, name + " is not an int: " + value);
        return ((PyInt) value).getValue();
    }

    private static void success(VmResult result) {
        check(result.isSuccess(), result.isFailure()
                ? result.getTraceback().format() : "execution failed");
    }

    private static String diagnostics(DiagnosticReporter reporter) {
        StringBuilder result = new StringBuilder();
        for (Diagnostic diagnostic : reporter.diagnostics()) {
            result.append(diagnostic).append('\n');
        }
        return result.toString();
    }

    private static String lines(String... sourceLines) {
        return String.join("\n", sourceLines) + "\n";
    }

    private static void run(String name, CheckedRunnable test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable failure) {
            failed++;
            System.err.println("FAIL  " + name + " -> " + failure.getMessage());
            failure.printStackTrace(System.err);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected <" + expected
                    + "> but was <" + actual + ">");
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable { void run() throws Exception; }

    private static final class CollectingErrorListener extends BaseErrorListener {
        private final String phase;
        private final List<String> issues;
        private CollectingErrorListener(String phase, List<String> issues) {
            this.phase = phase;
            this.issues = issues;
        }
        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String message,
                RecognitionException exception) {
            issues.add(phase + " " + line + ":" + charPositionInLine
                    + " " + message);
        }
    }
}
