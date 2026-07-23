package compilers.flask.vm;

import compilers.flask.vm.values.PyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Bounded operand stack containing only the closed {@link VmStackValue} type. */
public final class OperandStack {
    private final int maximumSize;
    private final List<VmStackValue> values = new ArrayList<>();

    public OperandStack(int maximumSize) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("Maximum operand-stack size must be positive");
        }
        this.maximumSize = maximumSize;
    }

    public int size() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int getMaximumSize() {
        return maximumSize;
    }

    public void push(VmStackValue value) {
        Objects.requireNonNull(value, "value");
        if (values.size() >= maximumSize) {
            throw RuntimeOps.error("RuntimeError", "maximum operand stack size exceeded");
        }
        values.add(value);
    }

    public VmStackValue pop() {
        requireDepth(1);
        return values.remove(values.size() - 1);
    }

    public PyValue popPyValue() {
        VmStackValue value = pop();
        if (!(value instanceof PyValue)) {
            throw new IllegalStateException(
                    "Verified bytecode exposed an internal stack value to a Python operation");
        }
        return (PyValue) value;
    }

    public CodeRef popCodeRef() {
        VmStackValue value = pop();
        if (!(value instanceof CodeRef)) {
            throw new IllegalStateException(
                    "Verified bytecode expected an internal CodeRef");
        }
        return (CodeRef) value;
    }

    public CellRef popCellRef() {
        VmStackValue value = pop();
        if (!(value instanceof CellRef)) {
            throw new IllegalStateException(
                    "Verified bytecode expected an internal CellRef");
        }
        return (CellRef) value;
    }

    public VmStackValue peek() {
        return peek(1);
    }

    /** One-based depth: depth 1 is TOS. */
    public VmStackValue peek(int depth) {
        requireDepth(depth);
        return values.get(values.size() - depth);
    }

    public PyValue peekPyValue(int depth) {
        VmStackValue value = peek(depth);
        if (!(value instanceof PyValue)) {
            throw new IllegalStateException(
                    "Verified bytecode exposed an internal stack value to a Python operation");
        }
        return (PyValue) value;
    }

    /** Duplicates the value at a one-based depth. */
    public void copy(int depth) {
        push(peek(depth));
    }

    /** Swaps TOS with the value at a one-based depth. */
    public void swap(int depth) {
        requireDepth(depth);
        int top = values.size() - 1;
        int other = values.size() - depth;
        VmStackValue topValue = values.get(top);
        values.set(top, values.get(other));
        values.set(other, topValue);
    }

    /** Pops Python values and restores their original left-to-right order. */
    public List<PyValue> popPyValuesInSourceOrder(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Pop count cannot be negative");
        }
        if (count > values.size()) {
            throw new IllegalStateException("Verified bytecode caused operand stack underflow");
        }
        List<PyValue> result = new ArrayList<>(
                Collections.nCopies(count, (PyValue) null));
        for (int i = count - 1; i >= 0; i--) {
            result.set(i, popPyValue());
        }
        return result;
    }

    public void truncate(int newSize) {
        if (newSize < 0 || newSize > values.size()) {
            throw new IllegalArgumentException("Invalid operand-stack truncation size");
        }
        while (values.size() > newSize) {
            values.remove(values.size() - 1);
        }
    }

    public void clear() {
        values.clear();
    }

    /** Restores the verifier-owned prefix captured at transfer creation. */
    public void restorePrefix(List<VmStackValue> snapshot, int depth) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (depth < 0 || depth > snapshot.size()) {
            throw new IllegalStateException(
                    "Verified stack anchor cannot be restored from transfer state");
        }
        values.clear();
        for (int index = 0; index < depth; index++) {
            push(snapshot.get(index));
        }
    }

    public List<VmStackValue> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private void requireDepth(int depth) {
        if (depth <= 0 || depth > values.size()) {
            throw new IllegalStateException(
                    "Verified bytecode used invalid operand-stack depth " + depth
                            + " at stack size " + values.size());
        }
    }
}
