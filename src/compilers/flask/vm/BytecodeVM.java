package compilers.flask.vm;

import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.codegen.bytecode.BytecodeFormat;
import compilers.flask.codegen.bytecode.BytecodeModule;
import compilers.flask.codegen.bytecode.CleanupRegion;
import compilers.flask.codegen.bytecode.CodeKind;
import compilers.flask.codegen.bytecode.CodeObject;
import compilers.flask.codegen.bytecode.ExceptionRegion;
import compilers.flask.codegen.bytecode.Instruction;
import compilers.flask.codegen.bytecode.InstructionLocation;
import compilers.flask.codegen.bytecode.OpCode;
import compilers.flask.codegen.bytecode.Operand;
import compilers.flask.codegen.bytecode.StackAnchor;
import compilers.flask.codegen.bytecode.VerifiedBytecodeModule;
import compilers.flask.vm.modules.ModuleLoader;
import compilers.flask.vm.modules.ModuleRegistry;
import compilers.flask.vm.modules.OsNativeProviders;
import compilers.flask.vm.builtins.PythonBuiltinProviders;
import compilers.flask.vm.flask.FlaskNativeProviders;
import compilers.flask.vm.flask.PyFlaskApp;
import compilers.flask.vm.flask.PyRequest;
import compilers.flask.vm.values.PyDict;
import compilers.flask.vm.values.PyBaseException;
import compilers.flask.vm.values.PyBool;
import compilers.flask.vm.values.PyCallable;
import compilers.flask.vm.values.PyClass;
import compilers.flask.vm.values.PyFunction;
import compilers.flask.vm.values.PyIterator;
import compilers.flask.vm.values.PyInstance;
import compilers.flask.vm.values.PyList;
import compilers.flask.vm.values.PyModule;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PySet;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyTuple;
import compilers.flask.vm.values.PyTracebackValue;
import compilers.flask.vm.values.PyType;
import compilers.flask.vm.values.PyValue;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Interpreter for the verified core Flask bytecode instruction subset. */
public final class BytecodeVM {
    /*
     * Keep this inventory independent from OpCode.values().  The coverage
     * harness must fail when a new opcode is added without an explicit VM
     * dispatch decision, including a deliberate phase-deferred rejection.
     */
    private static final Set<OpCode> DISPATCHED_OPCODES =
            Collections.unmodifiableSet(EnumSet.of(
                    OpCode.NOP, OpCode.POP_TOP, OpCode.COPY, OpCode.SWAP,
                    OpCode.LOAD_CONST, OpCode.LOAD_NAME, OpCode.STORE_NAME,
                    OpCode.DELETE_NAME, OpCode.LOAD_FAST, OpCode.STORE_FAST,
                    OpCode.DELETE_FAST, OpCode.LOAD_GLOBAL, OpCode.STORE_GLOBAL,
                    OpCode.DELETE_GLOBAL, OpCode.LOAD_DEREF, OpCode.STORE_DEREF,
                    OpCode.DELETE_DEREF, OpCode.LOAD_CLOSURE, OpCode.LOAD_TEMP,
                    OpCode.STORE_TEMP, OpCode.CLEAR_TEMP, OpCode.LOAD_ATTR,
                    OpCode.STORE_ATTR, OpCode.DELETE_ATTR, OpCode.BINARY_SUBSCR,
                    OpCode.STORE_SUBSCR, OpCode.DELETE_SUBSCR, OpCode.BUILD_LIST,
                    OpCode.BUILD_TUPLE, OpCode.BUILD_SET, OpCode.BUILD_MAP,
                    OpCode.UNPACK_SEQUENCE, OpCode.FORMAT_VALUE,
                    OpCode.BUILD_STRING, OpCode.GET_ITER, OpCode.FOR_ITER,
                    OpCode.UNARY_OP, OpCode.BINARY_OP, OpCode.COMPARE_OP,
                    OpCode.CALL, OpCode.MAKE_FUNCTION, OpCode.BUILD_CLASS,
                    OpCode.IMPORT_NAME, OpCode.IMPORT_FROM, OpCode.IMPORT_STAR,
                    OpCode.JUMP, OpCode.POP_JUMP_IF_FALSE,
                    OpCode.POP_JUMP_IF_TRUE, OpCode.JUMP_IF_FALSE_OR_POP,
                    OpCode.JUMP_IF_TRUE_OR_POP, OpCode.UNWIND_JUMP,
                    OpCode.RETURN_VALUE, OpCode.RAISE,
                    OpCode.LOAD_CURRENT_EXCEPTION, OpCode.EXCEPTION_MATCH,
                    OpCode.BEGIN_EXCEPT, OpCode.END_EXCEPT,
                    OpCode.ENTER_CLEANUP, OpCode.END_CLEANUP,
                    OpCode.WITH_ENTER, OpCode.WITH_EXIT));

    private final ExecutionLimits limits;
    private final Map<String, PyValue> builtinTemplate;
    private final ModuleRegistry moduleRegistry;
    private final VmCapabilities capabilities;

    public BytecodeVM() {
        this(ExecutionLimits.DEFAULT, defaultEnvironment(VmCapabilities.NONE));
    }

    public BytecodeVM(ExecutionLimits limits) {
        this(limits, defaultEnvironment(VmCapabilities.NONE));
    }

    public BytecodeVM(ExecutionLimits limits, VmCapabilities capabilities) {
        this(limits, defaultEnvironment(capabilities));
    }

    public BytecodeVM(ExecutionLimits limits, Namespace builtins) {
        this(limits, builtins, ModuleRegistry.empty());
    }

    public BytecodeVM(
            ExecutionLimits limits,
            Namespace builtins,
            ModuleRegistry moduleRegistry) {
        this(limits, builtins, moduleRegistry, VmCapabilities.NONE);
    }

    public BytecodeVM(
            ExecutionLimits limits,
            Namespace builtins,
            ModuleRegistry moduleRegistry,
            VmCapabilities capabilities) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.builtinTemplate = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(builtins, "builtins").snapshot()));
        this.moduleRegistry = Objects.requireNonNull(moduleRegistry, "moduleRegistry");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    public ExecutionLimits getLimits() {
        return limits;
    }

    private BytecodeVM(ExecutionLimits limits, DefaultEnvironment environment) {
        this(limits, environment.builtins, environment.registry,
                environment.capabilities);
    }

    private static DefaultEnvironment defaultEnvironment(
            VmCapabilities capabilities) {
        Namespace builtins = PythonBuiltinProviders.create();
        ModuleRegistry.Builder registry = ModuleRegistry.builder()
                .registerNative("builtins", (module, loader) -> {
                    for (Map.Entry<String, PyValue> entry
                            : builtins.snapshot().entrySet()) {
                        module.put(entry.getKey(), entry.getValue());
                    }
                });
        FlaskNativeProviders.register(registry);
        OsNativeProviders.register(registry);
        return new DefaultEnvironment(
                builtins, registry.build(),
                Objects.requireNonNull(capabilities, "capabilities"));
    }

    private static final class DefaultEnvironment {
        private final Namespace builtins;
        private final ModuleRegistry registry;
        private final VmCapabilities capabilities;
        private DefaultEnvironment(
                Namespace builtins,
                ModuleRegistry registry,
                VmCapabilities capabilities) {
            this.builtins = builtins;
            this.registry = registry;
            this.capabilities = capabilities;
        }
    }

    /**
     * Exact opcode inventory with an explicit switch arm. Some later-phase
     * opcodes currently reject execution by name, but none can fall through a
     * silent/default dispatch path.
     */
    public static Set<OpCode> dispatchedOpcodes() {
        return DISPATCHED_OPCODES;
    }

    /**
     * Sole execution entry point. A raw {@code BytecodeModule} is not accepted
     * by this API and no frame is created until the stamp and format recheck.
     */
    public VmResult execute(VerifiedBytecodeModule verifiedModule) {
        BytecodeModule module = requireExecutableModule(verifiedModule);
        CodeObject root = module.getRootCode();

        Namespace globals = freshModuleGlobals(module);
        Namespace builtins = new Namespace(builtinTemplate);
        Frame frame = Frame.module(
                root, globals, builtins, limits.getMaximumOperandStackSize());
        ExecutionLimits.Meter meter = limits.newMeter();
        ExecutionState state = new ExecutionState(
                meter, builtins, limits.getMaximumOperandStackSize(),
                moduleRegistry, capabilities);

        meter.enterCall();
        state.pushFrame(frame);
        try {
            try {
                PyValue returnValue = executeFrame(frame, state);
                return VmResult.success(
                        returnValue, globals.snapshot(), meter.getInstructionCount(),
                        state.getStdout());
            } catch (VmRuntimeException runtimeFailure) {
                VmTraceback traceback = buildTraceback(runtimeFailure);
                return VmResult.failure(
                        traceback, globals.snapshot(), meter.getInstructionCount(),
                        state.getStdout());
            }
        } finally {
            state.popFrame(frame);
            meter.exitCall();
        }
    }

    /** Invokes a retained Python/native callable in a fresh isolated VM run. */
    public VmResult invokeCallable(
            PyValue callable,
            List<PyValue> positionalArguments,
            Map<String, PyValue> keywordArguments) {
        Namespace builtins = new Namespace(builtinTemplate);
        ExecutionLimits.Meter meter = limits.newMeter();
        ExecutionState state = new ExecutionState(
                meter, builtins, limits.getMaximumOperandStackSize(),
                moduleRegistry, capabilities);
        Map<String, PyValue> globals = callable instanceof PyFunction
                ? ((PyFunction) callable).getGlobals().snapshot()
                : Collections.<String, PyValue>emptyMap();
        try {
            PyValue result = state.invoke(
                    callable, positionalArguments, keywordArguments);
            return VmResult.success(
                    result, globals, meter.getInstructionCount(), state.getStdout());
        } catch (VmRuntimeException failure) {
            return VmResult.failure(
                    buildTraceback(failure), globals,
                    meter.getInstructionCount(), state.getStdout());
        }
    }

    /** Executes a registered Flask route without opening a network server. */
    public VmResult invokeRoute(
            PyFlaskApp app,
            String endpoint,
            PyRequest request,
            PyDict session) {
        Objects.requireNonNull(app, "app");
        Namespace builtins = new Namespace(builtinTemplate);
        ExecutionLimits.Meter meter = limits.newMeter();
        ExecutionState state = new ExecutionState(
                meter, builtins, limits.getMaximumOperandStackSize(),
                moduleRegistry, capabilities);
        try {
            PyValue result = app.invokeRoute(state, endpoint, request, session);
            return VmResult.success(result, Collections.<String, PyValue>emptyMap(),
                    meter.getInstructionCount(), state.getStdout());
        } catch (VmRuntimeException failure) {
            return VmResult.failure(buildTraceback(failure),
                    Collections.<String, PyValue>emptyMap(),
                    meter.getInstructionCount(), state.getStdout());
        }
    }

    /**
     * Resolves and invokes a registered Flask route by HTTP method and path in
     * one fresh, isolated host call. Dynamic path components are passed to the
     * route callable by {@link PyFlaskApp}; no network server is opened.
     */
    public VmResult invokeRoute(
            PyFlaskApp app,
            String method,
            String path,
            PyDict session) {
        Objects.requireNonNull(app, "app");
        Namespace builtins = new Namespace(builtinTemplate);
        ExecutionLimits.Meter meter = limits.newMeter();
        ExecutionState state = new ExecutionState(
                meter, builtins, limits.getMaximumOperandStackSize(),
                moduleRegistry, capabilities);
        try {
            PyValue result = app.invokePath(state, method, path, session);
            return VmResult.success(result, Collections.<String, PyValue>emptyMap(),
                    meter.getInstructionCount(), state.getStdout());
        } catch (VmRuntimeException failure) {
            return VmResult.failure(buildTraceback(failure),
                    Collections.<String, PyValue>emptyMap(),
                    meter.getInstructionCount(), state.getStdout());
        }
    }

    /** Invokes an immutable method/path match, including its placeholder values. */
    public VmResult invokeResolvedRoute(
            PyFlaskApp app,
            PyFlaskApp.RouteMatch match,
            PyDict session) {
        Objects.requireNonNull(app, "app");
        Objects.requireNonNull(match, "match");
        Namespace builtins = new Namespace(builtinTemplate);
        ExecutionLimits.Meter meter = limits.newMeter();
        ExecutionState state = new ExecutionState(
                meter, builtins, limits.getMaximumOperandStackSize(),
                moduleRegistry, capabilities);
        try {
            PyValue result = app.invokeResolvedRoute(state, match, session);
            return VmResult.success(result, Collections.<String, PyValue>emptyMap(),
                    meter.getInstructionCount(), state.getStdout());
        } catch (VmRuntimeException failure) {
            return VmResult.failure(buildTraceback(failure),
                    Collections.<String, PyValue>emptyMap(),
                    meter.getInstructionCount(), state.getStdout());
        }
    }

    /** Resolves and invokes a method/path request without a network server. */
    public VmResult invokeRouteByPath(
            PyFlaskApp app,
            String method,
            String path,
            PyDict session) {
        Objects.requireNonNull(app, "app");
        PyFlaskApp.RouteMatch match = app.resolveRoute(method, path).orElseThrow(() ->
                RuntimeOps.error("RuntimeError", "no route for "
                        + method.toUpperCase(java.util.Locale.ROOT) + " " + path));
        return invokeResolvedRoute(app, match, session);
    }

    private static BytecodeModule requireExecutableModule(
            VerifiedBytecodeModule verifiedModule) {
        Objects.requireNonNull(verifiedModule, "verifiedModule");
        if (!verifiedModule.hasValidVerificationStamp()) {
            throw new IllegalArgumentException(
                    "VM refused a stale or forged bytecode verification token");
        }
        BytecodeModule module = verifiedModule.getModule();
        BytecodeFormat.requireSupported(module.getMagic(), module.getFormatVersion());
        if (verifiedModule.getFormatVersion() != BytecodeFormat.CURRENT_VERSION) {
            throw new IllegalArgumentException("VM refused unsupported bytecode version");
        }
        CodeObject root = module.getRootCode();
        if (root.getKind() != CodeKind.MODULE) {
            throw new IllegalArgumentException("Bytecode module root must have MODULE kind");
        }
        if (!root.isVerified()) {
            throw new IllegalArgumentException("Bytecode module root is not verified");
        }
        if (root.getFormatVersion() != module.getFormatVersion()) {
            throw new IllegalArgumentException(
                    "Root CodeObject version does not match its module");
        }
        return module;
    }

    private static Namespace freshModuleGlobals(BytecodeModule module) {
        Namespace globals = new Namespace();
        globals.put("__name__", new PyString(module.getModuleName()));
        String packageName = module.getMetadata().get("__package__");
        if (packageName == null) {
            int lastDot = module.getModuleName().lastIndexOf('.');
            packageName = lastDot < 0 ? "" : module.getModuleName().substring(0, lastDot);
        }
        globals.put("__package__", new PyString(packageName));
        return globals;
    }

    private static PyValue executeFrame(Frame frame, ExecutionState state) {
        while (frame.hasInstruction()) {
            Instruction instruction = frame.currentInstruction();
            OperandStack stack = frame.getOperandStack();
            int instructionOffset = frame.getInstructionPointer();
            List<VmStackValue> instructionStack = stack.snapshot();

            try {
                // Limits are VM runtime failures too.  Keep this inside the
                // instruction catch so cancellation/limit tracebacks retain
                // the current source location and can traverse cleanup.
                state.meter.beforeInstruction();
                switch (instruction.getOpCode()) {
                case NOP:
                    frame.advance();
                    break;
                case POP_TOP:
                    stack.pop();
                    frame.advance();
                    break;
                case COPY:
                    stack.copy(count(instruction));
                    frame.advance();
                    break;
                case SWAP:
                    stack.swap(count(instruction));
                    frame.advance();
                    break;

                case LOAD_CONST:
                    loadConstant(frame, indexed(instruction));
                    frame.advance();
                    break;
                case LOAD_NAME:
                    stack.push(frame.loadName(name(frame, instruction)));
                    frame.advance();
                    break;
                case STORE_NAME:
                    frame.storeName(name(frame, instruction), stack.popPyValue());
                    frame.advance();
                    break;
                case DELETE_NAME:
                    frame.deleteName(name(frame, instruction));
                    frame.advance();
                    break;
                case LOAD_FAST:
                    stack.push(frame.loadFast(indexed(instruction)));
                    frame.advance();
                    break;
                case STORE_FAST:
                    frame.storeFast(indexed(instruction), stack.popPyValue());
                    frame.advance();
                    break;
                case DELETE_FAST:
                    frame.deleteFast(indexed(instruction));
                    frame.advance();
                    break;
                case LOAD_GLOBAL:
                    stack.push(frame.loadGlobal(name(frame, instruction)));
                    frame.advance();
                    break;
                case STORE_GLOBAL:
                    frame.storeGlobal(name(frame, instruction), stack.popPyValue());
                    frame.advance();
                    break;
                case DELETE_GLOBAL:
                    frame.deleteGlobal(name(frame, instruction));
                    frame.advance();
                    break;
                case LOAD_DEREF:
                    stack.push(frame.loadDeref(indexed(instruction)));
                    frame.advance();
                    break;
                case STORE_DEREF:
                    frame.storeDeref(indexed(instruction), stack.popPyValue());
                    frame.advance();
                    break;
                case DELETE_DEREF:
                    frame.deleteDeref(indexed(instruction));
                    frame.advance();
                    break;
                case LOAD_CLOSURE:
                    stack.push(new CellRef(frame.getDerefCell(indexed(instruction))));
                    frame.advance();
                    break;
                case LOAD_TEMP:
                    stack.push(frame.loadTemporary(indexed(instruction)));
                    frame.advance();
                    break;
                case STORE_TEMP:
                    frame.storeTemporary(indexed(instruction), stack.popPyValue());
                    frame.advance();
                    break;
                case CLEAR_TEMP:
                    frame.clearTemporary(indexed(instruction));
                    frame.advance();
                    break;

                case LOAD_ATTR: {
                    PyValue object = stack.popPyValue();
                    stack.push(RuntimeOps.loadAttribute(
                            object, name(frame, instruction), state));
                    frame.advance();
                    break;
                }
                case STORE_ATTR: {
                    PyValue object = stack.popPyValue();
                    PyValue value = stack.popPyValue();
                    RuntimeOps.storeAttribute(
                            object, name(frame, instruction), value, state);
                    frame.advance();
                    break;
                }
                case DELETE_ATTR:
                    RuntimeOps.deleteAttribute(
                            stack.popPyValue(), name(frame, instruction), state);
                    frame.advance();
                    break;
                case BINARY_SUBSCR: {
                    PyValue key = stack.popPyValue();
                    PyValue object = stack.popPyValue();
                    stack.push(RuntimeOps.loadSubscript(object, key, state));
                    frame.advance();
                    break;
                }
                case STORE_SUBSCR: {
                    PyValue key = stack.popPyValue();
                    PyValue object = stack.popPyValue();
                    PyValue value = stack.popPyValue();
                    RuntimeOps.storeSubscript(object, key, value, state);
                    frame.advance();
                    break;
                }
                case DELETE_SUBSCR: {
                    PyValue key = stack.popPyValue();
                    PyValue object = stack.popPyValue();
                    RuntimeOps.deleteSubscript(object, key, state);
                    frame.advance();
                    break;
                }
                case BUILD_LIST:
                    stack.push(new PyList(
                            stack.popPyValuesInSourceOrder(count(instruction))));
                    frame.advance();
                    break;
                case BUILD_TUPLE:
                    stack.push(new PyTuple(
                            stack.popPyValuesInSourceOrder(count(instruction))));
                    frame.advance();
                    break;
                case BUILD_SET:
                    stack.push(new PySet(
                            stack.popPyValuesInSourceOrder(count(instruction))));
                    frame.advance();
                    break;
                case BUILD_MAP:
                    buildMap(stack, count(instruction));
                    frame.advance();
                    break;
                case UNPACK_SEQUENCE:
                    unpack(stack, count(instruction));
                    frame.advance();
                    break;
                case FORMAT_VALUE:
                    stack.push(RuntimeOps.formatValue(RuntimeOps.resolveDynamic(
                            stack.popPyValue(), state)));
                    frame.advance();
                    break;
                case BUILD_STRING:
                    stack.push(RuntimeOps.buildString(
                            stack.popPyValuesInSourceOrder(count(instruction))));
                    frame.advance();
                    break;
                case GET_ITER:
                    stack.push(RuntimeOps.iter(RuntimeOps.resolveDynamic(
                            stack.popPyValue(), state)));
                    frame.advance();
                    break;
                case FOR_ITER:
                    forIter(frame, instruction);
                    break;

                case UNARY_OP: {
                    Operand.UnaryOperator operator =
                            ((Operand.UnaryOperatorOperand) instruction.getOperand())
                                    .getOperator();
                    stack.push(RuntimeOps.unary(operator.name(),
                            RuntimeOps.resolveDynamic(stack.popPyValue(), state)));
                    frame.advance();
                    break;
                }
                case BINARY_OP: {
                    Operand.BinaryOperator operator =
                            ((Operand.BinaryOperatorOperand) instruction.getOperand())
                                    .getOperator();
                    PyValue right = RuntimeOps.resolveDynamic(stack.popPyValue(), state);
                    PyValue left = RuntimeOps.resolveDynamic(stack.popPyValue(), state);
                    stack.push(RuntimeOps.binary(operator.name(), left, right));
                    frame.advance();
                    break;
                }
                case COMPARE_OP: {
                    Operand.CompareOperator operator =
                            ((Operand.CompareOperatorOperand) instruction.getOperand())
                                    .getOperator();
                    PyValue right = RuntimeOps.resolveDynamic(stack.popPyValue(), state);
                    PyValue left = RuntimeOps.resolveDynamic(stack.popPyValue(), state);
                    stack.push(RuntimeOps.compare(operator.name(), left, right));
                    frame.advance();
                    break;
                }

                case CALL:
                    executeCall(frame, instruction, state);
                    break;
                case MAKE_FUNCTION:
                    makeFunction(frame, instruction);
                    break;
                case BUILD_CLASS:
                    buildClass(frame, instruction, state);
                    break;
                case IMPORT_NAME:
                    importName(frame, instruction, state);
                    break;
                case IMPORT_FROM:
                    importFrom(frame, instruction, state);
                    break;
                case IMPORT_STAR:
                    importStar(frame, state);
                    break;

                case JUMP:
                    frame.jump(target(instruction));
                    break;
                case POP_JUMP_IF_FALSE:
                    conditionalPopJump(frame, instruction, false, state);
                    break;
                case POP_JUMP_IF_TRUE:
                    conditionalPopJump(frame, instruction, true, state);
                    break;
                case JUMP_IF_FALSE_OR_POP:
                    conditionalRetainingJump(frame, instruction, false, state);
                    break;
                case JUMP_IF_TRUE_OR_POP:
                    conditionalRetainingJump(frame, instruction, true, state);
                    break;
                case UNWIND_JUMP: {
                    PendingTransfer transfer = PendingTransfer.jump(
                            target(instruction),
                            anchorAt(frame.getCode(), target(instruction)),
                            instructionOffset,
                            stack.snapshot(),
                            inheritedVisited(frame, state));
                    replacePending(frame, transfer);
                    TransferCompletion completion = dispatchPending(frame, state);
                    if (completion.returned) return completion.value;
                    break;
                }
                case RETURN_VALUE: {
                    PyValue value = stack.popPyValue();
                    PendingTransfer transfer = PendingTransfer.returning(
                            value,
                            instructionOffset,
                            stack.snapshot(),
                            inheritedVisited(frame, state));
                    replacePending(frame, transfer);
                    TransferCompletion completion = dispatchPending(frame, state);
                    if (completion.returned) return completion.value;
                    break;
                }

                case RAISE: {
                    TransferCompletion completion = executeRaise(
                            frame, instruction, state, instructionStack);
                    if (completion.returned) return completion.value;
                    break;
                }
                case LOAD_CURRENT_EXCEPTION: {
                    VmRuntimeException current = currentException(frame, state);
                    if (current == null) {
                        throw RuntimeOps.error(
                                "RuntimeError", "No active exception to load");
                    }
                    stack.push(current.getRaisedValue());
                    frame.advance();
                    break;
                }
                case EXCEPTION_MATCH: {
                    PyValue typeSpec = stack.popPyValue();
                    PyValue exception = stack.popPyValue();
                    stack.push(PyBool.valueOf(exceptionMatches(
                            frame, exception, typeSpec)));
                    frame.advance();
                    break;
                }
                case BEGIN_EXCEPT:
                    beginExcept(frame, instruction, state);
                    break;
                case END_EXCEPT:
                    endExcept(frame, instruction, state);
                    break;
                case ENTER_CLEANUP: {
                    TransferCompletion completion = enterCleanup(
                            frame, instruction, state);
                    if (completion.returned) return completion.value;
                    break;
                }
                case END_CLEANUP: {
                    TransferCompletion completion = endCleanup(
                            frame, instruction, state);
                    if (completion.returned) return completion.value;
                    break;
                }
                case WITH_ENTER:
                    withEnter(frame, instruction, state);
                    break;
                case WITH_EXIT:
                    withExit(frame, instruction, state);
                    break;
                }
            } catch (VmRuntimeException failure) {
                // dispatchPending() has already attached the source entry for
                // an explicit raise/transfer escaping this same frame.  Let it
                // cross the frame boundary without manufacturing a duplicate
                // traceback entry; a caller still records its CALL site.
                if (failure.escapedTransferFrameIs(frame)) {
                    throw failure;
                }
                addTracebackEntry(frame, instructionOffset, failure);
                VmRuntimeException previous = currentException(frame, state);
                if (previous != null && previous != failure) {
                    failure.setImplicitContext(previous);
                }
                PendingTransfer transfer = PendingTransfer.exception(
                        failure,
                        instructionOffset,
                        instructionStack,
                        inheritedVisited(frame, state),
                        suspendedTransferForExceptionReplacement(frame));
                replacePending(frame, transfer);
                frame.setDispatchException(null);
                TransferCompletion completion = dispatchPending(frame, state);
                if (completion.returned) return completion.value;
            }
        }
        throw new IllegalStateException(
                "Verified code fell through beyond its instruction array");
    }

    private static void executeCall(
            Frame frame, Instruction instruction, ExecutionState state) {
        Operand.CallSpec spec = (Operand.CallSpec) instruction.getOperand();
        int argumentCount = spec.getPositionalCount() + spec.getKeywordCount();
        List<PyValue> values = frame.getOperandStack()
                .popPyValuesInSourceOrder(argumentCount);
        PyValue callable = frame.getOperandStack().popPyValue();
        List<PyValue> positional = new ArrayList<>(
                values.subList(0, spec.getPositionalCount()));
        LinkedHashMap<String, PyValue> keywords = new LinkedHashMap<>();
        for (int index = 0; index < spec.getKeywordCount(); index++) {
            keywords.put(spec.getOrderedKeywordNames().get(index),
                    values.get(spec.getPositionalCount() + index));
        }
        PyValue result = state.invoke(callable, positional, keywords);
        frame.getOperandStack().push(result);
        frame.advance();
    }

    private static void makeFunction(Frame frame, Instruction instruction) {
        Operand.MakeFunctionSpec spec =
                (Operand.MakeFunctionSpec) instruction.getOperand();
        OperandStack stack = frame.getOperandStack();
        CodeObject code = stack.popCodeRef().getCodeObject();

        List<Cell> closure = new ArrayList<>(Collections.nCopies(
                spec.getOrderedFreeVarNames().size(), (Cell) null));
        for (int index = closure.size() - 1; index >= 0; index--) {
            closure.set(index, stack.popCellRef().getCell());
        }

        LinkedHashMap<String, PyValue> annotations = new LinkedHashMap<>();
        List<String> annotationNames = spec.getOrderedAnnotationNames();
        List<PyValue> annotationValues = new ArrayList<>(Collections.nCopies(
                annotationNames.size(), (PyValue) null));
        for (int index = annotationValues.size() - 1; index >= 0; index--) {
            annotationValues.set(index, stack.popPyValue());
        }
        for (int index = 0; index < annotationNames.size(); index++) {
            annotations.put(annotationNames.get(index), annotationValues.get(index));
        }

        LinkedHashMap<String, PyValue> defaults = new LinkedHashMap<>();
        List<String> defaultNames = spec.getOrderedDefaultParameterNames();
        List<PyValue> defaultValues = new ArrayList<>(Collections.nCopies(
                defaultNames.size(), (PyValue) null));
        for (int index = defaultValues.size() - 1; index >= 0; index--) {
            defaultValues.set(index, stack.popPyValue());
        }
        for (int index = 0; index < defaultNames.size(); index++) {
            defaults.put(defaultNames.get(index), defaultValues.get(index));
        }

        stack.push(new PyFunction(
                code, frame.getGlobals(), defaults, annotations, closure));
        frame.advance();
    }

    private static void buildClass(
            Frame frame, Instruction instruction, ExecutionState state) {
        Operand.BuildClassSpec spec =
                (Operand.BuildClassSpec) instruction.getOperand();
        OperandStack stack = frame.getOperandStack();
        CodeObject code = stack.popCodeRef().getCodeObject();

        List<Cell> closure = new ArrayList<>(Collections.nCopies(
                spec.getOrderedFreeVarNames().size(), (Cell) null));
        for (int index = closure.size() - 1; index >= 0; index--) {
            closure.set(index, stack.popCellRef().getCell());
        }
        List<PyValue> bases = stack.popPyValuesInSourceOrder(spec.getBaseCount());

        Namespace classNamespace = new Namespace();
        PyValue moduleName = frame.getGlobals().find("__name__")
                .orElse(new PyString("__main__"));
        classNamespace.put("__module__", moduleName);
        classNamespace.put("__qualname__", new PyString(code.getQualifiedName()));
        state.executeClassBody(
                code, classNamespace, frame.getGlobals(), closure);

        stack.push(new PyClass(spec.getClassName(), bases, classNamespace));
        frame.advance();
    }

    private static void importName(
            Frame frame, Instruction instruction, ExecutionState state) {
        Operand.ImportSpec spec = (Operand.ImportSpec) instruction.getOperand();
        if (!Operand.isCanonicalModuleName(spec.getModuleName())) {
            throw RuntimeOps.error("ImportError",
                    "invalid module name '" + spec.getModuleName() + "'");
        }
        ModuleLoader.ImportResult result =
                spec.getResultMode() == Operand.ImportSpec.ResultMode.TOP_LEVEL
                        ? ModuleLoader.ImportResult.TOP_LEVEL
                        : ModuleLoader.ImportResult.LEAF;
        frame.getOperandStack().push(state.moduleLoader.importModule(
                spec.getModuleName(), result));
        frame.advance();
    }

    private static void importFrom(
            Frame frame, Instruction instruction, ExecutionState state) {
        PyValue value = frame.getOperandStack().peekPyValue(1);
        if (!(value instanceof PyModule)) {
            throw RuntimeOps.error("TypeError", "IMPORT_FROM expected a module, got '"
                    + value.getTypeName() + "'");
        }
        String importedName = name(frame, instruction);
        if (!Operand.isIdentifier(importedName)) {
            throw RuntimeOps.error("ImportError",
                    "invalid imported name '" + importedName + "'");
        }
        PyValue imported = state.moduleLoader.importFrom(
                (PyModule) value, importedName);
        frame.getOperandStack().push(imported);
        frame.advance();
    }

    private static void importStar(Frame frame, ExecutionState state) {
        if (frame.getCode().getKind() != CodeKind.MODULE) {
            throw RuntimeOps.error("ImportError",
                    "IMPORT_STAR is only valid at module scope");
        }
        PyValue value = frame.getOperandStack().popPyValue();
        if (!(value instanceof PyModule)) {
            throw RuntimeOps.error("TypeError", "IMPORT_STAR expected a module, got '"
                    + value.getTypeName() + "'");
        }
        state.moduleLoader.importStar((PyModule) value, frame.getLocals());
        frame.advance();
    }

    private static void loadConstant(Frame frame, int index) {
        Object constant = frame.getCode().getConstantPool().get(index);
        if (constant instanceof PyValue) {
            frame.getOperandStack().push((PyValue) constant);
        } else if (constant instanceof CodeObject) {
            frame.getOperandStack().push(new CodeRef((CodeObject) constant));
        } else {
            throw new IllegalStateException(
                    "Verified constant pool contains an unsupported value");
        }
    }

    private static void buildMap(OperandStack stack, int pairCount) {
        final int valueCount;
        try {
            valueCount = Math.multiplyExact(pairCount, 2);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("Verified BUILD_MAP count overflow", overflow);
        }
        List<PyValue> values = stack.popPyValuesInSourceOrder(valueCount);
        PyDict result = new PyDict();
        for (int i = 0; i < values.size(); i += 2) {
            result.put(values.get(i), values.get(i + 1));
        }
        stack.push(result);
    }

    private static void unpack(OperandStack stack, int count) {
        List<PyValue> values = RuntimeOps.unpack(stack.popPyValue(), count);
        // Contract: item1 is TOS, so push itemN first.
        for (int i = values.size() - 1; i >= 0; i--) {
            stack.push(values.get(i));
        }
    }

    private static void forIter(Frame frame, Instruction instruction) {
        PyValue iteratorValue = frame.getOperandStack().popPyValue();
        if (!(iteratorValue instanceof PyIterator)) {
            throw RuntimeOps.error("TypeError", "FOR_ITER expected iterator, got '"
                    + iteratorValue.getTypeName() + "'");
        }
        Optional<PyValue> next = ((PyIterator) iteratorValue).tryNext();
        if (next.isPresent()) {
            frame.getOperandStack().push(next.get());
            frame.advance();
        } else {
            frame.jump(target(instruction));
        }
    }

    private static void conditionalPopJump(
            Frame frame,
            Instruction instruction,
            boolean jumpWhenTruthy,
            ExecutionState state) {
        boolean truthy = RuntimeOps.isTruthy(
                frame.getOperandStack().popPyValue(), state);
        if (truthy == jumpWhenTruthy) {
            frame.jump(target(instruction));
        } else {
            frame.advance();
        }
    }

    private static void conditionalRetainingJump(
            Frame frame,
            Instruction instruction,
            boolean jumpWhenTruthy,
            ExecutionState state) {
        boolean truthy = RuntimeOps.isTruthy(
                frame.getOperandStack().peekPyValue(1), state);
        if (truthy == jumpWhenTruthy) {
            frame.jump(target(instruction));
        } else {
            frame.getOperandStack().popPyValue();
            frame.advance();
        }
    }

    // ------------------------------------------------------------------
    // Unified exception/cleanup transfer engine
    // ------------------------------------------------------------------

    private static TransferCompletion executeRaise(
            Frame frame,
            Instruction instruction,
            ExecutionState state,
            List<VmStackValue> instructionStack) {
        int count = count(instruction);
        int origin = frame.getInstructionPointer();
        PendingTransfer oldPending = frame.getPendingTransfer();

        if (count == 0) {
            VmRuntimeException current = currentException(frame, state);
            if (current == null) {
                current = RuntimeOps.error(
                        "RuntimeError", "No active exception to reraise");
                addTracebackEntry(frame, origin, current);
            } else if (!hasTracebackForCode(current,
                    frame.getCode().getQualifiedName())) {
                // A function called from an active handler contributes its
                // own frame, while a same-frame bare raise preserves origin.
                addTracebackEntry(frame, origin, current);
            }
            // An unmatched handler resumes the same dispatch transfer from its
            // original protected range.  A bare raise written inside cleanup
            // bytecode, however, must use its own offset so an inner try can
            // catch it while the already-visited outer cleanup stays skipped.
            int transferOrigin = oldPending != null && oldPending.isDispatching()
                    ? oldPending.getOriginOffset()
                    : origin;
            PendingTransfer transfer = PendingTransfer.exception(
                    current,
                    transferOrigin,
                    frame.getOperandStack().snapshot(),
                    inheritedVisited(frame, state),
                    suspendedTransferForExceptionReplacement(frame));
            replacePending(frame, transfer);
            frame.setDispatchException(null);
            return dispatchPending(frame, state);
        }

        PyValue causeValue = count == 2
                ? frame.getOperandStack().popPyValue()
                : null;
        PyValue raisedValue = frame.getOperandStack().popPyValue();
        VmRuntimeException failure = normalizeException(
                frame, state, raisedValue);
        VmRuntimeException previous = currentException(frame, state);

        if (count == 2) {
            if (causeValue == PyNone.INSTANCE) {
                failure.suppressContext();
            } else {
                failure.setExplicitCause(normalizeException(
                        frame, state, causeValue));
            }
        } else if (previous != null && previous != failure) {
            failure.setImplicitContext(previous);
        }

        addTracebackEntry(frame, origin, failure);
        PendingTransfer transfer = PendingTransfer.exception(
                failure,
                origin,
                frame.getOperandStack().snapshot(),
                inheritedVisited(frame, state),
                suspendedTransferForExceptionReplacement(frame));
        replacePending(frame, transfer);
        frame.setDispatchException(null);
        return dispatchPending(frame, state);
    }

    private static void beginExcept(
            Frame frame, Instruction instruction, ExecutionState state) {
        Operand.HandlerSpec spec =
                (Operand.HandlerSpec) instruction.getOperand();
        PendingTransfer pending = frame.getPendingTransfer();
        VmRuntimeException candidate = frame.getDispatchException();
        CleanupRegion cleanup = cleanupById(
                frame.getCode(), spec.getCleanupRegionId());
        if (pending == null
                || pending.getKind() != PendingTransfer.Kind.EXCEPTION
                || !pending.isDispatching()
                || candidate == null
                || pending.getException() != candidate
                || cleanup.getKind() != CleanupRegion.Kind.EXCEPT_HANDLER
                || cleanup.getHandlerId() != spec.getHandlerId()) {
            throw new IllegalStateException(
                    "Verified BEGIN_EXCEPT has inconsistent dispatch metadata");
        }

        pending.endDispatch();
        frame.setPendingTransfer(null);
        frame.setDispatchException(null);
        if (spec.getAlias() != null) {
            frame.storeResolved(spec.getAlias(), candidate.getRaisedValue());
        }
        ExceptionState exceptionState = new ExceptionState(
                frame,
                spec.getHandlerId(),
                spec.getCleanupRegionId(),
                candidate,
                spec.getAlias(),
                pending.getSuspendedTransfer());
        state.pushHandledException(exceptionState);
        frame.pushRuntimeBlock(RuntimeBlock.handler(
                spec.getCleanupRegionId(), spec.getHandlerId()));
        frame.advance();
    }

    private static void endExcept(
            Frame frame, Instruction instruction, ExecutionState state) {
        int handlerId = ((Operand.CleanupSpec) instruction.getOperand())
                .getCleanupId();
        CleanupRegion cleanup = handlerById(frame.getCode(), handlerId);
        ExceptionState handled = state.popHandledException(
                frame, handlerId, cleanup.getId());
        frame.clearResolved(handled.getAlias());
        frame.popRuntimeBlock(
                cleanup.getId(), RuntimeBlock.Kind.EXCEPT_HANDLER);
        // An exception caught while a finally/with cleanup was already
        // running is only a temporary replacement.  A normal handler exit
        // resumes the older transfer; return/jump/new-exception exits go
        // through cleanupHandledException instead and deliberately discard it.
        if (frame.getPendingTransfer() != null) {
            throw new IllegalStateException(
                    "END_EXCEPT reached with an unclaimed PendingTransfer");
        }
        frame.setPendingTransfer(handled.getSuspendedTransfer());
        frame.advance();
    }

    private static TransferCompletion enterCleanup(
            Frame frame, Instruction instruction, ExecutionState state) {
        int cleanupId = ((Operand.CleanupSpec) instruction.getOperand())
                .getCleanupId();
        CleanupRegion cleanup = cleanupById(frame.getCode(), cleanupId);
        if (cleanup.getKind() != CleanupRegion.Kind.FINALLY
                && cleanup.getKind() != CleanupRegion.Kind.WITH) {
            throw new IllegalStateException(
                    "ENTER_CLEANUP targets a state-only cleanup");
        }
        PendingTransfer suspended = frame.getPendingTransfer();
        PendingTransfer transfer = PendingTransfer.normal(
                cleanup.getNormalContinuationOffset(),
                anchorAt(frame.getCode(), cleanup.getNormalContinuationOffset()),
                frame.getInstructionPointer(),
                frame.getOperandStack().snapshot(),
                inheritedVisited(frame, state),
                suspended);
        transfer.visit(cleanupId);
        transfer.enterCleanup(cleanupId);
        frame.setPendingTransfer(transfer);
        frame.restoreStack(cleanup.getAnchor(), transfer);
        frame.jump(cleanup.getHandlerOffset());
        return TransferCompletion.NONE;
    }

    private static TransferCompletion endCleanup(
            Frame frame,
            Instruction instruction,
            ExecutionState state) {
        int cleanupId = ((Operand.CleanupSpec) instruction.getOperand())
                .getCleanupId();
        PendingTransfer pending = frame.getPendingTransfer();
        if (pending == null || pending.getActiveCleanupId() != cleanupId
                || !pending.hasVisited(cleanupId)) {
            throw new IllegalStateException(
                    "Verified END_CLEANUP has no active PendingTransfer");
        }
        pending.leaveCleanup(cleanupId);
        return dispatchPending(frame, state);
    }

    private static void withEnter(
            Frame frame, Instruction instruction, ExecutionState state) {
        Operand.WithSpec spec = (Operand.WithSpec) instruction.getOperand();
        PyValue manager = RuntimeOps.resolveDynamic(
                frame.getOperandStack().popPyValue(), state);
        try {
            PyValue exit = RuntimeOps.loadAttribute(manager, "__exit__", state);
            frame.storeTemporary(spec.getResourceSlot(), exit);
            PyValue enter = RuntimeOps.loadAttribute(manager, "__enter__", state);
            PyValue entered = state.invoke(
                    enter,
                    Collections.<PyValue>emptyList(),
                    Collections.<String, PyValue>emptyMap());
            frame.pushRuntimeBlock(RuntimeBlock.with(spec.getCleanupId()));
            frame.getOperandStack().push(entered);
            frame.advance();
        } catch (VmRuntimeException failure) {
            if (frame.isTemporaryInitialized(spec.getResourceSlot())) {
                frame.clearTemporary(spec.getResourceSlot());
            }
            throw failure;
        }
    }

    private static void withExit(
            Frame frame, Instruction instruction, ExecutionState state) {
        Operand.WithSpec spec = (Operand.WithSpec) instruction.getOperand();
        PendingTransfer pending = frame.getPendingTransfer();
        if (pending == null
                || pending.getActiveCleanupId() != spec.getCleanupId()
                || !frame.hasRuntimeBlock(
                        spec.getCleanupId(), RuntimeBlock.Kind.WITH)
                || !frame.isTemporaryInitialized(spec.getResourceSlot())) {
            throw new IllegalStateException(
                    "Verified WITH_EXIT has no active context manager");
        }

        PyValue exit = frame.loadTemporary(spec.getResourceSlot());
        frame.clearTemporary(spec.getResourceSlot());
        frame.popRuntimeBlock(spec.getCleanupId(), RuntimeBlock.Kind.WITH);

        List<PyValue> arguments = new ArrayList<>(3);
        if (pending.getKind() == PendingTransfer.Kind.EXCEPTION) {
            VmRuntimeException failure = pending.getException();
            arguments.add(exceptionTypeValue(frame, failure.getRaisedValue()));
            arguments.add(failure.getRaisedValue());
            arguments.add(new PyTracebackValue(buildTraceback(failure)));
        } else {
            arguments.add(PyNone.INSTANCE);
            arguments.add(PyNone.INSTANCE);
            arguments.add(PyNone.INSTANCE);
        }

        PyValue result = state.invoke(
                exit, arguments, Collections.<String, PyValue>emptyMap());
        if (pending.getKind() == PendingTransfer.Kind.EXCEPTION
                && RuntimeOps.isTruthy(result, state)) {
            CleanupRegion cleanup = cleanupById(
                    frame.getCode(), spec.getCleanupId());
            PendingTransfer suppressed = PendingTransfer.normal(
                    cleanup.getNormalContinuationOffset(),
                    anchorAt(frame.getCode(), cleanup.getNormalContinuationOffset()),
                    pending.getOriginOffset(),
                    pending.getOriginStack(),
                    pending.getVisitedBoundaryIds(),
                    pending.getSuspendedTransfer());
            suppressed.enterCleanup(spec.getCleanupId());
            frame.setPendingTransfer(suppressed);
            frame.setDispatchException(null);
        }
        frame.advance();
    }

    private static TransferCompletion dispatchPending(
            Frame frame, ExecutionState state) {
        while (true) {
            PendingTransfer pending = frame.getPendingTransfer();
            if (pending == null) {
                throw new IllegalStateException(
                        "Unified unwinder lost its PendingTransfer");
            }
            RuntimeBoundary boundary = selectBoundary(frame, pending);
            if (boundary == null) {
                switch (pending.getKind()) {
                    case NORMAL:
                    case JUMP:
                        StackAnchor targetAnchor = pending.getTargetAnchor();
                        if (targetAnchor == null) {
                            throw new IllegalStateException(
                                    "Verified transfer target has no StackAnchor");
                        }
                        frame.getOperandStack().restorePrefix(
                                pending.getOriginStack(), targetAnchor.getDepth());
                        frame.setPendingTransfer(
                                pending.getKind() == PendingTransfer.Kind.NORMAL
                                        ? pending.getSuspendedTransfer()
                                        : null);
                        frame.setDispatchException(null);
                        frame.jump(pending.getTargetOffset());
                        return TransferCompletion.NONE;
                    case RETURN:
                        frame.setPendingTransfer(null);
                        frame.setDispatchException(null);
                        return TransferCompletion.returned(
                                pending.getReturnValue());
                    case EXCEPTION:
                        frame.setPendingTransfer(null);
                        frame.setDispatchException(null);
                        pending.getException().markEscapedTransferFrame(frame);
                        throw pending.getException();
                    default:
                        throw new AssertionError(
                                "Unhandled transfer " + pending.getKind());
                }
            }

            pending.visit(boundary.id());
            frame.restoreStack(boundary.anchor(), pending);
            if (boundary.exceptionRegion != null) {
                // A replacement exception only resumes an older transfer when
                // it is caught while that older cleanup handler is still
                // executing.  Dispatch outside the handler has already
                // abandoned the interrupted cleanup; retaining it would make
                // END_EXCEPT incorrectly resurrect the original exception,
                // return, or jump.
                pending.retainSuspendedTransfer(
                        suspendedTransferForHandler(
                                frame,
                                pending.getSuspendedTransfer(),
                                boundary.exceptionRegion.getHandlerOffset()));
                pending.beginDispatch();
                frame.setDispatchException(pending.getException());
                frame.jump(boundary.exceptionRegion.getHandlerOffset());
                return TransferCompletion.NONE;
            }

            CleanupRegion cleanup = boundary.cleanupRegion;
            switch (cleanup.getKind()) {
                case TEMP_CLEAR:
                    for (Integer slot : cleanup.getResourceSlots()) {
                        if (frame.isTemporaryInitialized(slot)) {
                            frame.clearTemporary(slot);
                        }
                    }
                    break;
                case EXCEPT_HANDLER:
                    cleanupHandledException(frame, state, cleanup);
                    break;
                case FINALLY:
                case WITH:
                    pending.enterCleanup(cleanup.getId());
                    frame.jump(cleanup.getHandlerOffset());
                    return TransferCompletion.NONE;
                default:
                    throw new AssertionError(
                            "Unhandled cleanup " + cleanup.getKind());
            }
        }
    }

    private static RuntimeBoundary selectBoundary(
            Frame frame, PendingTransfer pending) {
        RuntimeBoundary selected = null;
        for (ExceptionRegion region : frame.getCode().getExceptionRegions()) {
            if (pending.getKind() != PendingTransfer.Kind.EXCEPTION
                    || pending.hasVisited(region.getId())
                    || !contains(region, pending.getOriginOffset())) {
                continue;
            }
            RuntimeBoundary candidate = new RuntimeBoundary(region);
            selected = deeper(selected, candidate);
        }
        for (CleanupRegion region : frame.getCode().getCleanupRegions()) {
            if (pending.hasVisited(region.getId())
                    || !region.contains(pending.getOriginOffset())
                    || destinationStaysInside(region, pending)
                    || !cleanupIsActive(frame, region)) {
                continue;
            }
            RuntimeBoundary candidate = new RuntimeBoundary(region);
            selected = deeper(selected, candidate);
        }
        return selected;
    }

    private static RuntimeBoundary deeper(
            RuntimeBoundary selected, RuntimeBoundary candidate) {
        if (selected == null || candidate.depth() > selected.depth()) {
            return candidate;
        }
        if (candidate.depth() == selected.depth()
                && candidate.id() != selected.id()) {
            throw new IllegalStateException(
                    "Verified bytecode has ambiguous active boundaries");
        }
        return selected;
    }

    private static boolean cleanupIsActive(
            Frame frame, CleanupRegion cleanup) {
        switch (cleanup.getKind()) {
            case FINALLY:
                return true;
            case WITH:
                return frame.hasRuntimeBlock(
                        cleanup.getId(), RuntimeBlock.Kind.WITH);
            case EXCEPT_HANDLER:
                return frame.hasRuntimeBlock(
                        cleanup.getId(), RuntimeBlock.Kind.EXCEPT_HANDLER);
            case TEMP_CLEAR:
                for (Integer slot : cleanup.getResourceSlots()) {
                    if (frame.isTemporaryInitialized(slot)) return true;
                }
                return false;
            default:
                throw new AssertionError("Unhandled cleanup kind");
        }
    }

    private static boolean destinationStaysInside(
            CleanupRegion cleanup, PendingTransfer pending) {
        return (pending.getKind() == PendingTransfer.Kind.NORMAL
                || pending.getKind() == PendingTransfer.Kind.JUMP)
                && cleanup.contains(pending.getTargetOffset());
    }

    private static void cleanupHandledException(
            Frame frame, ExecutionState state, CleanupRegion cleanup) {
        ExceptionState handled = state.popHandledException(
                frame, cleanup.getHandlerId(), cleanup.getId());
        frame.clearResolved(handled.getAlias());
        frame.popRuntimeBlock(
                cleanup.getId(), RuntimeBlock.Kind.EXCEPT_HANDLER);
    }

    private static boolean contains(ExceptionRegion region, int offset) {
        return offset >= region.getStartOffset()
                && offset < region.getEndOffset();
    }

    private static Set<Integer> inheritedVisited(
            Frame frame, ExecutionState state) {
        PendingTransfer pending = frame.getPendingTransfer();
        if (pending != null) {
            return pending.getVisitedBoundaryIds();
        }
        return state.inheritedHandledBoundaryIds(frame);
    }

    /**
     * A failure raised while cleanup bytecode is running can be caught locally
     * without cancelling the older return/jump/exception which entered that
     * cleanup.  Preserve exactly that active transfer for BEGIN/END_EXCEPT.
     */
    private static PendingTransfer suspendedTransferForExceptionReplacement(
            Frame frame) {
        PendingTransfer pending = frame.getPendingTransfer();
        if (pending == null) return null;
        if (pending.getActiveCleanupId() >= 0) {
            CleanupRegion cleanup = cleanupById(
                    frame.getCode(), pending.getActiveCleanupId());
            if (cleanup.getKind() == CleanupRegion.Kind.WITH
                    && !frame.hasRuntimeBlock(
                            cleanup.getId(), RuntimeBlock.Kind.WITH)) {
                // __exit__ is invoked only after the manager resource/block is
                // retired.  If it raises, that new exception replaces the
                // transfer entering this WITH; only an enclosing suspended
                // cleanup may still be resumed if the replacement is caught.
                return pending.getSuspendedTransfer();
            }
            return pending;
        }
        // A new failure while evaluating an except type replaces the current
        // dispatch candidate, but must still retain any older cleanup transfer
        // which that candidate had temporarily superseded.
        return pending.isDispatching()
                ? pending.getSuspendedTransfer() : null;
    }

    /** Returns the first suspended cleanup which still contains the handler. */
    private static PendingTransfer suspendedTransferForHandler(
            Frame frame,
            PendingTransfer suspended,
            int handlerOffset) {
        PendingTransfer cursor = suspended;
        while (cursor != null) {
            int cleanupId = cursor.getActiveCleanupId();
            if (cleanupId >= 0) {
                CleanupRegion cleanup = cleanupById(frame.getCode(), cleanupId);
                boolean handlerIsInside;
                if (cleanup.getKind() == CleanupRegion.Kind.EXCEPT_HANDLER) {
                    handlerIsInside = cleanup.contains(handlerOffset);
                } else {
                    handlerIsInside = cleanup.getHandlerOffset() >= 0
                            && cleanup.getNormalContinuationOffset()
                                    > cleanup.getHandlerOffset()
                            && handlerOffset >= cleanup.getHandlerOffset()
                            && handlerOffset
                                    < cleanup.getNormalContinuationOffset();
                }
                if (handlerIsInside) return cursor;
            }
            cursor = cursor.getSuspendedTransfer();
        }
        return null;
    }

    private static void replacePending(
            Frame frame, PendingTransfer replacement) {
        PendingTransfer old = frame.getPendingTransfer();
        if (old != null && old.isDispatching()) old.endDispatch();
        frame.setPendingTransfer(replacement);
    }

    private static StackAnchor anchorAt(CodeObject code, int offset) {
        for (StackAnchor anchor : code.getStackAnchors()) {
            if (anchor.getOffset() == offset) return anchor;
        }
        return null;
    }

    private static CleanupRegion cleanupById(CodeObject code, int id) {
        for (CleanupRegion cleanup : code.getCleanupRegions()) {
            if (cleanup.getId() == id) return cleanup;
        }
        throw new IllegalStateException(
                "Verified bytecode references unknown cleanup " + id);
    }

    private static CleanupRegion handlerById(CodeObject code, int handlerId) {
        for (CleanupRegion cleanup : code.getCleanupRegions()) {
            if (cleanup.getKind() == CleanupRegion.Kind.EXCEPT_HANDLER
                    && cleanup.getHandlerId() == handlerId) {
                return cleanup;
            }
        }
        throw new IllegalStateException(
                "Verified bytecode references unknown handler " + handlerId);
    }

    private static VmRuntimeException currentException(
            Frame frame, ExecutionState state) {
        VmRuntimeException active = state.findActiveFrameException(frame);
        if (active != null) return active;
        ExceptionState handled = state.peekHandledException();
        return handled == null ? null : handled.getFailure();
    }

    private static VmRuntimeException normalizeException(
            Frame frame, ExecutionState state, PyValue value) {
        PyValue candidate = RuntimeOps.resolveDynamic(
                Objects.requireNonNull(value, "exception value"), state);
        PyType baseException = builtinExceptionBase(frame);
        if (candidate instanceof PyType) {
            PyType type = (PyType) candidate;
            if (!type.isSubclassOf(baseException)) {
                throw RuntimeOps.error("TypeError",
                        "exceptions must derive from BaseException");
            }
            candidate = state.invoke(
                    candidate,
                    Collections.<PyValue>emptyList(),
                    Collections.<String, PyValue>emptyMap());
        }

        if (candidate instanceof PyBaseException) {
            return new VmRuntimeException(
                    candidate, (PyBaseException) candidate);
        }
        if (candidate instanceof PyInstance
                && ((PyInstance) candidate).isInstanceOf(baseException)) {
            return new VmRuntimeException(
                    candidate,
                    new PyBaseException(candidate.getTypeName(), ""));
        }
        throw RuntimeOps.error("TypeError",
                "exceptions must derive from BaseException");
    }

    private static boolean exceptionMatches(
            Frame frame, PyValue exception, PyValue typeSpec) {
        if (typeSpec instanceof PyTuple) {
            for (PyValue element : ((PyTuple) typeSpec).getElements()) {
                if (exceptionMatches(frame, exception, element)) return true;
            }
            return false;
        }
        if (!(typeSpec instanceof PyType)
                || !((PyType) typeSpec).isSubclassOf(
                        builtinExceptionBase(frame))) {
            throw RuntimeOps.error(
                    "TypeError",
                    "catching classes that do not inherit from BaseException "
                            + "is not allowed");
        }
        PyType candidate = (PyType) typeSpec;
        if (exception instanceof PyInstance) {
            return ((PyInstance) exception).isInstanceOf(candidate);
        }
        if (exception instanceof PyBaseException) {
            PyValue actual = frame.getBuiltins().find(
                    ((PyBaseException) exception).getExceptionTypeName())
                    .orElse(null);
            return actual instanceof PyType
                    && ((PyType) actual).isSubclassOf(candidate);
        }
        throw new IllegalStateException(
                "Pending exception contains a non-exception value");
    }

    private static PyValue exceptionTypeValue(Frame frame, PyValue exception) {
        if (exception instanceof PyInstance) {
            return ((PyInstance) exception).getPythonClass();
        }
        if (exception instanceof PyBaseException) {
            return frame.getBuiltins().find(
                    ((PyBaseException) exception).getExceptionTypeName())
                    .orElseThrow(() -> new IllegalStateException(
                            "Exception type is absent from builtins"));
        }
        throw new IllegalStateException("Invalid exception payload");
    }

    private static PyType builtinExceptionBase(Frame frame) {
        PyValue value = frame.getBuiltins().find("BaseException")
                .orElseThrow(() -> new IllegalStateException(
                        "Mandatory BaseException builtin is absent"));
        if (!(value instanceof PyType)) {
            throw new IllegalStateException(
                    "BaseException builtin is not a type");
        }
        return (PyType) value;
    }

    private static boolean hasTracebackForCode(
            VmRuntimeException failure, String codeName) {
        for (VmTraceback.Entry entry : failure.getTracebackEntries()) {
            if (entry.getCodeName().equals(codeName)) return true;
        }
        return false;
    }

    private static final class TransferCompletion {
        private static final TransferCompletion NONE =
                new TransferCompletion(false, null);
        private final boolean returned;
        private final PyValue value;
        private TransferCompletion(boolean returned, PyValue value) {
            this.returned = returned;
            this.value = value;
        }
        private static TransferCompletion returned(PyValue value) {
            return new TransferCompletion(
                    true, Objects.requireNonNull(value, "return value"));
        }
    }

    private static final class RuntimeBoundary {
        private final ExceptionRegion exceptionRegion;
        private final CleanupRegion cleanupRegion;
        private RuntimeBoundary(ExceptionRegion region) {
            exceptionRegion = Objects.requireNonNull(region, "region");
            cleanupRegion = null;
        }
        private RuntimeBoundary(CleanupRegion region) {
            exceptionRegion = null;
            cleanupRegion = Objects.requireNonNull(region, "region");
        }
        private int id() {
            return exceptionRegion != null
                    ? exceptionRegion.getId() : cleanupRegion.getId();
        }
        private int depth() {
            return exceptionRegion != null
                    ? exceptionRegion.getNestingDepth()
                    : cleanupRegion.getNestingDepth();
        }
        private StackAnchor anchor() {
            return exceptionRegion != null
                    ? exceptionRegion.getAnchor() : cleanupRegion.getAnchor();
        }
    }

    private static int indexed(Instruction instruction) {
        return ((Operand.IndexedOperand) instruction.getOperand()).getIndex();
    }

    private static int count(Instruction instruction) {
        return ((Operand.CountOperand) instruction.getOperand()).getCount();
    }

    private static int target(Instruction instruction) {
        return ((Operand.JumpOperand) instruction.getOperand()).getTargetOffset();
    }

    private static String name(Frame frame, Instruction instruction) {
        return frame.getCode().getNamePool().get(indexed(instruction));
    }

    private static void addTracebackEntry(
            Frame frame, int offset, VmRuntimeException failure) {
        SourceSpan span = SourceSpan.UNKNOWN;
        if (offset >= 0 && offset < frame.getCode().getSourceMap().size()) {
            InstructionLocation location = frame.getCode().getSourceMap().get(offset);
            span = location.getSpan();
        }
        failure.addTracebackEntry(new VmTraceback.Entry(
                frame.getCode().getQualifiedName(), span, Math.max(0, offset)));
    }

    private static VmTraceback buildTraceback(VmRuntimeException failure) {
        return buildTraceback(failure,
                new IdentityHashMap<VmRuntimeException, Boolean>());
    }

    private static VmTraceback buildTraceback(
            VmRuntimeException failure,
            IdentityHashMap<VmRuntimeException, Boolean> seen) {
        if (failure == null || seen.put(failure, Boolean.TRUE) != null) {
            return null;
        }
        List<VmTraceback.Entry> entries = new ArrayList<>(
                failure.getTracebackEntries());
        Collections.reverse(entries);
        VmTraceback cause = buildTraceback(failure.getExplicitCause(), seen);
        VmTraceback context = buildTraceback(
                failure.getImplicitContext(), seen);
        return new VmTraceback(
                entries,
                failure.getExceptionValue(),
                cause,
                context,
                failure.isContextSuppressed());
    }

    /** Per-execution services shared by every Python and native call frame. */
    private static final class ExecutionState implements VmCallContext {
        private final ExecutionLimits.Meter meter;
        private final Namespace builtins;
        private final int maximumOperandStackSize;
        private final ModuleLoader moduleLoader;
        private final RuntimeServices runtimeServices;
        private final Deque<Frame> frames = new ArrayDeque<>();
        private final Deque<ExceptionState> handledExceptions =
                new ArrayDeque<>();
        private final StringBuilder stdout = new StringBuilder();

        private ExecutionState(
                ExecutionLimits.Meter meter,
                Namespace builtins,
                int maximumOperandStackSize,
                ModuleRegistry moduleRegistry,
                VmCapabilities capabilities) {
            this.meter = Objects.requireNonNull(meter, "meter");
            this.builtins = Objects.requireNonNull(builtins, "builtins");
            this.maximumOperandStackSize = maximumOperandStackSize;
            this.moduleLoader = new ModuleLoader(
                    Objects.requireNonNull(moduleRegistry, "moduleRegistry"),
                    (bytecode, module, loader) ->
                            executeImportedModule(bytecode, module));
            this.runtimeServices = new RuntimeServices(
                    Objects.requireNonNull(capabilities, "capabilities"));
        }

        private void pushFrame(Frame frame) {
            frames.addLast(Objects.requireNonNull(frame, "frame"));
        }

        private void popFrame(Frame expected) {
            Frame actual = frames.pollLast();
            if (actual != expected) {
                throw new IllegalStateException("VM call-frame stack corruption");
            }
            ExceptionState top = handledExceptions.peekLast();
            if (top != null && top.getOwnerFrame() == expected) {
                throw new IllegalStateException(
                        "Handled-exception state leaked out of a frame");
            }
        }

        private void pushHandledException(ExceptionState state) {
            handledExceptions.addLast(
                    Objects.requireNonNull(state, "exception state"));
        }

        private ExceptionState peekHandledException() {
            return handledExceptions.peekLast();
        }

        /**
         * A handler may be executing in a cleanup whose original transfer is
         * parked in one or more outer ExceptionState records.  Abrupt control
         * flow from that handler must inherit every visited boundary so it
         * cannot enter an already-running finally/with a second time.
         */
        private Set<Integer> inheritedHandledBoundaryIds(Frame frame) {
            LinkedHashSet<Integer> ids = new LinkedHashSet<>();
            for (java.util.Iterator<ExceptionState> iterator =
                    handledExceptions.descendingIterator(); iterator.hasNext();) {
                ExceptionState handled = iterator.next();
                if (handled.getOwnerFrame() != frame) continue;
                PendingTransfer suspended = handled.getSuspendedTransfer();
                while (suspended != null) {
                    ids.addAll(suspended.getVisitedBoundaryIds());
                    suspended = suspended.getSuspendedTransfer();
                }
            }
            return ids.isEmpty()
                    ? Collections.<Integer>emptySet()
                    : Collections.unmodifiableSet(ids);
        }

        /**
         * Finds a propagating or dispatching exception from the currently
         * executing frame outward.  This is deliberately separate from the
         * handled-exception deque: a function called while its caller runs an
         * exception-driven finally (or evaluates an except type) must still be
         * able to execute a valid bare raise.
         */
        private VmRuntimeException findActiveFrameException(Frame currentFrame) {
            boolean foundCurrent = false;
            for (java.util.Iterator<Frame> iterator = frames.descendingIterator();
                    iterator.hasNext();) {
                Frame candidate = iterator.next();
                if (!foundCurrent) {
                    if (candidate != currentFrame) {
                        continue;
                    }
                    foundCurrent = true;
                }
                PendingTransfer pending = candidate.getPendingTransfer();
                if (pending != null
                        && pending.getKind() == PendingTransfer.Kind.EXCEPTION) {
                    return pending.getException();
                }
                VmRuntimeException dispatch = candidate.getDispatchException();
                if (dispatch != null) {
                    return dispatch;
                }
            }
            if (!foundCurrent) {
                throw new IllegalStateException(
                        "Current frame is absent from the VM call stack");
            }
            return null;
        }

        private ExceptionState popHandledException(
                Frame frame, int handlerId, int cleanupRegionId) {
            ExceptionState state = handledExceptions.peekLast();
            if (state == null
                    || state.getOwnerFrame() != frame
                    || state.getHandlerId() != handlerId
                    || state.getCleanupRegionId() != cleanupRegionId) {
                throw new IllegalStateException(
                        "Dynamic handled-exception stack corruption");
            }
            handledExceptions.removeLast();
            return state;
        }

        @Override
        public PyValue invoke(
                PyValue callable,
                List<PyValue> positionalArguments,
                Map<String, PyValue> keywordArguments) {
            Objects.requireNonNull(callable, "callable");
            Objects.requireNonNull(positionalArguments, "positionalArguments");
            Objects.requireNonNull(keywordArguments, "keywordArguments");
            PyValue target = RuntimeOps.resolveDynamic(callable, this);
            if (!(target instanceof PyCallable)) {
                throw RuntimeOps.error("TypeError", "'" + target.getTypeName()
                        + "' object is not callable");
            }
            List<PyValue> positional = Collections.unmodifiableList(
                    new ArrayList<>(positionalArguments));
            Map<String, PyValue> keywords = Collections.unmodifiableMap(
                    new LinkedHashMap<>(keywordArguments));
            meter.enterCall();
            try {
                return Objects.requireNonNull(
                        ((PyCallable) target).call(this, positional, keywords),
                        "call result");
            } finally {
                meter.exitCall();
            }
        }

        @Override
        public PyValue executeFunction(
                PyFunction function,
                List<PyValue> positionalArguments,
                Map<String, PyValue> keywordArguments) {
            Map<String, PyValue> bound = CallBinder.bind(
                    function, positionalArguments, keywordArguments);
            Frame frame = Frame.function(
                    function.getCode(), function.getGlobals(), builtins,
                    function.getClosure(),
                    maximumOperandStackSize);
            frame.bindArguments(bound);
            pushFrame(frame);
            try {
                return executeFrame(frame, this);
            } finally {
                popFrame(frame);
            }
        }

        private void executeClassBody(
                CodeObject code,
                Namespace classNamespace,
                Namespace globals,
                List<Cell> closure) {
            Frame frame = Frame.classBody(
                    code, classNamespace, globals, builtins, closure,
                    maximumOperandStackSize);
            meter.enterCall();
            pushFrame(frame);
            try {
                executeFrame(frame, this);
            } finally {
                popFrame(frame);
                meter.exitCall();
            }
        }

        private void executeImportedModule(
                VerifiedBytecodeModule verified, PyModule module) {
            BytecodeModule bytecodeModule = requireExecutableModule(verified);
            if (!bytecodeModule.getModuleName().equals(module.getCanonicalName())) {
                throw new IllegalArgumentException(
                        "Imported bytecode name does not match its module object");
            }
            Frame frame = Frame.module(
                    bytecodeModule.getRootCode(), module.getNamespace(), builtins,
                    maximumOperandStackSize);
            meter.enterCall();
            pushFrame(frame);
            try {
                executeFrame(frame, this);
            } finally {
                popFrame(frame);
                meter.exitCall();
            }
        }

        @Override
        public Namespace getCurrentGlobals() {
            return currentFrame().getGlobals();
        }

        @Override
        public Namespace getCurrentLocals() {
            return currentFrame().snapshotVisibleLocals();
        }

        @Override
        public void writeStdout(String text) {
            stdout.append(Objects.requireNonNull(text, "text"));
        }

        private String getStdout() {
            return stdout.toString();
        }

        @Override
        public RuntimeServices getRuntimeServices() {
            return runtimeServices;
        }

        private Frame currentFrame() {
            Frame frame = frames.peekLast();
            if (frame == null) {
                throw new IllegalStateException("No active VM frame");
            }
            return frame;
        }
    }
}
