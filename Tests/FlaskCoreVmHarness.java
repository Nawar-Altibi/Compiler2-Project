import compilers.flask.ast.nodes.SourceSpan;
import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.codegen.bytecode.BytecodeFormat;
import compilers.flask.codegen.bytecode.BytecodeModule;
import compilers.flask.codegen.bytecode.CleanupRegion;
import compilers.flask.codegen.bytecode.CodeKind;
import compilers.flask.codegen.bytecode.CodeObject;
import compilers.flask.codegen.bytecode.CodeObjectBuilder;
import compilers.flask.codegen.bytecode.ConstantPool;
import compilers.flask.codegen.bytecode.FunctionSignature;
import compilers.flask.codegen.bytecode.Instruction;
import compilers.flask.codegen.bytecode.InstructionLocation;
import compilers.flask.codegen.bytecode.Label;
import compilers.flask.codegen.bytecode.NamePool;
import compilers.flask.codegen.bytecode.OpCode;
import compilers.flask.codegen.bytecode.Operand;
import compilers.flask.codegen.bytecode.SourceMap;
import compilers.flask.codegen.bytecode.VerifiedBytecodeModule;
import compilers.flask.codegen.verify.BytecodeAssembler;
import compilers.flask.codegen.verify.ControlFlowVerifier;
import compilers.flask.vm.BytecodeVM;
import compilers.flask.vm.Cell;
import compilers.flask.vm.CellRef;
import compilers.flask.vm.ExecutionLimits;
import compilers.flask.vm.Frame;
import compilers.flask.vm.Namespace;
import compilers.flask.vm.OperandStack;
import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.VmResult;
import compilers.flask.vm.VmRuntimeException;
import compilers.flask.vm.VmTraceback;
import compilers.flask.vm.values.PyAttributeProvider;
import compilers.flask.vm.values.PyBaseException;
import compilers.flask.vm.values.PyBool;
import compilers.flask.vm.values.PyDict;
import compilers.flask.vm.values.PyFloat;
import compilers.flask.vm.values.PyInt;
import compilers.flask.vm.values.PyIterator;
import compilers.flask.vm.values.PyList;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PySet;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyTuple;
import compilers.flask.vm.values.PyValue;

import java.math.BigInteger;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Dependency-free executable tests for primitive values and the core VM. */
public final class FlaskCoreVmHarness {
    private static final String BYTECODE_SOURCE = "core-vm-bytecode.py";
    private static final SourceSpan BYTECODE_SPAN =
            new SourceSpan(BYTECODE_SOURCE, 1, 0, 1, 1);
    private static int passed;
    private static int failed;

    private FlaskCoreVmHarness() {
    }

    public static void main(String[] args) {
        run("scalar values and constant identity", FlaskCoreVmHarness::testScalars);
        run("truthiness", FlaskCoreVmHarness::testTruthiness);
        run("arbitrary precision and arithmetic", FlaskCoreVmHarness::testArithmetic);
        run("Python floor division and modulo", FlaskCoreVmHarness::testFloorAndModulo);
        run("collections and Python keys", FlaskCoreVmHarness::testCollections);
        run("comparisons and membership", FlaskCoreVmHarness::testComparisons);
        run("subscripts and attributes", FlaskCoreVmHarness::testAccessOperations);
        run("iteration and unpacking", FlaskCoreVmHarness::testIteration);
        run("namespace, cell, and operand stack", FlaskCoreVmHarness::testCoreState);
        run("frame lookup and slot state", FlaskCoreVmHarness::testFrameState);
        run("execution limits", FlaskCoreVmHarness::testExecutionLimits);
        run("result and basic traceback", FlaskCoreVmHarness::testResultAndTraceback);
        run("verified-only VM gate and opcode inventory",
                FlaskCoreVmHarness::testVerifiedGateAndDispatchInventory);
        run("verified straight-line names and arithmetic",
                FlaskCoreVmHarness::testVerifiedStraightLine);
        run("verified collections and subscripts",
                FlaskCoreVmHarness::testVerifiedCollections);
        run("verified branches and retaining short circuit",
                FlaskCoreVmHarness::testVerifiedBranches);
        run("verified while-loop control flow",
                FlaskCoreVmHarness::testVerifiedWhileLoop);
        run("verified iterator exhaustion and TEMP_CLEAR",
                FlaskCoreVmHarness::testVerifiedForExhaustion);
        run("verified for-break unwind skips else and clears temp",
                FlaskCoreVmHarness::testVerifiedForBreakUnwind);
        run("runtime failure traceback and execution isolation",
                FlaskCoreVmHarness::testVmRuntimeFailureAndIsolation);
        run("instruction limit and deferred-opcode rejection",
                FlaskCoreVmHarness::testVmLimitsAndDeferredOpcode);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(failed + " core VM test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " Flask core VM tests passed.");
    }

    private static void testScalars() {
        check(PyNone.INSTANCE.isDeeplyImmutable(), "None must be constant-safe");
        check(PyBool.TRUE.isDeeplyImmutable(), "bool must be constant-safe");
        check(PyInt.ONE.isDeeplyImmutable(), "int must be constant-safe");
        check(new PyFloat(1.5).isDeeplyImmutable(), "float must be constant-safe");
        check(new PyString("text").isDeeplyImmutable(), "str must be constant-safe");

        // Constant-pool Java equality remains type-strict.
        check(!PyBool.TRUE.equals(PyInt.ONE), "True and 1 merged as typed constants");
        // Runtime Python equality and hashing deliberately agree.
        check(RuntimeOps.equalsValue(PyBool.TRUE, PyInt.ONE), "True must equal 1 at runtime");
        equal(RuntimeOps.hash(PyBool.TRUE), RuntimeOps.hash(PyInt.ONE),
                "equal numeric key hash");
        check(RuntimeOps.equalsValue(new PyFloat(1.0), PyInt.ONE),
                "1.0 must equal 1 at runtime");
        equal("'a\\n\\''", new PyString("a\n'").repr(), "string repr");
        equal("None", PyNone.INSTANCE.repr(), "None repr");
    }

    private static void testTruthiness() {
        check(!RuntimeOps.isTruthy(PyNone.INSTANCE), "None truthiness");
        check(!RuntimeOps.isTruthy(PyBool.FALSE), "False truthiness");
        check(!RuntimeOps.isTruthy(PyInt.ZERO), "zero truthiness");
        check(!RuntimeOps.isTruthy(new PyFloat(-0.0)), "negative zero truthiness");
        check(!RuntimeOps.isTruthy(PyString.EMPTY), "empty string truthiness");
        check(!RuntimeOps.isTruthy(new PyList()), "empty list truthiness");
        check(!RuntimeOps.isTruthy(PyTuple.EMPTY), "empty tuple truthiness");
        check(!RuntimeOps.isTruthy(new PySet()), "empty set truthiness");
        check(!RuntimeOps.isTruthy(new PyDict()), "empty dict truthiness");
        check(RuntimeOps.isTruthy(PyInt.ONE), "one truthiness");
        check(RuntimeOps.isTruthy(new PyFloat(Double.NaN)), "NaN truthiness");
        equal(PyBool.TRUE, RuntimeOps.unary("not", PyInt.ZERO), "not zero");
    }

    private static void testArithmetic() {
        BigInteger huge = BigInteger.TEN.pow(80);
        PyValue sum = RuntimeOps.binary("ADD", new PyInt(huge), PyInt.ONE);
        equal(huge.add(BigInteger.ONE), ((PyInt) sum).getValue(), "huge integer addition");
        equal(PyInt.valueOf(6), RuntimeOps.binary("MULTIPLY", PyInt.valueOf(2),
                PyInt.valueOf(3)), "integer multiply");
        closeTo(2.5, RuntimeOps.binary("TRUE_DIVIDE", PyInt.valueOf(5),
                PyInt.valueOf(2)), "true divide");
        equal(PyInt.valueOf(1024), RuntimeOps.binary("POWER", PyInt.valueOf(2),
                PyInt.valueOf(10)), "integer power");
        closeTo(0.5, RuntimeOps.binary("POWER", PyInt.valueOf(2),
                PyInt.valueOf(-1)), "negative integer power");

        equal(new PyString("ababab"), RuntimeOps.binary("MULTIPLY",
                new PyString("ab"), PyInt.valueOf(3)), "string repeat");
        PyList left = new PyList(List.of(PyInt.ONE));
        PyValue inPlace = RuntimeOps.binary("INPLACE_ADD", left,
                new PyList(List.of(PyInt.valueOf(2))));
        check(inPlace == left, "in-place list add must retain identity");
        equal("[1, 2]", left.repr(), "in-place list result");

        expectError("TypeError", () -> RuntimeOps.binary("ADD", PyInt.ONE,
                new PyString("x")));
        expectError("ZeroDivisionError", () -> RuntimeOps.binary("TRUE_DIVIDE",
                PyInt.ONE, PyInt.ZERO));
    }

    private static void testFloorAndModulo() {
        assertInt(-3, RuntimeOps.binary("FLOOR_DIVIDE", PyInt.valueOf(-7),
                PyInt.valueOf(3)), "-7 // 3");
        assertInt(2, RuntimeOps.binary("MODULO", PyInt.valueOf(-7),
                PyInt.valueOf(3)), "-7 % 3");
        assertInt(-3, RuntimeOps.binary("FLOOR_DIVIDE", PyInt.valueOf(7),
                PyInt.valueOf(-3)), "7 // -3");
        assertInt(-2, RuntimeOps.binary("MODULO", PyInt.valueOf(7),
                PyInt.valueOf(-3)), "7 % -3");
        closeTo(-3.0, RuntimeOps.binary("FLOOR_DIVIDE", new PyFloat(7.0),
                new PyFloat(-3.0)), "float floor division");
        closeTo(-2.0, RuntimeOps.binary("MODULO", new PyFloat(7.0),
                new PyFloat(-3.0)), "float modulo");
        expectError("ZeroDivisionError", () -> RuntimeOps.binary("MODULO",
                PyInt.ONE, PyInt.ZERO));
    }

    private static void testCollections() {
        PyDict dict = new PyDict();
        dict.put(PyBool.TRUE, new PyString("bool"));
        dict.put(PyInt.ONE, new PyString("int"));
        equal(1, dict.size(), "equal numeric dictionary key count");
        equal(new PyString("int"), dict.find(new PyFloat(1.0)).orElseThrow(),
                "equal numeric dictionary lookup");

        PySet set = new PySet(List.of(PyBool.TRUE, PyInt.ONE, new PyFloat(1.0)));
        equal(1, set.size(), "equal numeric set element count");
        PyFloat nan = new PyFloat(Double.NaN);
        dict.put(nan, new PyString("same-object NaN"));
        equal(new PyString("same-object NaN"), dict.find(nan).orElseThrow(),
                "dictionary must find identical NaN key");
        check(!dict.find(new PyFloat(Double.NaN)).isPresent(),
                "distinct NaN keys must not compare equal");
        expectError("TypeError", () -> set.add(new PyList()));
        expectError("TypeError", () -> dict.put(new PyList(), PyNone.INSTANCE));

        PyTuple immutable = new PyTuple(List.of(PyInt.ONE, new PyString("x")));
        check(immutable.isDeeplyImmutable(), "immutable tuple constant safety");
        PyTuple shallowOnly = new PyTuple(List.of(new PyList()));
        check(!shallowOnly.isDeeplyImmutable(), "tuple with list must not enter constant pool");

        PyList recursive = new PyList();
        recursive.append(recursive);
        equal("[[...]]", recursive.repr(), "recursive list repr");
        expectUnsupported(() -> immutable.getElements().add(PyInt.ZERO),
                "tuple elements must be immutable");
    }

    private static void testComparisons() {
        equal(PyBool.TRUE, RuntimeOps.compare("EQ", PyInt.ONE, new PyFloat(1.0)),
                "numeric equality");
        equal(PyBool.TRUE, RuntimeOps.compare("LT", PyInt.ONE, new PyFloat(1.5)),
                "numeric order");
        equal(PyBool.TRUE, RuntimeOps.compare("LT", new PyString("a"),
                new PyString("b")), "string order");
        equal(PyBool.TRUE, RuntimeOps.compare("IN", PyInt.ONE,
                new PyList(List.of(PyBool.TRUE))), "list membership");
        equal(PyBool.TRUE, RuntimeOps.compare("IN", new PyString("bc"),
                new PyString("abcd")), "string membership");

        PyDict dict = new PyDict();
        dict.put(new PyString("key"), PyInt.ONE);
        equal(PyBool.TRUE, RuntimeOps.compare("IN", new PyString("key"), dict),
                "dict key membership");
        equal(PyBool.FALSE, RuntimeOps.compare("LT", new PyFloat(Double.NaN),
                PyFloatZero.VALUE), "NaN less-than");
        equal(PyBool.FALSE, RuntimeOps.compare("GE", new PyFloat(Double.NaN),
                PyFloatZero.VALUE), "NaN greater-equal");
        expectError("TypeError", () -> RuntimeOps.compare("LT", PyInt.ONE,
                new PyString("1")));
    }

    private static void testAccessOperations() {
        PyList list = new PyList(List.of(PyInt.ONE, PyInt.valueOf(2), PyInt.valueOf(3)));
        equal(PyInt.valueOf(3), RuntimeOps.loadSubscript(list, PyInt.valueOf(-1)),
                "negative list index");
        RuntimeOps.storeSubscript(list, PyInt.ONE, PyInt.valueOf(20));
        equal("[1, 20, 3]", list.repr(), "list store");
        RuntimeOps.deleteSubscript(list, PyInt.ZERO);
        equal("[20, 3]", list.repr(), "list delete");

        PyString unicode = new PyString("A😀B");
        equal(new PyString("😀"), RuntimeOps.loadSubscript(unicode, PyInt.ONE),
                "Unicode code-point indexing");

        PyDict dict = new PyDict();
        RuntimeOps.storeSubscript(dict, new PyString("a"), PyInt.ONE);
        equal(PyInt.ONE, RuntimeOps.loadSubscript(dict, new PyString("a")),
                "dict subscript");
        RuntimeOps.deleteSubscript(dict, new PyString("a"));
        expectError("KeyError", () -> RuntimeOps.loadSubscript(dict,
                new PyString("a")));

        TestAttributeValue object = new TestAttributeValue();
        RuntimeOps.storeAttribute(object, "answer", PyInt.valueOf(42));
        equal(PyInt.valueOf(42), RuntimeOps.loadAttribute(object, "answer"),
                "attribute load/store");
        RuntimeOps.deleteAttribute(object, "answer");
        expectError("AttributeError", () -> RuntimeOps.loadAttribute(object, "answer"));
    }

    private static void testIteration() {
        PyIterator iterator = RuntimeOps.iter(new PyString("A😀"));
        equal(new PyString("A"), RuntimeOps.next(iterator), "first string item");
        equal(new PyString("😀"), RuntimeOps.next(iterator), "second string item");
        expectError("StopIteration", () -> RuntimeOps.next(iterator));

        List<PyValue> unpacked = RuntimeOps.unpack(
                new PyTuple(List.of(PyInt.ONE, PyInt.valueOf(2))), 2);
        equal(List.of(PyInt.ONE, PyInt.valueOf(2)), unpacked, "unpack order");
        expectError("ValueError", () -> RuntimeOps.unpack(
                new PyList(List.of(PyInt.ONE)), 2));
        expectError("ValueError", () -> RuntimeOps.unpack(
                new PyList(List.of(PyInt.ONE, PyInt.valueOf(2))), 1));

        PyList mutable = new PyList(List.of(PyInt.ONE));
        PyIterator liveIterator = RuntimeOps.iter(mutable);
        mutable.append(PyInt.valueOf(2));
        equal(PyInt.ONE, RuntimeOps.next(liveIterator), "live list iterator first item");
        equal(PyInt.valueOf(2), RuntimeOps.next(liveIterator),
                "list iterator must observe append");
    }

    private static void testCoreState() {
        Namespace namespace = new Namespace();
        namespace.put("value", PyInt.ONE);
        equal(PyInt.ONE, namespace.find("value").orElseThrow(), "namespace load");
        check(namespace.delete("value"), "namespace delete");
        check(!namespace.find("value").isPresent(), "namespace deleted value remains");

        Cell cell = new Cell();
        check(!cell.isBound(), "new cell must be unbound");
        cell.set(PyInt.ONE);
        equal(PyInt.ONE, cell.find().orElseThrow(), "cell value");
        CellRef ref = new CellRef(cell);
        check(ref.getCell() == cell, "cell reference must retain shared cell identity");
        cell.clear();
        check(!ref.getCell().isBound(), "clearing shared cell not visible through ref");

        OperandStack stack = new OperandStack(5);
        stack.push(PyInt.ONE);
        stack.push(PyInt.valueOf(2));
        stack.copy(2);
        equal(List.of(PyInt.ONE, PyInt.valueOf(2), PyInt.ONE), stack.snapshot(),
                "COPY one-based depth");
        stack.swap(2);
        equal(List.of(PyInt.ONE, PyInt.ONE, PyInt.valueOf(2)), stack.snapshot(),
                "SWAP one-based depth");
        equal(List.of(PyInt.ONE, PyInt.valueOf(2)),
                stack.popPyValuesInSourceOrder(2), "source-order pops");
        expectUnsupported(() -> stack.snapshot().clear(), "stack snapshot must be immutable");
    }

    private static void testExecutionLimits() {
        ExecutionLimits.Meter instructions =
                new ExecutionLimits(2, 2, 3).newMeter();
        instructions.beforeInstruction();
        instructions.beforeInstruction();
        expectError("RuntimeError", instructions::beforeInstruction);

        ExecutionLimits.Meter calls = new ExecutionLimits(3, 1, 3).newMeter();
        calls.enterCall();
        expectError("RuntimeError", calls::enterCall);
        calls.exitCall();

        ExecutionLimits cancelled = new ExecutionLimits(
                10, 1, 3, () -> true);
        expectError("RuntimeError", cancelled.newMeter()::beforeInstruction);

        OperandStack bounded = new OperandStack(1);
        bounded.push(PyInt.ONE);
        expectError("RuntimeError", () -> bounded.push(PyInt.ZERO));
    }

    private static void testFrameState() {
        CodeObject code = verifiedCode(
                CodeKind.FUNCTION,
                List.of("fast"),
                List.of("cell"),
                List.of("free"),
                1);
        Namespace locals = new Namespace();
        Namespace globals = new Namespace();
        Namespace builtins = new Namespace();
        locals.put("chosen", new PyString("local"));
        globals.put("chosen", new PyString("global"));
        globals.put("global_only", PyInt.ONE);
        builtins.put("chosen", new PyString("builtin"));
        builtins.put("builtin_only", PyInt.valueOf(2));
        Cell free = new Cell(PyInt.valueOf(30));
        Frame frame = new Frame(code, locals, globals, builtins,
                List.of(free), 8);

        equal(new PyString("local"), frame.loadName("chosen"), "local lookup priority");
        frame.deleteName("chosen");
        equal(new PyString("global"), frame.loadName("chosen"), "global lookup fallback");
        equal(PyInt.valueOf(2), frame.loadName("builtin_only"), "builtin lookup fallback");
        equal(PyInt.ONE, frame.loadGlobal("global_only"), "global load");
        expectError("NameError", () -> frame.loadGlobal("missing"));

        expectError("UnboundLocalError", () -> frame.loadFast(0));
        frame.storeFast(0, PyInt.valueOf(10));
        equal(PyInt.valueOf(10), frame.loadFast(0), "fast slot");
        frame.deleteFast(0);
        expectError("UnboundLocalError", () -> frame.loadFast(0));

        expectError("UnboundLocalError", () -> frame.loadDeref(0));
        frame.storeDeref(0, PyInt.valueOf(20));
        equal(PyInt.valueOf(20), frame.loadDeref(0), "cell slot");
        equal(PyInt.valueOf(30), frame.loadDeref(1), "free slot");
        frame.storeDeref(1, PyInt.valueOf(31));
        equal(PyInt.valueOf(31), free.find().orElseThrow(), "shared free cell update");

        frame.storeTemporary(0, PyInt.valueOf(40));
        equal(PyInt.valueOf(40), frame.loadTemporary(0), "temporary slot");
        frame.clearTemporary(0);
        check(!frame.isTemporaryInitialized(0), "temporary clear");
        check(frame.hasInstruction(), "frame must begin at instruction zero");
        equal(OpCode.LOAD_CONST, frame.currentInstruction().getOpCode(),
                "first frame instruction");
        frame.advance();
        equal(OpCode.RETURN_VALUE, frame.currentInstruction().getOpCode(),
                "second frame instruction");
    }

    private static void testResultAndTraceback() {
        Map<String, PyValue> globals = new LinkedHashMap<>();
        globals.put("value", PyInt.ONE);
        VmResult success = VmResult.success(PyNone.INSTANCE, globals, 4);
        globals.put("later", PyInt.ZERO);
        check(success.isSuccess(), "success result flag");
        equal(PyNone.INSTANCE, success.getReturnValue(), "success return value");
        equal(1, success.getGlobals().size(), "globals must be a snapshot");
        expectUnsupported(() -> success.getGlobals().clear(), "globals snapshot immutable");

        SourceSpan span = new SourceSpan("sample.py", 3, 2, 3, 8);
        VmTraceback traceback = new VmTraceback(
                List.of(new VmTraceback.Entry("<module>", span, 7)),
                new PyBaseException("ZeroDivisionError", "division by zero"));
        VmResult failure = VmResult.failure(traceback, success.getGlobals(), 8);
        check(failure.isFailure(), "failure result flag");
        check(failure.getTraceback().format().contains(
                "File \"sample.py\", line 3, in <module>"),
                "traceback source frame");
        check(failure.getTraceback().format().endsWith(
                "ZeroDivisionError: division by zero"), "traceback exception line");
    }

    private static void testVerifiedGateAndDispatchInventory() {
        int publicExecuteMethods = 0;
        for (Method method : BytecodeVM.class.getDeclaredMethods()) {
            if (!method.getName().equals("execute")
                    || !java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            publicExecuteMethods++;
            equal(1, method.getParameterCount(), "VM execute arity");
            equal(VerifiedBytecodeModule.class, method.getParameterTypes()[0],
                    "VM execution capability type");
        }
        equal(1, publicExecuteMethods, "public VM execute entry count");

        Set<OpCode> dispatched = BytecodeVM.dispatchedOpcodes();
        equal(OpCode.allOpcodes(), dispatched, "complete VM dispatch inventory");
        expectUnsupported(() -> dispatched.clear(),
                "VM dispatch inventory must be immutable");
    }

    private static void testVerifiedStraightLine() {
        CodeObjectBuilder builder = newModuleBuilder(200, "straight_line");
        int forty = builder.addConstant(PyInt.valueOf(40));
        int two = builder.addConstant(PyInt.valueOf(2));
        int resultName = builder.addName("result");
        builder.emit(OpCode.LOAD_CONST, new Operand.ConstOperand(forty),
                BYTECODE_SPAN, false);
        builder.emit(OpCode.LOAD_CONST, new Operand.ConstOperand(two),
                BYTECODE_SPAN, false);
        builder.emit(OpCode.BINARY_OP,
                new Operand.BinaryOperatorOperand(Operand.BinaryOperator.ADD),
                BYTECODE_SPAN, false);
        builder.emit(OpCode.STORE_NAME, new Operand.NameOperand(resultName),
                BYTECODE_SPAN, false);
        builder.emit(OpCode.LOAD_NAME, new Operand.NameOperand(resultName),
                BYTECODE_SPAN, false);
        builder.emit(OpCode.RETURN_VALUE, BYTECODE_SPAN, false);

        VerifiedBytecodeModule module = verify(builder);
        BytecodeVM vm = new BytecodeVM();
        VmResult first = vm.execute(module);
        VmResult second = vm.execute(module);
        check(first.isSuccess() && second.isSuccess(),
                "verified straight-line module did not execute");
        equal(PyInt.valueOf(42), first.getReturnValue(), "straight-line result");
        equal(PyInt.valueOf(42), first.getGlobals().get("result"),
                "stored module global");
        equal(new PyString("__main__"), first.getGlobals().get("__name__"),
                "module __name__");
        equal(PyString.EMPTY, first.getGlobals().get("__package__"),
                "module __package__");
        check(first.getGlobals() != second.getGlobals(),
                "executions reused a globals snapshot");
        expectUnsupported(() -> first.getGlobals().put("x", PyNone.INSTANCE),
                "execution globals snapshot must be immutable");
    }

    private static void testVerifiedCollections() {
        CodeObjectBuilder builder = newModuleBuilder(201, "collections");
        int ten = builder.addConstant(PyInt.valueOf(10));
        int twenty = builder.addConstant(PyInt.valueOf(20));
        int thirty = builder.addConstant(PyInt.valueOf(30));
        int ninetyNine = builder.addConstant(PyInt.valueOf(99));
        int one = builder.addConstant(PyInt.ONE);
        int two = builder.addConstant(PyInt.valueOf(2));
        int trueValue = builder.addConstant(PyBool.TRUE);
        int keyA = builder.addConstant(new PyString("a"));
        int keyB = builder.addConstant(new PyString("b"));
        int listName = builder.addName("items");
        int mappingName = builder.addName("mapping");
        int firstName = builder.addName("first");
        int secondName = builder.addName("second");
        int setName = builder.addName("unique");

        emitLoadConst(builder, ten);
        emitLoadConst(builder, twenty);
        emitLoadConst(builder, thirty);
        builder.emit(OpCode.BUILD_LIST, new Operand.CountOperand(3),
                BYTECODE_SPAN, false);
        emitStoreName(builder, listName);

        // STORE_SUBSCR consumes [value, object, key].
        emitLoadConst(builder, ninetyNine);
        emitLoadName(builder, listName);
        emitLoadConst(builder, one);
        builder.emit(OpCode.STORE_SUBSCR, BYTECODE_SPAN, false);

        // BUILD_MAP consumes key/value pairs in source order.
        emitLoadConst(builder, keyA);
        emitLoadConst(builder, one);
        emitLoadConst(builder, keyB);
        emitLoadConst(builder, two);
        builder.emit(OpCode.BUILD_MAP, new Operand.CountOperand(2),
                BYTECODE_SPAN, false);
        emitStoreName(builder, mappingName);

        emitLoadConst(builder, one);
        emitLoadConst(builder, two);
        builder.emit(OpCode.BUILD_TUPLE, new Operand.CountOperand(2),
                BYTECODE_SPAN, false);
        builder.emit(OpCode.UNPACK_SEQUENCE, new Operand.CountOperand(2),
                BYTECODE_SPAN, false);
        emitStoreName(builder, firstName);
        emitStoreName(builder, secondName);

        emitLoadConst(builder, one);
        emitLoadConst(builder, trueValue);
        builder.emit(OpCode.BUILD_SET, new Operand.CountOperand(2),
                BYTECODE_SPAN, false);
        emitStoreName(builder, setName);

        emitLoadName(builder, mappingName);
        emitLoadConst(builder, keyB);
        builder.emit(OpCode.BINARY_SUBSCR, BYTECODE_SPAN, false);
        builder.emit(OpCode.RETURN_VALUE, BYTECODE_SPAN, false);

        VmResult result = new BytecodeVM().execute(verify(builder));
        check(result.isSuccess(), "verified collection module failed");
        equal(PyInt.valueOf(2), result.getReturnValue(), "mapping subscript result");
        equal("[10, 99, 30]", result.getGlobals().get("items").repr(),
                "list item assignment");
        equal("{'a': 1, 'b': 2}", result.getGlobals().get("mapping").repr(),
                "mapping build order");
        equal(PyInt.ONE, result.getGlobals().get("first"), "unpacked first item");
        equal(PyInt.valueOf(2), result.getGlobals().get("second"),
                "unpacked second item");
        equal(1, ((PySet) result.getGlobals().get("unique")).size(),
                "Python-equal set elements");
    }

    private static void testVerifiedBranches() {
        CodeObjectBuilder builder = newModuleBuilder(202, "branches");
        int zero = builder.addConstant(PyInt.ZERO);
        int unreachable = builder.addConstant(PyInt.valueOf(999));
        int selected = builder.addName("selected");
        Label falseBranch = builder.newLabel("false_branch");

        emitLoadConst(builder, zero);
        builder.emitJump(OpCode.JUMP_IF_FALSE_OR_POP, falseBranch,
                BYTECODE_SPAN, false);
        emitLoadConst(builder, unreachable);
        builder.mark(falseBranch);
        emitStoreName(builder, selected);
        emitLoadName(builder, selected);
        builder.emit(OpCode.RETURN_VALUE, BYTECODE_SPAN, false);

        VmResult result = new BytecodeVM().execute(verify(builder));
        check(result.isSuccess(), "verified branch module failed");
        equal(PyInt.ZERO, result.getReturnValue(),
                "false short-circuit must retain original operand");
    }

    private static void testVerifiedWhileLoop() {
        CodeObjectBuilder builder = newModuleBuilder(203, "while_loop");
        int zero = builder.addConstant(PyInt.ZERO);
        int one = builder.addConstant(PyInt.ONE);
        int five = builder.addConstant(PyInt.valueOf(5));
        int total = builder.addName("total");
        int index = builder.addName("index");
        Label top = builder.newLabel("top");
        Label end = builder.newLabel("end");

        emitLoadConst(builder, zero);
        emitStoreName(builder, total);
        emitLoadConst(builder, zero);
        emitStoreName(builder, index);
        builder.mark(top);
        emitLoadName(builder, index);
        emitLoadConst(builder, five);
        builder.emit(OpCode.COMPARE_OP,
                new Operand.CompareOperatorOperand(Operand.CompareOperator.LT),
                BYTECODE_SPAN, false);
        builder.emitJump(OpCode.POP_JUMP_IF_FALSE, end, BYTECODE_SPAN, false);

        emitLoadName(builder, total);
        emitLoadName(builder, index);
        builder.emit(OpCode.BINARY_OP,
                new Operand.BinaryOperatorOperand(Operand.BinaryOperator.ADD),
                BYTECODE_SPAN, false);
        emitStoreName(builder, total);
        emitLoadName(builder, index);
        emitLoadConst(builder, one);
        builder.emit(OpCode.BINARY_OP,
                new Operand.BinaryOperatorOperand(Operand.BinaryOperator.ADD),
                BYTECODE_SPAN, false);
        emitStoreName(builder, index);
        builder.emitJump(OpCode.JUMP, top, BYTECODE_SPAN, false);

        builder.mark(end);
        emitLoadName(builder, total);
        builder.emit(OpCode.RETURN_VALUE, BYTECODE_SPAN, false);

        VmResult result = new BytecodeVM().execute(verify(builder));
        check(result.isSuccess(), "verified while-loop module failed");
        equal(PyInt.valueOf(10), result.getReturnValue(), "while-loop sum");
    }

    private static void testVerifiedForExhaustion() {
        CodeObjectBuilder builder = newModuleBuilder(204, "for_exhaustion");
        builder.setTemporarySlotCount(1);
        int zero = builder.addConstant(PyInt.ZERO);
        int one = builder.addConstant(PyInt.ONE);
        int two = builder.addConstant(PyInt.valueOf(2));
        int three = builder.addConstant(PyInt.valueOf(3));
        int total = builder.addName("total");
        int item = builder.addName("item");
        Label lifetimeStart = builder.newLabel("iterator_lifetime_start");
        Label top = builder.newLabel("for_top");
        Label naturalExit = builder.newLabel("natural_exit");
        Label lifetimeEnd = builder.newLabel("iterator_lifetime_end");

        emitLoadConst(builder, one);
        emitLoadConst(builder, two);
        emitLoadConst(builder, three);
        builder.emit(OpCode.BUILD_LIST, new Operand.CountOperand(3),
                BYTECODE_SPAN, false);
        builder.emit(OpCode.GET_ITER, BYTECODE_SPAN, false);
        builder.emit(OpCode.STORE_TEMP, new Operand.TempSlotOperand(0),
                BYTECODE_SPAN, true);
        builder.mark(lifetimeStart);
        emitLoadConst(builder, zero);
        emitStoreName(builder, total);

        builder.mark(top);
        builder.emit(OpCode.LOAD_TEMP, new Operand.TempSlotOperand(0),
                BYTECODE_SPAN, true);
        builder.emitJump(OpCode.FOR_ITER, naturalExit, BYTECODE_SPAN, false);
        emitStoreName(builder, item);
        emitLoadName(builder, total);
        emitLoadName(builder, item);
        builder.emit(OpCode.BINARY_OP,
                new Operand.BinaryOperatorOperand(Operand.BinaryOperator.ADD),
                BYTECODE_SPAN, false);
        emitStoreName(builder, total);
        builder.emitJump(OpCode.JUMP, top, BYTECODE_SPAN, false);

        builder.mark(naturalExit);
        builder.emit(OpCode.CLEAR_TEMP, new Operand.TempSlotOperand(0),
                BYTECODE_SPAN, true);
        builder.mark(lifetimeEnd);
        emitLoadName(builder, total);
        builder.emit(OpCode.RETURN_VALUE, BYTECODE_SPAN, false);
        builder.addCleanupRegion(CleanupRegion.Kind.TEMP_CLEAR,
                lifetimeStart, lifetimeEnd, null, null, 0);

        VmResult result = new BytecodeVM().execute(verify(builder));
        check(result.isSuccess(), "verified for-exhaustion module failed");
        equal(PyInt.valueOf(6), result.getReturnValue(), "for-loop sum");
    }

    private static void testVerifiedForBreakUnwind() throws Exception {
        CodeObjectBuilder builder = newModuleBuilder(205, "for_break");
        builder.setTemporarySlotCount(1);
        int one = builder.addConstant(PyInt.ONE);
        int two = builder.addConstant(PyInt.valueOf(2));
        int broken = builder.addConstant(new PyString("broken"));
        int exhausted = builder.addConstant(new PyString("exhausted"));
        int elseMarkerValue = builder.addConstant(new PyString("else-ran"));
        int item = builder.addName("item");
        int elseMarker = builder.addName("else_marker");
        Label lifetimeStart = builder.newLabel("iterator_lifetime_start");
        Label naturalExit = builder.newLabel("natural_exit");
        Label lifetimeEnd = builder.newLabel("iterator_lifetime_end");
        Label breakExit = builder.newLabel("break_exit");

        emitLoadConst(builder, one);
        emitLoadConst(builder, two);
        builder.emit(OpCode.BUILD_LIST, new Operand.CountOperand(2),
                BYTECODE_SPAN, false);
        builder.emit(OpCode.GET_ITER, BYTECODE_SPAN, false);
        builder.emit(OpCode.STORE_TEMP, new Operand.TempSlotOperand(0),
                BYTECODE_SPAN, true);
        builder.mark(lifetimeStart);
        builder.emit(OpCode.LOAD_TEMP, new Operand.TempSlotOperand(0),
                BYTECODE_SPAN, true);
        builder.emitJump(OpCode.FOR_ITER, naturalExit, BYTECODE_SPAN, false);
        emitStoreName(builder, item);
        builder.emitJump(OpCode.UNWIND_JUMP, breakExit, BYTECODE_SPAN, true);

        builder.mark(naturalExit);
        builder.emit(OpCode.CLEAR_TEMP, new Operand.TempSlotOperand(0),
                BYTECODE_SPAN, true);
        builder.mark(lifetimeEnd);
        emitLoadConst(builder, elseMarkerValue);
        emitStoreName(builder, elseMarker);
        emitLoadConst(builder, exhausted);
        builder.emit(OpCode.RETURN_VALUE, BYTECODE_SPAN, false);

        builder.mark(breakExit);
        emitLoadConst(builder, broken);
        builder.emit(OpCode.RETURN_VALUE, BYTECODE_SPAN, false);
        builder.addCleanupRegion(CleanupRegion.Kind.TEMP_CLEAR,
                lifetimeStart, lifetimeEnd, null, null, 0);

        VerifiedBytecodeModule module = verify(builder);
        VmResult result = new BytecodeVM().execute(module);
        check(result.isSuccess(), "verified for-break module failed");
        equal(new PyString("broken"), result.getReturnValue(), "break result");
        check(!result.getGlobals().containsKey("else_marker"),
                "for-else body ran after break");

        // Successful completion proves the verified PendingTransfer path
        // cleared the hidden iterator before reaching the break destination.
    }

    private static void testVmRuntimeFailureAndIsolation() {
        CodeObjectBuilder builder = newModuleBuilder(206, "runtime_failure");
        int one = builder.addConstant(PyInt.ONE);
        int zero = builder.addConstant(PyInt.ZERO);
        SourceSpan divisionSpan = new SourceSpan(BYTECODE_SOURCE, 7, 4, 7, 9);
        emitLoadConst(builder, one);
        emitLoadConst(builder, zero);
        builder.emit(OpCode.BINARY_OP,
                new Operand.BinaryOperatorOperand(
                        Operand.BinaryOperator.TRUE_DIVIDE),
                divisionSpan, false);
        builder.emit(OpCode.RETURN_VALUE, BYTECODE_SPAN, false);

        VerifiedBytecodeModule module = verify(builder);
        BytecodeVM vm = new BytecodeVM();
        VmResult first = vm.execute(module);
        VmResult second = vm.execute(module);
        check(first.isFailure() && second.isFailure(),
                "division failure escaped VmResult");
        equal("ZeroDivisionError",
                first.getTraceback().getException().getExceptionTypeName(),
                "runtime exception type");
        equal(7, first.getTraceback().getEntries().get(0)
                .getSourceSpan().getStartLine(), "runtime traceback line");
        check(first.getTraceback().format().contains("division by zero"),
                "runtime traceback message");
        check(first.getGlobals() != second.getGlobals(),
                "failed executions reused globals");
        equal(Set.of("__name__", "__package__"), first.getGlobals().keySet(),
                "failure globals isolation");
    }

    private static void testVmLimitsAndDeferredOpcode() {
        CodeObjectBuilder infinite = newModuleBuilder(207, "infinite");
        Label top = infinite.newLabel("top");
        infinite.mark(top);
        infinite.emitJump(OpCode.JUMP, top, BYTECODE_SPAN, true);
        VmResult limited = new BytecodeVM(new ExecutionLimits(5, 10, 10))
                .execute(verify(infinite));
        check(limited.isFailure(), "instruction limit did not stop infinite loop");
        equal("RuntimeError",
                limited.getTraceback().getException().getExceptionTypeName(),
                "instruction-limit error type");
        check(limited.getTraceback().getException().getMessageText()
                        .contains("maximum instruction count"),
                "instruction-limit message");
        equal(5L, limited.getExecutedInstructionCount(),
                "instruction limit counts executed instructions only");

        CodeObjectBuilder deferred = newModuleBuilder(208, "deferred_raise");
        int none = deferred.addConstant(PyNone.INSTANCE);
        emitLoadConst(deferred, none);
        deferred.emit(OpCode.RAISE,
                new Operand.CountOperand(1),
                BYTECODE_SPAN, false);
        VmResult rejected = new BytecodeVM().execute(verify(deferred));
        check(rejected.isFailure(), "invalid RAISE was silently accepted");
        equal("TypeError",
                rejected.getTraceback().getException().getExceptionTypeName(),
                "invalid raise error type");
        check(rejected.getTraceback().getException().getMessageText()
                .contains("BaseException"),
                "invalid raise rejection did not explain the type contract");

        CodeObjectBuilder invalidImport = newModuleBuilder(209, "invalid_import_from");
        int importNone = invalidImport.addConstant(PyNone.INSTANCE);
        int importedName = invalidImport.addName("value");
        emitLoadConst(invalidImport, importNone);
        invalidImport.emit(OpCode.IMPORT_FROM,
                new Operand.NameOperand(importedName), BYTECODE_SPAN, false);
        invalidImport.emit(OpCode.POP_TOP, BYTECODE_SPAN, false);
        invalidImport.emit(OpCode.POP_TOP, BYTECODE_SPAN, false);
        emitLoadConst(invalidImport, importNone);
        invalidImport.emit(OpCode.RETURN_VALUE, BYTECODE_SPAN, false);
        VmResult invalidImportResult = new BytecodeVM().execute(verify(invalidImport));
        check(invalidImportResult.isFailure(),
                "IMPORT_FROM accepted a non-module runtime value");
        equal("TypeError",
                invalidImportResult.getTraceback().getException().getExceptionTypeName(),
                "invalid IMPORT_FROM error type");
    }

    private static CodeObjectBuilder newModuleBuilder(int codeId, String name) {
        return new CodeObjectBuilder(
                codeId,
                CodeKind.MODULE,
                "<module>",
                name,
                BYTECODE_SOURCE,
                FunctionSignature.EMPTY,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList());
    }

    private static VerifiedBytecodeModule verify(CodeObjectBuilder builder) {
        DiagnosticReporter reporter = new DiagnosticReporter();
        BytecodeModule assembled = new BytecodeAssembler().assembleModule(
                BYTECODE_SOURCE, "__main__", builder, reporter);
        if (assembled == null || reporter.hasErrors()) {
            throw new AssertionError("bytecode assembly failed: "
                    + diagnosticsText(reporter.diagnostics()));
        }
        ControlFlowVerifier.VerificationResult verification =
                new ControlFlowVerifier().verify(assembled);
        if (!verification.isSuccess()) {
            throw new AssertionError("bytecode verification failed: "
                    + diagnosticsText(verification.getDiagnostics()));
        }
        VerifiedBytecodeModule module = verification.getModule();
        check(module != null && module.hasValidVerificationStamp(),
                "verifier did not return a valid execution capability");
        return module;
    }

    private static String diagnosticsText(List<Diagnostic> diagnostics) {
        StringBuilder result = new StringBuilder();
        for (Diagnostic diagnostic : diagnostics) {
            if (result.length() > 0) {
                result.append(" | ");
            }
            result.append(diagnostic.message());
        }
        return result.toString();
    }

    private static void emitLoadConst(CodeObjectBuilder builder, int index) {
        builder.emit(OpCode.LOAD_CONST, new Operand.ConstOperand(index),
                BYTECODE_SPAN, false);
    }

    private static void emitLoadName(CodeObjectBuilder builder, int index) {
        builder.emit(OpCode.LOAD_NAME, new Operand.NameOperand(index),
                BYTECODE_SPAN, false);
    }

    private static void emitStoreName(CodeObjectBuilder builder, int index) {
        builder.emit(OpCode.STORE_NAME, new Operand.NameOperand(index),
                BYTECODE_SPAN, false);
    }

    private static int findOpcode(CodeObject code, OpCode opCode) {
        for (int offset = 0; offset < code.getInstructions().size(); offset++) {
            if (code.getInstructions().get(offset).getOpCode() == opCode) {
                return offset;
            }
        }
        throw new AssertionError("missing opcode " + opCode);
    }

    private static void assertInt(long expected, PyValue actual, String label) {
        check(actual instanceof PyInt, label + " did not produce PyInt");
        equal(BigInteger.valueOf(expected), ((PyInt) actual).getValue(), label);
    }

    private static CodeObject verifiedCode(
            CodeKind kind,
            List<String> fastLocals,
            List<String> cellVariables,
            List<String> freeVariables,
            int temporarySlots) {
        List<Instruction> instructions = List.of(
                new Instruction(OpCode.LOAD_CONST, new Operand.ConstOperand(0)),
                new Instruction(OpCode.RETURN_VALUE));
        SourceSpan span = new SourceSpan("core-vm.py", 1, 0, 1, 1);
        return new CodeObject(
                BytecodeFormat.CURRENT_VERSION,
                100,
                kind,
                "test",
                "test",
                "core-vm.py",
                instructions,
                new ConstantPool(List.of(PyNone.INSTANCE)),
                new NamePool(List.of()),
                FunctionSignature.EMPTY,
                fastLocals,
                cellVariables,
                freeVariables,
                temporarySlots,
                List.of(),
                List.of(),
                new SourceMap(List.of(
                        new InstructionLocation(span, false),
                        new InstructionLocation(span, true))),
                List.of(),
                1);
    }

    private static void closeTo(double expected, PyValue actual, String label) {
        check(actual instanceof PyFloat, label + " did not produce PyFloat");
        double value = ((PyFloat) actual).getValue();
        if (Math.abs(expected - value) > 1e-12) {
            throw new AssertionError(label + ": expected <" + expected
                    + "> but was <" + value + ">");
        }
    }

    private static void expectError(String typeName, Runnable operation) {
        try {
            operation.run();
        } catch (VmRuntimeException error) {
            equal(typeName, error.getExceptionValue().getExceptionTypeName(),
                    "runtime error type");
            return;
        }
        throw new AssertionError("expected runtime error " + typeName);
    }

    private static void expectUnsupported(Runnable operation, String message) {
        try {
            operation.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError(message);
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

    private static final class PyFloatZero {
        private static final PyFloat VALUE = new PyFloat(0.0);
    }

    private static final class TestAttributeValue
            implements PyValue, PyAttributeProvider {
        private final Map<String, PyValue> attributes = new LinkedHashMap<>();

        @Override
        public Optional<PyValue> findAttribute(String name) {
            return Optional.ofNullable(attributes.get(name));
        }

        @Override
        public boolean setAttribute(String name, PyValue value) {
            attributes.put(name, value);
            return true;
        }

        @Override
        public boolean deleteAttribute(String name) {
            return attributes.remove(name) != null;
        }

        @Override
        public String getTypeName() {
            return "test_object";
        }

        @Override
        public String repr() {
            return "<test_object>";
        }
    }
}
