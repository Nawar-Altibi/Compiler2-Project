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
import java.util.Map;
import java.util.Objects;

/** Phase-7 exception/finally integration matrix through the real front end. */
public final class FlaskExceptionVmHarness {
    private static final String SOURCE_FILE = "phase7-exceptions.py";
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        run("caught order, lazy assert, alias clear, else, and finally",
                FlaskExceptionVmHarness::testCaughtAssertAndAlias);
        run("try else only follows normal completion",
                FlaskExceptionVmHarness::testElsePaths);
        run("finally observes and overrides return",
                FlaskExceptionVmHarness::testFinallyReturn);
        run("bare raise crosses a called function and restores nested handlers",
                FlaskExceptionVmHarness::testDynamicBareRaise);
        run("bare raise sees an exception driving a caller finally",
                FlaskExceptionVmHarness::testBareRaiseAcrossFinallyCall);
        run("caught exception inside finally resumes the outer exception",
                FlaskExceptionVmHarness::testCaughtExceptionInsideFinally);
        run("unmatched exception propagates with a Python traceback",
                FlaskExceptionVmHarness::testUnmatched);
        run("explicit raise records its source frame exactly once",
                FlaskExceptionVmHarness::testExplicitRaiseTraceback);
        run("raise-from records direct cause and suppresses implicit context",
                FlaskExceptionVmHarness::testCause);
        run("raise-from-None suppresses implicit exception context",
                FlaskExceptionVmHarness::testSuppressedContext);
        run("break and continue unwind nested finally suites",
                FlaskExceptionVmHarness::testLoopTransfers);
        run("nested cleanup preserves and then overrides pending transfers",
                FlaskExceptionVmHarness::testNestedCleanupTransfers);
        run("exception normalization, tuple matching, subclasses, and errors",
                FlaskExceptionVmHarness::testExceptionNormalization);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " exception VM test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " exception VM tests passed.");
    }

    private static void testCaughtAssertAndAlias() {
        VmResult result = execute(lines(
                "message_calls = 0",
                "def message():",
                "    global message_calls",
                "    message_calls += 1",
                "    return 'boom'",
                "log = ''",
                "try:",
                "    assert False, message()",
                "except TypeError:",
                "    log = 'wrong'",
                "except AssertionError as err:",
                "    log = 'caught:' + str(err)",
                "else:",
                "    log = 'else'",
                "finally:",
                "    log += ':finally'",
                "alias_cleared = False",
                "try:",
                "    leaked = err",
                "except NameError:",
                "    alias_cleared = True",
                "assert True, message()"));
        success(result);
        equal(new PyString("caught:boom:finally"),
                result.getGlobals().get("log"), "handler/finally log");
        equal(BigInteger.ONE, integer(result, "message_calls"),
                "assert message is lazy");
        equal(PyBool.TRUE, result.getGlobals().get("alias_cleared"),
                "except alias clear");
    }

    private static void testElsePaths() {
        VmResult result = execute(lines(
                "log = ''",
                "try:",
                "    value = 1",
                "except Exception:",
                "    log += 'X'",
                "else:",
                "    log += 'E'",
                "try:",
                "    value = 1 // 0",
                "except ZeroDivisionError:",
                "    log += 'H'",
                "else:",
                "    log += 'bad'"));
        success(result);
        equal(new PyString("EH"), result.getGlobals().get("log"),
                "try else selection");
    }

    private static void testFinallyReturn() {
        VmResult result = execute(lines(
                "log = ''",
                "def observed():",
                "    global log",
                "    try:",
                "        return 1",
                "    finally:",
                "        log += 'F'",
                "def overridden():",
                "    try:",
                "        return 1",
                "    finally:",
                "        return 2",
                "first = observed()",
                "second = overridden()"));
        success(result);
        equal(BigInteger.ONE, integer(result, "first"), "preserved return");
        equal(BigInteger.valueOf(2), integer(result, "second"),
                "finally return override");
        equal(new PyString("F"), result.getGlobals().get("log"),
                "finally side effect");
    }

    private static void testDynamicBareRaise() {
        VmResult result = execute(lines(
                "def reraiser():",
                "    raise",
                "dynamic = False",
                "restored = False",
                "try:",
                "    1 // 0",
                "except ZeroDivisionError:",
                "    try:",
                "        reraiser()",
                "    except ZeroDivisionError:",
                "        dynamic = True",
                "    try:",
                "        raise ValueError('nested')",
                "    except ValueError:",
                "        pass",
                "    try:",
                "        raise",
                "    except ZeroDivisionError:",
                "        restored = True"));
        success(result);
        equal(PyBool.TRUE, result.getGlobals().get("dynamic"),
                "called-function bare raise");
        equal(PyBool.TRUE, result.getGlobals().get("restored"),
                "nested handled state restoration");
    }

    /**
     * CPython keeps the active exception visible while executing an
     * exception-driven finally, including in Python functions called by that
     * finally.  This used to report "No active exception to reraise" because
     * the VM only searched the handled-exception deque.
     */
    private static void testBareRaiseAcrossFinallyCall() {
        VmResult result = execute(lines(
                "def reraiser():",
                "    raise",
                "caught = False",
                "try:",
                "    try:",
                "        raise ValueError('outer')",
                "    finally:",
                "        reraiser()",
                "except ValueError:",
                "    caught = True"));
        success(result);
        equal(PyBool.TRUE, result.getGlobals().get("caught"),
                "bare raise inherited from caller finally");
    }

    /** A locally handled replacement must not discard the older cleanup transfer. */
    private static void testCaughtExceptionInsideFinally() {
        VmResult result = execute(lines(
                "inner = 0",
                "outer = False",
                "try:",
                "    try:",
                "        raise ValueError('outer')",
                "    finally:",
                "        try:",
                "            raise TypeError('inner')",
                "        except TypeError:",
                "            inner = 2",
                "except ValueError:",
                "    outer = True"));
        success(result);
        equal(BigInteger.valueOf(2), integer(result, "inner"),
                "inner handler completed");
        equal(PyBool.TRUE, result.getGlobals().get("outer"),
                "outer exception resumed");
    }

    private static void testUnmatched() {
        VmResult result = execute(lines(
                "try:",
                "    raise ValueError('unmatched')",
                "except TypeError:",
                "    pass"));
        check(result.isFailure(), "unmatched exception unexpectedly succeeded");
        equal("ValueError", result.getTraceback().getException()
                .getExceptionTypeName(), "unmatched type");
        check(result.getTraceback().format().contains("phase7-exceptions.py"),
                "source traceback missing");
    }

    private static void testExplicitRaiseTraceback() {
        VmResult result = execute(lines("raise ValueError('once')"));
        check(result.isFailure(), "explicit raise unexpectedly succeeded");
        equal(1, result.getTraceback().getEntries().size(),
                "explicit raise traceback entry count");
        equal("ValueError", result.getTraceback().getException()
                .getExceptionTypeName(), "explicit raise type");
    }

    private static void testCause() {
        VmResult result = execute(lines(
                "try:",
                "    raise ValueError('context')",
                "except ValueError:",
                "    raise TypeError('outer') from RuntimeError('cause')"));
        check(result.isFailure(), "raise-from unexpectedly succeeded");
        equal("TypeError", result.getTraceback().getException()
                .getExceptionTypeName(), "outer exception");
        check(result.getTraceback().getExplicitCause() != null,
                "direct cause missing");
        equal("RuntimeError", result.getTraceback().getExplicitCause()
                .getException().getExceptionTypeName(), "cause type");
        check(result.getTraceback().format().contains("direct cause"),
                "cause separator missing");
    }

    private static void testSuppressedContext() {
        VmResult result = execute(lines(
                "try:",
                "    raise ValueError('context')",
                "except ValueError:",
                "    raise TypeError('outer') from None"));
        check(result.isFailure(), "raise from None unexpectedly succeeded");
        check(result.getTraceback().getExplicitCause() == null,
                "raise from None created an explicit cause");
        check(result.getTraceback().isContextSuppressed(),
                "raise from None did not suppress context");
        check(!result.getTraceback().format().contains("During handling"),
                "suppressed context was printed");
    }

    private static void testLoopTransfers() {
        VmResult result = execute(lines(
                "log = ''",
                "for item in [1, 2, 3]:",
                "    try:",
                "        if item == 1:",
                "            continue",
                "        if item == 2:",
                "            break",
                "    finally:",
                "        log += str(item)",
                "else:",
                "    log += 'else'"));
        success(result);
        equal(new PyString("12"), result.getGlobals().get("log"),
                "loop transfer cleanup order");
    }

    private static void testNestedCleanupTransfers() {
        VmResult result = execute(lines(
                "log = ''",
                "def nested_return():",
                "    global log",
                "    try:",
                "        return 5",
                "    finally:",
                "        try:",
                "            log += 'A'",
                "        finally:",
                "            log += 'B'",
                "def handler_return():",
                "    try:",
                "        raise ValueError('handled')",
                "    except ValueError as local_error:",
                "        return 7",
                "first = nested_return()",
                "second = handler_return()",
                "replacement = False",
                "try:",
                "    try:",
                "        raise ValueError('old')",
                "    finally:",
                "        raise TypeError('new')",
                "except TypeError:",
                "    replacement = True"));
        success(result);
        equal(BigInteger.valueOf(5), integer(result, "first"),
                "nested cleanup preserved return");
        equal(BigInteger.valueOf(7), integer(result, "second"),
                "handler return");
        equal(new PyString("AB"), result.getGlobals().get("log"),
                "nested finally order");
        equal(PyBool.TRUE, result.getGlobals().get("replacement"),
                "finally exception override");
    }

    private static void testExceptionNormalization() {
        VmResult valid = execute(lines(
                "class Custom(ValueError):",
                "    pass",
                "subclass = False",
                "tuple_match = False",
                "try:",
                "    raise Custom",
                "except (TypeError, ValueError) as caught:",
                "    subclass = isinstance(caught, Custom)",
                "    tuple_match = True"));
        success(valid);
        equal(PyBool.TRUE, valid.getGlobals().get("subclass"),
                "user exception subclass");
        equal(PyBool.TRUE, valid.getGlobals().get("tuple_match"),
                "exception tuple match");

        VmResult invalidRaise = execute(lines("raise 1"));
        check(invalidRaise.isFailure(), "raising int unexpectedly succeeded");
        equal("TypeError", invalidRaise.getTraceback().getException()
                .getExceptionTypeName(), "invalid raise type");

        VmResult invalidHandler = execute(lines(
                "try:",
                "    raise ValueError('x')",
                "except 1:",
                "    pass"));
        check(invalidHandler.isFailure(), "invalid handler unexpectedly succeeded");
        equal("TypeError", invalidHandler.getTraceback().getException()
                .getExceptionTypeName(), "invalid handler type");

        VmResult bare = execute(lines("raise"));
        check(bare.isFailure(), "bare raise without state unexpectedly succeeded");
        equal("RuntimeError", bare.getTraceback().getException()
                .getExceptionTypeName(), "bare raise without state");
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
