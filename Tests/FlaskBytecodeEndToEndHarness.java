import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticCategory;
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
import compilers.flask.codegen.disasm.Disassembler;
import compilers.flask.semantic.SemanticAnalyzer;
import compilers.flask.semantic.validation.AstStructuralValidator;
import compilers.flask.semantic.validation.ScopeRuleChecker;
import compilers.flask.vm.BytecodeVM;
import compilers.flask.vm.ExecutionLimits;
import compilers.flask.vm.VmResult;
import compilers.flask.vm.values.PyBool;
import compilers.flask.vm.values.PyDict;
import compilers.flask.vm.values.PyInt;
import compilers.flask.vm.values.PyList;
import compilers.flask.vm.values.PyNone;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Full Part-2 path: AST -> binding -> generation -> verification -> VM. */
public final class FlaskBytecodeEndToEndHarness {
    private static final String SOURCE_FILE = "part2-e2e.py";
    private static int passed;
    private static int failed;

    private FlaskBytecodeEndToEndHarness() {
    }

    public static void main(String[] args) {
        run("straight-line values and mutation",
                FlaskBytecodeEndToEndHarness::testStraightLine);
        run("branches, short circuit, and chained comparisons",
                FlaskBytecodeEndToEndHarness::testBranchesAndComparisons);
        run("while/for else, break, continue, and temp cleanup",
                FlaskBytecodeEndToEndHarness::testLoops);
        run("unreachable structured code after loop transfer is pruned",
                FlaskBytecodeEndToEndHarness::testUnreachableLoopCode);
        run("recursive unpack, delete, augmented subscript, and f-string",
                FlaskBytecodeEndToEndHarness::testTargetsAndFString);
        run("runtime failure and source traceback",
                FlaskBytecodeEndToEndHarness::testRuntimeFailure);
        run("fresh execution isolation and deterministic disassembly",
                FlaskBytecodeEndToEndHarness::testIsolationAndDisassembly);
        run("instruction limit interrupts an infinite loop",
                FlaskBytecodeEndToEndHarness::testInstructionLimit);
        run("cancellation interrupts an infinite loop and leaves the VM reusable",
                FlaskBytecodeEndToEndHarness::testCancellationAndRecovery);
        run("Phase-7 assert and executable runtime symbols",
                FlaskBytecodeEndToEndHarness::testCodegenFailures);
        run("terminating if/elif/else arms as the final block",
                FlaskBytecodeEndToEndHarness::testTerminatingBranchReturns);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " bytecode E2E test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " Flask bytecode E2E tests passed.");
    }

    private static void testStraightLine() {
        VmResult result = execute(lines(
                "huge = 9999999999999999999999999999999999999999",
                "a = 10",
                "b = 3",
                "calc = a + b * 2",
                "floor = -7 // 3",
                "remainder = -7 % 3",
                "truth = not 0",
                "numbers = [10, 20, 30]",
                "numbers[1] = 25",
                "picked = numbers[1]",
                "del numbers[0]",
                "pair = (a, b)",
                "unique = {1, 1, 2}",
                "mapping = {\"x\": calc, \"x\": floor}"));
        check(result.isSuccess(), traceback(result));
        Map<String, PyValue> globals = result.getGlobals();
        equal(new BigInteger("9999999999999999999999999999999999999999"),
                intValue(globals, "huge"), "arbitrary precision global");
        equal(BigInteger.valueOf(16), intValue(globals, "calc"), "arithmetic");
        equal(BigInteger.valueOf(-3), intValue(globals, "floor"), "floor division");
        equal(BigInteger.valueOf(2), intValue(globals, "remainder"), "modulo");
        equal(PyBool.TRUE, globals.get("truth"), "unary not");
        equal(BigInteger.valueOf(25), intValue(globals, "picked"), "subscript load");
        equal("[25, 30]", globals.get("numbers").repr(), "subscript store/delete");
        PyDict mapping = (PyDict) globals.get("mapping");
        equal(new PyInt(BigInteger.valueOf(-3)),
                mapping.find(new PyString("x")).orElseThrow(), "duplicate dict key");
        equal(PyNone.INSTANCE, result.getReturnValue(), "implicit module return");
        equal(new PyString("__main__"), globals.get("__name__"), "module __name__");
        equal(new PyString(""), globals.get("__package__"), "module __package__");
    }

    private static void testBranchesAndComparisons() {
        VmResult result = execute(lines(
                "and_value = 0 and (1 // 0)",
                "or_value = 1 or (1 // 0)",
                "chain_true = 1 < 2 < 3",
                "chain_false = 3 < 2 < 1",
                "chain_safe = 3 < 2 < (1 // 0)",
                "choice = 0",
                "if chain_false:",
                "    choice = 1",
                "elif chain_true:",
                "    choice = 2",
                "else:",
                "    choice = 3"));
        check(result.isSuccess(), traceback(result));
        Map<String, PyValue> globals = result.getGlobals();
        equal(BigInteger.ZERO, intValue(globals, "and_value"), "and preserves operand");
        equal(BigInteger.ONE, intValue(globals, "or_value"), "or preserves operand");
        equal(PyBool.TRUE, globals.get("chain_true"), "true comparison chain");
        equal(PyBool.FALSE, globals.get("chain_false"), "false comparison chain");
        equal(PyBool.FALSE, globals.get("chain_safe"),
                "comparison chain short circuits its final operand");
        equal(BigInteger.valueOf(2), intValue(globals, "choice"), "elif branch");
    }

    private static void testLoops() {
        VmResult result = execute(lines(
                "i = 0",
                "while_total = 0",
                "while i < 4:",
                "    i += 1",
                "    if i == 2:",
                "        continue",
                "    if i == 4:",
                "        break",
                "    while_total += i",
                "else:",
                "    while_total = 999",
                "for_total = 0",
                "for item in [1, 2, 3]:",
                "    if item == 2:",
                "        continue",
                "    for_total += item",
                "else:",
                "    exhausted = True",
                "break_total = 0",
                "for item in [1, 2, 3]:",
                "    break_total += item",
                "    if item == 2:",
                "        break",
                "else:",
                "    break_total = 999",
                "after_break = break_total",
                "natural_i = 0",
                "while natural_i < 2:",
                "    natural_i += 1",
                "else:",
                "    natural_else = 7",
                "nested_total = 0",
                "for outer in [1, 2]:",
                "    for inner in [10, 20]:",
                "        if inner == 20:",
                "            break",
                "        nested_total += outer"));
        check(result.isSuccess(), traceback(result));
        Map<String, PyValue> globals = result.getGlobals();
        equal(BigInteger.valueOf(4), intValue(globals, "while_total"),
                "while break skips else");
        equal(BigInteger.valueOf(4), intValue(globals, "for_total"),
                "for continue and natural exhaustion");
        equal(PyBool.TRUE, globals.get("exhausted"), "for else on exhaustion");
        equal(BigInteger.valueOf(3), intValue(globals, "after_break"),
                "for break skips else and executes TEMP_CLEAR unwind");
        equal(BigInteger.valueOf(7), intValue(globals, "natural_else"),
                "while else runs after natural termination");
        equal(BigInteger.valueOf(3), intValue(globals, "nested_total"),
                "nested loop contexts remain isolated");
    }

    private static void testUnreachableLoopCode() {
        VmResult result = execute(lines(
                "for x in [1]:",
                "    break",
                "    for y in [2]:",
                "        pass",
                "after = 7"));
        check(result.isSuccess(), traceback(result));
        equal(BigInteger.valueOf(7), intValue(result.getGlobals(), "after"),
                "reachable code after the outer loop");
        check(!result.getGlobals().containsKey("y"),
                "unreachable nested loop executed or leaked a target");
    }

    private static void testTargetsAndFString() {
        VmResult result = execute(lines(
                "(a, (b, c)) = (1, (2, 3))",
                "items = [10, 20, 30]",
                "items[1] += 5",
                "text = f\"{a}:{b + c}\"",
                "snapshot = (a, b, c)",
                "del a, b"));
        check(result.isSuccess(), traceback(result));
        Map<String, PyValue> globals = result.getGlobals();
        equal("[10, 25, 30]", globals.get("items").repr(), "augmented subscript");
        equal(new PyString("1:5"), globals.get("text"), "f-string");
        equal("(1, 2, 3)", globals.get("snapshot").repr(), "recursive unpack");
        check(!globals.containsKey("a") && !globals.containsKey("b"),
                "recursive/name delete did not clear bindings");
        equal(BigInteger.valueOf(3), intValue(globals, "c"), "unpack tail");
    }

    private static void testRuntimeFailure() {
        VmResult result = execute(lines(
                "before = 7",
                "boom = 1 / 0",
                "after = 9"));
        check(result.isFailure(), "division by zero unexpectedly succeeded");
        equal("ZeroDivisionError",
                result.getTraceback().getException().getExceptionTypeName(),
                "runtime exception type");
        check(result.getTraceback().toString().contains(SOURCE_FILE),
                "traceback lost source filename");
        equal(BigInteger.valueOf(7), intValue(result.getGlobals(), "before"),
                "globals before failure");
        check(!result.getGlobals().containsKey("after"),
                "instructions after failure were executed");
    }

    private static void testIsolationAndDisassembly() {
        GenerationResult generation = compile(lines("value = [1, 2]"));
        check(generation.isSuccess(), diagnostics(generation.getReporter()));
        BytecodeVM vm = new BytecodeVM();
        VmResult first = vm.execute(generation.requireModule());
        VmResult second = vm.execute(generation.requireModule());
        check(first.isSuccess() && second.isSuccess(), "repeat execution failed");
        check(first.getGlobals().get("value") != second.getGlobals().get("value"),
                "mutable module value leaked between VM runs");
        expectUnsupported(() -> first.getGlobals().put("x", PyNone.INSTANCE),
                "globals snapshot must be immutable");

        Disassembler disassembler = new Disassembler();
        String firstText = disassembler.disassemble(generation.requireModule());
        String secondText = disassembler.disassemble(generation.requireModule());
        equal(firstText, secondText, "repeat disassembly");
        check(firstText.contains("maxStack=") && firstText.contains("[synthetic]"),
                "disassembly omitted verified/source metadata");
        check(!firstText.contains("D:\\") && !firstText.contains("D:/"),
                "disassembly leaked an absolute workspace path");
    }

    private static void testInstructionLimit() {
        GenerationResult generation = compile(lines(
                "while True:",
                "    pass"));
        check(generation.isSuccess(), diagnostics(generation.getReporter()));
        BytecodeVM vm = new BytecodeVM(new ExecutionLimits(25, 4, 64));
        VmResult result = vm.execute(generation.requireModule());
        check(result.isFailure(), "infinite loop ignored instruction limit");
        equal("RuntimeError",
                result.getTraceback().getException().getExceptionTypeName(),
                "limit exception type");
        check(result.getTraceback().getException().getMessageText()
                        .contains("instruction count"),
                "limit exception message");
        check(!result.getTraceback().getEntries().isEmpty(),
                "instruction-limit traceback lost its source location");
    }

    private static void testCancellationAndRecovery() {
        GenerationResult infinite = compile(lines(
                "while True:",
                "    pass"));
        GenerationResult finite = compile(lines("after_cancel = 7"));
        check(infinite.isSuccess(), diagnostics(infinite.getReporter()));
        check(finite.isSuccess(), diagnostics(finite.getReporter()));

        AtomicBoolean cancellationEnabled = new AtomicBoolean(true);
        AtomicInteger polls = new AtomicInteger();
        ExecutionLimits limits = new ExecutionLimits(
                10_000, 4, 64,
                () -> cancellationEnabled.get()
                        && polls.incrementAndGet() >= 20);
        BytecodeVM vm = new BytecodeVM(limits);
        VmResult cancelled = vm.execute(infinite.requireModule());
        check(cancelled.isFailure(), "infinite loop ignored cancellation");
        equal("RuntimeError", cancelled.getTraceback().getException()
                .getExceptionTypeName(), "cancellation exception type");
        check(cancelled.getTraceback().getException().getMessageText()
                        .contains("cancelled"),
                "cancellation exception message");
        check(!cancelled.getTraceback().getEntries().isEmpty(),
                "cancellation traceback lost its source location");

        // Counters are per execution and a cancelled frame must not poison
        // the VM/module registry used by a subsequent independent run.
        cancellationEnabled.set(false);
        VmResult recovered = vm.execute(finite.requireModule());
        check(recovered.isSuccess(), traceback(recovered));
        equal(BigInteger.valueOf(7),
                intValue(recovered.getGlobals(), "after_cancel"),
                "post-cancellation execution");
    }

    private static void testCodegenFailures() {
        GenerationResult assertion = compile(lines("assert True"));
        check(assertion.isSuccess() && assertion.getModule() != null,
                "Phase-7 assert did not produce a verified module");
        check(new BytecodeVM().execute(assertion.requireModule()).isSuccess(),
                "true assertion did not execute successfully");

        GenerationResult executable = compile(lines(
                "value = len([1, 2])",
                "captured = print"));
        check(executable.isSuccess() && executable.getModule() != null,
                "executable runtime symbol failed generation");
        VmResult result = new BytecodeVM().execute(executable.requireModule());
        check(result.isSuccess(), "executable runtime symbol failed execution");
        equal(BigInteger.valueOf(2),
                ((PyInt) result.getGlobals().get("value")).getValue(),
                "builtin len result");
    }

    /**
     * Regression for the fall-through defect where an if/elif/else whose every
     * arm terminates (return/raise) is the final block of a function.  The
     * merge JUMP used to be emitted unconditionally and resolved one past the
     * code with no landing instruction, so the whole function failed
     * verification with "Invalid jump target".
     */
    private static void testTerminatingBranchReturns() {
        VmResult result = execute(lines(
                "def classify(x):",
                "    if x:",
                "        return 1",
                "    else:",
                "        return 2",
                "def grade(x):",
                "    if x == 1:",
                "        return 10",
                "    elif x == 2:",
                "        return 20",
                "    else:",
                "        return 30",
                "def nested(x, y):",
                "    if x:",
                "        if y:",
                "            return 100",
                "        else:",
                "            return 200",
                "    else:",
                "        return 300",
                "true_arm = classify(1)",
                "false_arm = classify(0)",
                "elif_arm = grade(2)",
                "else_arm = grade(9)",
                "nested_arm = nested(1, 0)"));
        check(result.isSuccess(), traceback(result));
        Map<String, PyValue> globals = result.getGlobals();
        equal(BigInteger.ONE, intValue(globals, "true_arm"),
                "if/else both-return: truthy arm");
        equal(BigInteger.valueOf(2), intValue(globals, "false_arm"),
                "if/else both-return: falsy arm");
        equal(BigInteger.valueOf(20), intValue(globals, "elif_arm"),
                "if/elif/else all-return: elif arm");
        equal(BigInteger.valueOf(30), intValue(globals, "else_arm"),
                "if/elif/else all-return: else arm");
        equal(BigInteger.valueOf(200), intValue(globals, "nested_arm"),
                "nested if/else all-return");
    }

    private static VmResult execute(String source) {
        GenerationResult generation = compile(source);
        check(generation.isSuccess(), diagnostics(generation.getReporter()));
        return new BytecodeVM().execute(generation.requireModule());
    }

    private static GenerationResult compile(String source) {
        ProgramNode program = build(source);
        DiagnosticReporter reporter = new DiagnosticReporter();
        AstStructuralValidator structural =
                new AstStructuralValidator(reporter, SOURCE_FILE);
        if (!structural.validate(program)) {
            return GenerationResult.failure(reporter);
        }
        SymbolTableBuilder symbols = new SymbolTableBuilder(reporter, SOURCE_FILE);
        program.accept(symbols);
        new ScopeRuleChecker(symbols.getSymbolTable(), reporter, SOURCE_FILE)
                .check(program);
        new SemanticAnalyzer(symbols.getSymbolTable(), SOURCE_FILE, reporter)
                .analyze(program);
        if (reporter.hasErrors()) {
            return GenerationResult.failure(reporter);
        }
        BindingAnalysisResult binding = new BindingResolver().resolve(program);
        return new FlaskBytecodeCompiler().compile(
                program, binding, SOURCE_FILE, "__main__", reporter);
    }

    private static ProgramNode build(String source) {
        List<String> syntaxIssues = new ArrayList<>();
        FlaskLexer lexer = new FlaskLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new CollectingErrorListener("lexer", syntaxIssues));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        FlaskParser parser = new FlaskParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new CollectingErrorListener("parser", syntaxIssues));
        FlaskParser.ProgramContext tree = parser.program();
        check(syntaxIssues.isEmpty(), "syntax errors: " + syntaxIssues + "\n" + source);
        ASTNode root = new ASTBuilder(SOURCE_FILE).visit(tree);
        check(root instanceof ProgramNode, "AST root is not ProgramNode");
        return (ProgramNode) root;
    }

    private static BigInteger intValue(Map<String, PyValue> globals, String name) {
        PyValue value = globals.get(name);
        check(value instanceof PyInt, name + " is not int: " + value);
        return ((PyInt) value).getValue();
    }

    private static boolean hasCategory(
            DiagnosticReporter reporter, DiagnosticCategory category) {
        for (Diagnostic diagnostic : reporter.diagnostics()) {
            if (diagnostic.category() == category) return true;
        }
        return false;
    }

    private static String traceback(VmResult result) {
        return result.isFailure() ? result.getTraceback().toString() : "";
    }

    private static String diagnostics(DiagnosticReporter reporter) {
        StringBuilder result = new StringBuilder();
        for (Diagnostic diagnostic : reporter.diagnostics()) {
            result.append(diagnostic).append('\n');
        }
        return result.toString();
    }

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
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

    private static void expectUnsupported(Runnable action, String message) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

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
            issues.add(phase + " " + line + ":" + charPositionInLine + " " + message);
        }
    }
}
