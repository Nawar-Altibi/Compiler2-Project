package compilers.flask.vm;

import compilers.flask.codegen.analysis.BindingKind;
import compilers.flask.codegen.analysis.ResolvedName;
import compilers.flask.codegen.bytecode.CodeObject;
import compilers.flask.codegen.bytecode.Instruction;
import compilers.flask.codegen.bytecode.StackAnchor;
import compilers.flask.vm.values.PyValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Mutable execution state owned by one verified code object invocation. */
public final class Frame {
    private final CodeObject code;
    private final OperandStack operandStack;
    private final Namespace locals;
    private final Namespace globals;
    private final Namespace builtins;
    private final PyValue[] fastLocals;
    private final Cell[] derefCells;
    private final PyValue[] temporarySlots;
    private final Deque<RuntimeBlock> runtimeBlocks = new ArrayDeque<>();
    private PendingTransfer pendingTransfer;
    private VmRuntimeException dispatchException;
    private int instructionPointer;

    public Frame(
            CodeObject code,
            Namespace locals,
            Namespace globals,
            Namespace builtins,
            List<Cell> freeCells,
            int maximumOperandStackSize) {
        this.code = Objects.requireNonNull(code, "code");
        if (!code.isVerified()) {
            throw new IllegalArgumentException("Frame requires a verified CodeObject");
        }
        this.locals = Objects.requireNonNull(locals, "locals");
        this.globals = Objects.requireNonNull(globals, "globals");
        this.builtins = Objects.requireNonNull(builtins, "builtins");
        this.operandStack = new OperandStack(maximumOperandStackSize);
        this.fastLocals = new PyValue[code.getFastLocalNames().size()];
        this.temporarySlots = new PyValue[code.getTemporarySlotCount()];

        List<Cell> suppliedFreeCells = freeCells == null
                ? Collections.<Cell>emptyList()
                : new ArrayList<>(freeCells);
        if (suppliedFreeCells.size() != code.getFreeVariableNames().size()) {
            throw new IllegalArgumentException(
                    "Free-cell count does not match CodeObject free-variable table");
        }
        int cellCount = code.getCellVariableNames().size();
        this.derefCells = new Cell[cellCount + suppliedFreeCells.size()];
        for (int i = 0; i < cellCount; i++) {
            derefCells[i] = new Cell();
        }
        for (int i = 0; i < suppliedFreeCells.size(); i++) {
            derefCells[cellCount + i] = Objects.requireNonNull(
                    suppliedFreeCells.get(i), "free cell");
        }
    }

    public static Frame module(
            CodeObject code,
            Namespace globals,
            Namespace builtins,
            int maximumOperandStackSize) {
        return new Frame(code, globals, globals, builtins,
                Collections.<Cell>emptyList(), maximumOperandStackSize);
    }

    public static Frame function(
            CodeObject code,
            Namespace globals,
            Namespace builtins,
            List<Cell> freeCells,
            int maximumOperandStackSize) {
        return new Frame(code, new Namespace(), globals, builtins,
                freeCells, maximumOperandStackSize);
    }

    public static Frame classBody(
            CodeObject code,
            Namespace classNamespace,
            Namespace globals,
            Namespace builtins,
            List<Cell> freeCells,
            int maximumOperandStackSize) {
        return new Frame(code, classNamespace, globals, builtins,
                freeCells, maximumOperandStackSize);
    }

    public CodeObject getCode() {
        return code;
    }

    public OperandStack getOperandStack() {
        return operandStack;
    }

    public Namespace getLocals() {
        return locals;
    }

    public Namespace getGlobals() {
        return globals;
    }

    public Namespace getBuiltins() {
        return builtins;
    }

    public PendingTransfer getPendingTransfer() { return pendingTransfer; }
    public void setPendingTransfer(PendingTransfer transfer) {
        pendingTransfer = transfer;
    }
    public VmRuntimeException getDispatchException() { return dispatchException; }
    public void setDispatchException(VmRuntimeException failure) {
        dispatchException = failure;
    }

    public void pushRuntimeBlock(RuntimeBlock block) {
        runtimeBlocks.addLast(Objects.requireNonNull(block, "block"));
    }

    public boolean hasRuntimeBlock(int boundaryId, RuntimeBlock.Kind kind) {
        for (RuntimeBlock block : runtimeBlocks) {
            if (block.getBoundaryId() == boundaryId && block.getKind() == kind) {
                return true;
            }
        }
        return false;
    }

    public RuntimeBlock popRuntimeBlock(
            int boundaryId, RuntimeBlock.Kind kind) {
        RuntimeBlock top = runtimeBlocks.peekLast();
        if (top == null || top.getBoundaryId() != boundaryId
                || top.getKind() != kind) {
            throw new IllegalStateException("Runtime cleanup-block stack corruption");
        }
        return runtimeBlocks.removeLast();
    }

    public boolean hasRuntimeBlocks() { return !runtimeBlocks.isEmpty(); }

    public int getInstructionPointer() {
        return instructionPointer;
    }

    public boolean hasInstruction() {
        return instructionPointer >= 0
                && instructionPointer < code.getInstructions().size();
    }

    public Instruction currentInstruction() {
        if (!hasInstruction()) {
            throw new IllegalStateException(
                    "Instruction pointer is outside the verified code array: "
                            + instructionPointer);
        }
        return code.getInstructions().get(instructionPointer);
    }

    public void advance() {
        instructionPointer++;
    }

    public void jump(int targetOffset) {
        if (targetOffset < 0 || targetOffset >= code.getInstructions().size()) {
            throw new IllegalStateException(
                    "Verified bytecode jumped outside its code object: " + targetOffset);
        }
        instructionPointer = targetOffset;
    }

    public PyValue loadName(String name) {
        Optional<PyValue> value = locals.find(name);
        if (!value.isPresent() && locals != globals) {
            value = globals.find(name);
        }
        if (!value.isPresent()) {
            value = builtins.find(name);
        }
        return value.orElseThrow(() -> RuntimeOps.error(
                "NameError", "name '" + name + "' is not defined"));
    }

    public void storeName(String name, PyValue value) {
        locals.put(name, value);
    }

    public void deleteName(String name) {
        if (!locals.delete(name)) {
            throw RuntimeOps.error("NameError", "name '" + name + "' is not defined");
        }
    }

    public PyValue loadGlobal(String name) {
        Optional<PyValue> value = globals.find(name);
        if (!value.isPresent()) {
            value = builtins.find(name);
        }
        return value.orElseThrow(() -> RuntimeOps.error(
                "NameError", "name '" + name + "' is not defined"));
    }

    public void storeGlobal(String name, PyValue value) {
        globals.put(name, value);
    }

    public void deleteGlobal(String name) {
        if (!globals.delete(name)) {
            throw RuntimeOps.error("NameError", "name '" + name + "' is not defined");
        }
    }

    public PyValue loadFast(int slot) {
        requireFastSlot(slot);
        PyValue value = fastLocals[slot];
        if (value == null) {
            throw RuntimeOps.error("UnboundLocalError", "local variable '"
                    + code.getFastLocalNames().get(slot)
                    + "' referenced before assignment");
        }
        return value;
    }

    public void storeFast(int slot, PyValue value) {
        requireFastSlot(slot);
        fastLocals[slot] = Objects.requireNonNull(value, "value");
    }

    public void deleteFast(int slot) {
        requireFastSlot(slot);
        if (fastLocals[slot] == null) {
            throw RuntimeOps.error("UnboundLocalError", "local variable '"
                    + code.getFastLocalNames().get(slot)
                    + "' referenced before assignment");
        }
        fastLocals[slot] = null;
    }

    public PyValue loadDeref(int slot) {
        Cell cell = getDerefCell(slot);
        return cell.find().orElseThrow(() -> RuntimeOps.error(
                "UnboundLocalError", "free or cell variable '"
                        + derefName(slot) + "' referenced before assignment"));
    }

    public void storeDeref(int slot, PyValue value) {
        getDerefCell(slot).set(value);
    }

    public void deleteDeref(int slot) {
        Cell cell = getDerefCell(slot);
        if (!cell.isBound()) {
            throw RuntimeOps.error("UnboundLocalError", "free or cell variable '"
                    + derefName(slot) + "' referenced before assignment");
        }
        cell.clear();
    }

    public Cell getDerefCell(int slot) {
        if (slot < 0 || slot >= derefCells.length) {
            throw new IllegalStateException("Verified bytecode used invalid deref slot " + slot);
        }
        return derefCells[slot];
    }

    public PyValue loadTemporary(int slot) {
        requireTemporarySlot(slot);
        PyValue value = temporarySlots[slot];
        if (value == null) {
            throw new IllegalStateException(
                    "Verified bytecode loaded uninitialized temporary slot " + slot);
        }
        return value;
    }

    public void storeTemporary(int slot, PyValue value) {
        requireTemporarySlot(slot);
        temporarySlots[slot] = Objects.requireNonNull(value, "value");
    }

    public void clearTemporary(int slot) {
        requireTemporarySlot(slot);
        temporarySlots[slot] = null;
    }

    public void restoreStack(StackAnchor anchor, PendingTransfer transfer) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(transfer, "transfer");
        operandStack.restorePrefix(
                transfer.getOriginStack(), anchor.getDepth());
    }

    /** Stores an already-resolved synthetic binding such as an except alias. */
    public void storeResolved(ResolvedName resolved, PyValue value) {
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(value, "value");
        BindingKind kind = resolved.getKind();
        switch (kind) {
            case NAME: storeName(resolved.getName(), value); return;
            case GLOBAL: storeGlobal(resolved.getName(), value); return;
            case FAST: storeFast(resolved.getSlotIndex(), value); return;
            case DEREF: storeDeref(resolved.getSlotIndex(), value); return;
            default: throw new AssertionError("Unhandled binding kind " + kind);
        }
    }

    /** Idempotently clears an except alias even if handler code deleted it. */
    public void clearResolved(ResolvedName resolved) {
        if (resolved == null) return;
        BindingKind kind = resolved.getKind();
        switch (kind) {
            case NAME: locals.delete(resolved.getName()); return;
            case GLOBAL: globals.delete(resolved.getName()); return;
            case FAST:
                requireFastSlot(resolved.getSlotIndex());
                fastLocals[resolved.getSlotIndex()] = null;
                return;
            case DEREF:
                getDerefCell(resolved.getSlotIndex()).clear();
                return;
            default: throw new AssertionError("Unhandled binding kind " + kind);
        }
    }

    /** Installs already validated arguments into fast or captured parameter slots. */
    public void bindArguments(Map<String, PyValue> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        for (String parameter : code.getSignature().getParameterNames()) {
            PyValue value = arguments.get(parameter);
            if (value == null) {
                throw new IllegalArgumentException(
                        "Missing bound value for parameter " + parameter);
            }
            int fast = code.getFastLocalNames().indexOf(parameter);
            if (fast >= 0) {
                storeFast(fast, value);
                continue;
            }
            int cell = code.getCellVariableNames().indexOf(parameter);
            if (cell >= 0) {
                storeDeref(cell, value);
                continue;
            }
            throw new IllegalStateException(
                    "Verified function parameter has no runtime slot: " + parameter);
        }
    }

    public Namespace snapshotVisibleLocals() {
        Namespace result = new Namespace(locals.snapshot());
        for (int index = 0; index < fastLocals.length; index++) {
            if (fastLocals[index] != null) {
                result.put(code.getFastLocalNames().get(index), fastLocals[index]);
            }
        }
        for (int index = 0; index < code.getCellVariableNames().size(); index++) {
            Optional<PyValue> value = derefCells[index].find();
            if (value.isPresent()) {
                result.put(code.getCellVariableNames().get(index), value.get());
            }
        }
        return result;
    }

    public boolean isTemporaryInitialized(int slot) {
        requireTemporarySlot(slot);
        return temporarySlots[slot] != null;
    }

    private void requireFastSlot(int slot) {
        if (slot < 0 || slot >= fastLocals.length) {
            throw new IllegalStateException("Verified bytecode used invalid fast slot " + slot);
        }
    }

    private void requireTemporarySlot(int slot) {
        if (slot < 0 || slot >= temporarySlots.length) {
            throw new IllegalStateException("Verified bytecode used invalid temp slot " + slot);
        }
    }

    private String derefName(int slot) {
        int cells = code.getCellVariableNames().size();
        if (slot < cells) {
            return code.getCellVariableNames().get(slot);
        }
        return code.getFreeVariableNames().get(slot - cells);
    }
}
