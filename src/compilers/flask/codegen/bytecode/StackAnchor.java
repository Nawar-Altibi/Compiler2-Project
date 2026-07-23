package compilers.flask.codegen.bytecode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable stack shape computed by verification for a symbolic anchor. */
public final class StackAnchor {
    private final int id;
    private final int offset;
    private final List<StackValueDescriptor> stackValues;

    public StackAnchor(int id, int offset, List<StackValueDescriptor> stackValues) {
        if (id < 0 || offset < 0) {
            throw new IllegalArgumentException("Anchor id/offset cannot be negative");
        }
        this.id = id;
        this.offset = offset;
        Objects.requireNonNull(stackValues, "stackValues");
        List<StackValueDescriptor> copy = new ArrayList<>(stackValues.size());
        for (StackValueDescriptor value : stackValues) {
            copy.add(Objects.requireNonNull(value, "stack value"));
        }
        this.stackValues = Collections.unmodifiableList(copy);
    }

    public int getId() { return id; }
    public int getOffset() { return offset; }
    public int getDepth() { return stackValues.size(); }
    public List<StackValueDescriptor> getStackValues() { return stackValues; }
}
