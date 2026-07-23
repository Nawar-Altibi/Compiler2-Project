import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.SymbolTable.SymbolTableBuilder;
import compilers.flask.antlr_gen.FlaskLexer;
import compilers.flask.antlr_gen.FlaskParser;
import compilers.flask.ast.builder.ASTBuilder;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.codegen.FlaskBytecodeCompiler;
import compilers.flask.codegen.GenerationResult;
import compilers.flask.codegen.analysis.BindingAnalysisResult;
import compilers.flask.codegen.analysis.BindingResolver;
import compilers.flask.codegen.bytecode.BytecodeModule;
import compilers.flask.codegen.bytecode.CodeKind;
import compilers.flask.codegen.bytecode.CodeObject;
import compilers.flask.codegen.bytecode.CodeObjectBuilder;
import compilers.flask.codegen.bytecode.FunctionSignature;
import compilers.flask.codegen.bytecode.OpCode;
import compilers.flask.codegen.bytecode.Operand;
import compilers.flask.codegen.bytecode.VerifiedBytecodeModule;
import compilers.flask.codegen.verify.BytecodeAssembler;
import compilers.flask.codegen.verify.ControlFlowVerifier;
import compilers.flask.semantic.SemanticAnalyzer;
import compilers.flask.semantic.validation.AstStructuralValidator;
import compilers.flask.semantic.validation.ScopeRuleChecker;
import compilers.flask.vm.BytecodeVM;
import compilers.flask.vm.CallBinder;
import compilers.flask.vm.VmResult;
import compilers.flask.vm.VmRuntimeException;
import compilers.flask.vm.VmTraceback;
import compilers.flask.vm.values.PyBool;
import compilers.flask.vm.values.PyFunction;
import compilers.flask.vm.values.PyInt;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Independent Phase-4 gate for calls, functions, defaults, decorators,
 * recursion, closures, and nested tracebacks.
 *
 * <p>The handcrafted test reaches the VM only through an assembled and
 * verified module. The source tests exercise the complete front-end through
 * the same verified-only VM entry point.</p>
 */
public final class FlaskFunctionVmHarness {
    private static final String SOURCE_FILE = "phase4-functions.py";
    private static final String MANUAL_SOURCE = "phase4-manual.py";
    private static final SourceSpan MANUAL_SPAN =
            new SourceSpan(MANUAL_SOURCE, 1, 0, 1, 1);
    private static int passed;
    private static int failed;

    private FlaskFunctionVmHarness() {
    }

    public static void main(String[] args) {
        run("CallBinder binds positional, keyword, and default values",
                FlaskFunctionVmHarness::testCallBinder);
        run("handcrafted MAKE_FUNCTION and CALL execute only after verification",
                FlaskFunctionVmHarness::testManualMakeFunctionAndCall);
        run("runtime call binding rejects missing, excess, duplicate, and unknown args",
                FlaskFunctionVmHarness::testRuntimeCallErrors);
        run("callable and arguments preserve source evaluation order; defaults run once",
                FlaskFunctionVmHarness::testEvaluationOrderAndDefaults);
        run("global recursion executes with function frames",
                FlaskFunctionVmHarness::testRecursion);
        run("nested mutual recursion shares closure cells",
                FlaskFunctionVmHarness::testMutualRecursion);
        run("captured parameters and sibling closures observe shared cells",
                FlaskFunctionVmHarness::testSharedClosures);
        run("decorators and annotations preserve definition-time ordering",
                FlaskFunctionVmHarness::testDecoratorsAndAnnotations);
        run("runtime failures contain module and nested function frames",
                FlaskFunctionVmHarness::testNestedTraceback);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " Phase-4 function test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " Phase-4 function/VM tests passed.");
    }

    private static void testCallBinder() {
        VmResult definition = execute(lines(
                "def target(a, b=20, c=30):",
                "    return a"));
        check(definition.isSuccess(), traceback(definition));
        PyFunction function = function(definition.getGlobals(), "target");

        LinkedHashMap<String, PyValue> keywords = new LinkedHashMap<>();
        keywords.put("c", PyInt.valueOf(3));
        Map<String, PyValue> bound = CallBinder.bind(
                function,
                Collections.<PyValue>singletonList(PyInt.ONE),
                keywords);
        equal(PyInt.ONE, bound.get("a"), "positional a");
        equal(PyInt.valueOf(20), bound.get("b"), "default b");
        equal(PyInt.valueOf(3), bound.get("c"), "keyword c");

        expectBinderError(function,
                Arrays.<PyValue>asList(
                        PyInt.ONE, PyInt.ONE, PyInt.ONE, PyInt.ONE),
                Collections.<String, PyValue>emptyMap(),
                "positional");
        expectBinderError(function,
                Collections.<PyValue>emptyList(),
                Collections.<String, PyValue>emptyMap(),
                "missing required");

        LinkedHashMap<String, PyValue> unexpected = new LinkedHashMap<>();
        unexpected.put("unknown", PyInt.ONE);
        expectBinderError(function,
                Collections.<PyValue>singletonList(PyInt.ONE),
                unexpected,
                "unexpected keyword");

        LinkedHashMap<String, PyValue> duplicate = new LinkedHashMap<>();
        duplicate.put("a", PyInt.valueOf(2));
        expectBinderError(function,
                Collections.<PyValue>singletonList(PyInt.ONE),
                duplicate,
                "multiple values");
    }

    private static void testManualMakeFunctionAndCall() {
        CodeObjectBuilder child = new CodeObjectBuilder(
                101,
                CodeKind.FUNCTION,
                "add",
                "manual.add",
                MANUAL_SOURCE,
                new FunctionSignature(
                        Arrays.asList("a", "b"),
                        Collections.singletonList("b")),
                Arrays.asList("a", "b"),
                Collections.<String>emptyList(),
                Collections.<String>emptyList());
        child.emit(OpCode.LOAD_FAST,
                new Operand.LocalSlotOperand(0), MANUAL_SPAN, false);
        child.emit(OpCode.LOAD_FAST,
                new Operand.LocalSlotOperand(1), MANUAL_SPAN, false);
        child.emit(OpCode.BINARY_OP,
                new Operand.BinaryOperatorOperand(Operand.BinaryOperator.ADD),
                MANUAL_SPAN, false);
        child.emit(OpCode.RETURN_VALUE, MANUAL_SPAN, false);
        child.seal();

        CodeObjectBuilder root = moduleBuilder(100, "manual");
        int defaultFive = root.addConstant(PyInt.valueOf(5));
        int codeIndex = root.addCodeObjectBuilder(child);
        int seven = root.addConstant(PyInt.valueOf(7));
        int eight = root.addConstant(PyInt.valueOf(8));
        int none = root.addConstant(PyNone.INSTANCE);
        int functionName = root.addName("add");
        int defaultResult = root.addName("default_result");
        int keywordResult = root.addName("keyword_result");

        loadConst(root, defaultFive);
        loadConst(root, codeIndex);
        root.emit(OpCode.MAKE_FUNCTION,
                new Operand.MakeFunctionSpec(
                        Collections.singletonList("b"),
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList()),
                MANUAL_SPAN, false);
        root.emit(OpCode.STORE_NAME,
                new Operand.NameOperand(functionName), MANUAL_SPAN, false);

        loadName(root, functionName);
        loadConst(root, seven);
        root.emit(OpCode.CALL,
                new Operand.CallSpec(1, Collections.<String>emptyList()),
                MANUAL_SPAN, false);
        root.emit(OpCode.STORE_NAME,
                new Operand.NameOperand(defaultResult), MANUAL_SPAN, false);

        loadName(root, functionName);
        loadConst(root, seven);
        loadConst(root, eight);
        root.emit(OpCode.CALL,
                new Operand.CallSpec(1, Collections.singletonList("b")),
                MANUAL_SPAN, false);
        root.emit(OpCode.STORE_NAME,
                new Operand.NameOperand(keywordResult), MANUAL_SPAN, false);
        loadConst(root, none);
        root.emit(OpCode.RETURN_VALUE, MANUAL_SPAN, true);
        root.seal();

        VerifiedBytecodeModule verified = verify(root, MANUAL_SOURCE, "manual");
        check(verified.hasValidVerificationStamp(), "verification stamp is invalid");
        CodeObject verifiedChild = verified.getModule().getRootCode()
                .getNestedCodeObjects().get(0);
        equal(CodeKind.FUNCTION, verifiedChild.getKind(), "nested kind");
        check(verifiedChild.isVerified(), "nested function code remained unverified");

        VmResult result = new BytecodeVM().execute(verified);
        check(result.isSuccess(), traceback(result));
        equal(BigInteger.valueOf(12),
                integer(result.getGlobals(), "default_result"),
                "default call result");
        equal(BigInteger.valueOf(15),
                integer(result.getGlobals(), "keyword_result"),
                "keyword call result");
    }

    private static void testRuntimeCallErrors() {
        assertRuntimeCallError(lines(
                "def target(a, b=2):",
                "    return a + b",
                "alias = target",
                "result = alias()"), "missing required");
        assertRuntimeCallError(lines(
                "def target(a, b=2):",
                "    return a + b",
                "alias = target",
                "result = alias(1, 2, 3)"), "positional");
        assertRuntimeCallError(lines(
                "def target(a, b=2):",
                "    return a + b",
                "alias = target",
                "result = alias(1, a=3)"), "multiple values");
        assertRuntimeCallError(lines(
                "def target(a, b=2):",
                "    return a + b",
                "alias = target",
                "result = alias(1, unknown=3)"), "unexpected keyword");
    }

    private static void testEvaluationOrderAndDefaults() {
        VmResult result = execute(lines(
                "log = \"\"",
                "def mark(value, tag):",
                "    global log",
                "    log += tag",
                "    return value",
                "def target(a, b=0):",
                "    return a + b",
                "ordered = mark(target, \"C\")(mark(2, \"A\"), b=mark(3, \"B\"))",
                "def default_value():",
                "    global log",
                "    log += \"D\"",
                "    return [0]",
                "def use(value=default_value()):",
                "    value[0] += 1",
                "    return value[0]",
                "first = use()",
                "second = use()"));
        check(result.isSuccess(), traceback(result));
        equal(new PyString("CABD"), result.getGlobals().get("log"),
                "definition/call evaluation order");
        equal(BigInteger.valueOf(5), integer(result.getGlobals(), "ordered"),
                "ordered call result");
        equal(BigInteger.ONE, integer(result.getGlobals(), "first"),
                "first mutable-default call");
        equal(BigInteger.valueOf(2), integer(result.getGlobals(), "second"),
                "second mutable-default call");
    }

    private static void testRecursion() {
        VmResult result = execute(lines(
                "def factorial(n):",
                "    if n <= 1:",
                "        return 1",
                "    return n * factorial(n - 1)",
                "answer = factorial(6)"));
        check(result.isSuccess(), traceback(result));
        equal(BigInteger.valueOf(720), integer(result.getGlobals(), "answer"),
                "factorial result");
    }

    private static void testMutualRecursion() {
        VmResult result = execute(lines(
                "def parity(value):",
                "    def even(number):",
                "        if number == 0:",
                "            return True",
                "        return odd(number - 1)",
                "    def odd(number):",
                "        if number == 0:",
                "            return False",
                "        return even(number - 1)",
                "    return even(value)",
                "six = parity(6)",
                "five = parity(5)"));
        check(result.isSuccess(), traceback(result));
        equal(PyBool.TRUE, result.getGlobals().get("six"), "even parity");
        equal(PyBool.FALSE, result.getGlobals().get("five"), "odd parity");
    }

    private static void testSharedClosures() {
        VmResult result = execute(lines(
                "def capture(value):",
                "    def read():",
                "        return value",
                "    value += 1",
                "    return read",
                "captured = capture(4)",
                "captured_value = captured()",
                "def pair():",
                "    state = [10]",
                "    def read():",
                "        return state[0]",
                "    def bump():",
                "        state[0] += 1",
                "        return state[0]",
                "    return (read, bump)",
                "(reader, bumper) = pair()",
                "before = reader()",
                "changed = bumper()",
                "after = reader()"));
        check(result.isSuccess(), traceback(result));
        equal(BigInteger.valueOf(5), integer(result.getGlobals(), "captured_value"),
                "captured parameter update");
        equal(BigInteger.TEN, integer(result.getGlobals(), "before"),
                "shared cell before mutation");
        equal(BigInteger.valueOf(11), integer(result.getGlobals(), "changed"),
                "sibling closure mutation");
        equal(BigInteger.valueOf(11), integer(result.getGlobals(), "after"),
                "shared cell after mutation");
    }

    private static void testDecoratorsAndAnnotations() {
        VmResult result = execute(lines(
                "events = \"\"",
                "def make_decorator(tag):",
                "    global events",
                "    events += tag",
                "    def apply(function):",
                "        global events",
                "        events += tag + \"a\"",
                "        return function",
                "    return apply",
                "def note(value, tag):",
                "    global events",
                "    events += tag",
                "    return value",
                "@make_decorator(\"1\")",
                "@make_decorator(\"2\")",
                "def decorated(value: note(\"PX\", \"A\") = note(4, \"D\")) -> note(\"RX\", \"R\"):",
                "    return value",
                "decorated_result = decorated()"));
        check(result.isSuccess(), traceback(result));
        equal(new PyString("12DAR2a1a"), result.getGlobals().get("events"),
                "decorator/default/annotation order");
        equal(BigInteger.valueOf(4),
                integer(result.getGlobals(), "decorated_result"),
                "decorated result");

        PyFunction function = function(result.getGlobals(), "decorated");
        equal(PyInt.valueOf(4), function.getDefaultValues().get("value"),
                "function default metadata");
        equal(new PyString("PX"), function.getAnnotations().get("value"),
                "parameter annotation metadata");
        equal(new PyString("RX"), function.getAnnotations().get("return"),
                "return annotation metadata");
    }

    private static void testNestedTraceback() {
        VmResult result = execute(lines(
                "def inner():",
                "    return 1 / 0",
                "def outer():",
                "    return inner()",
                "result = outer()"));
        check(result.isFailure(), "nested runtime failure unexpectedly succeeded");
        equal("ZeroDivisionError",
                result.getTraceback().getException().getExceptionTypeName(),
                "nested exception type");
        List<VmTraceback.Entry> entries = result.getTraceback().getEntries();
        equal(3, entries.size(), "traceback frame count");
        equal("__main__", entries.get(0).getCodeName(), "module frame");
        equal("__main__.outer", entries.get(1).getCodeName(), "outer frame");
        equal("__main__.inner", entries.get(2).getCodeName(), "inner frame");
        equal(5, entries.get(0).getSourceSpan().getStartLine(), "module call line");
        equal(4, entries.get(1).getSourceSpan().getStartLine(), "outer call line");
        equal(2, entries.get(2).getSourceSpan().getStartLine(), "inner error line");
    }

    private static void expectBinderError(
            PyFunction function,
            List<PyValue> positional,
            Map<String, PyValue> keywords,
            String messageFragment) {
        try {
            CallBinder.bind(function, positional, keywords);
        } catch (VmRuntimeException error) {
            equal("TypeError", error.getExceptionValue().getExceptionTypeName(),
                    "binder error type");
            check(error.getExceptionValue().getMessageText().contains(messageFragment),
                    "binder error omitted '" + messageFragment + "': "
                            + error.getExceptionValue().getMessageText());
            return;
        }
        throw new AssertionError("CallBinder accepted invalid arguments");
    }

    private static void assertRuntimeCallError(
            String source, String messageFragment) {
        VmResult result = execute(source);
        check(result.isFailure(), "invalid runtime call unexpectedly succeeded");
        equal("TypeError", result.getTraceback().getException().getExceptionTypeName(),
                "runtime call error type");
        check(result.getTraceback().getException().getMessageText()
                        .contains(messageFragment),
                "runtime call error omitted '" + messageFragment + "': "
                        + result.getTraceback().getException().getMessageText());
    }

    private static PyFunction function(Map<String, PyValue> globals, String name) {
        PyValue value = globals.get(name);
        check(value instanceof PyFunction,
                name + " is not PyFunction: " + String.valueOf(value));
        return (PyFunction) value;
    }

    private static BigInteger integer(Map<String, PyValue> globals, String name) {
        PyValue value = globals.get(name);
        check(value instanceof PyInt, name + " is not int: " + String.valueOf(value));
        return ((PyInt) value).getValue();
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

    private static VerifiedBytecodeModule verify(
            CodeObjectBuilder root, String source, String moduleName) {
        DiagnosticReporter reporter = new DiagnosticReporter();
        BytecodeModule assembled = new BytecodeAssembler().assembleModule(
                source, moduleName, root, reporter);
        check(assembled != null && !reporter.hasErrors(), diagnostics(reporter));
        VerifiedBytecodeModule verified =
                new ControlFlowVerifier().verify(assembled, reporter);
        check(verified != null && !reporter.hasErrors(), diagnostics(reporter));
        return verified;
    }

    private static CodeObjectBuilder moduleBuilder(int codeId, String name) {
        return new CodeObjectBuilder(
                codeId,
                CodeKind.MODULE,
                "<module>",
                name,
                MANUAL_SOURCE,
                FunctionSignature.EMPTY,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList());
    }

    private static void loadConst(CodeObjectBuilder builder, int index) {
        builder.emit(OpCode.LOAD_CONST,
                new Operand.ConstOperand(index), MANUAL_SPAN, false);
    }

    private static void loadName(CodeObjectBuilder builder, int index) {
        builder.emit(OpCode.LOAD_NAME,
                new Operand.NameOperand(index), MANUAL_SPAN, false);
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

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
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
