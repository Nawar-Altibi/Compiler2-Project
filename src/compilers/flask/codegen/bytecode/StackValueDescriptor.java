package compilers.flask.codegen.bytecode;

import java.util.Objects;

/** Verifier-owned stack kind plus exact internal-value provenance. */
public final class StackValueDescriptor {
    public enum Kind { PY_VALUE, CELL_REF, CODE_REF }

    public static final StackValueDescriptor PY_VALUE =
            new StackValueDescriptor(Kind.PY_VALUE, "");

    private final Kind kind;
    private final String provenance;

    public StackValueDescriptor(Kind kind, String provenance) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.provenance = provenance == null ? "" : provenance;
        if (kind != Kind.PY_VALUE && this.provenance.isEmpty()) {
            throw new IllegalArgumentException("Internal stack values require provenance");
        }
    }

    public Kind getKind() {
        return kind;
    }

    public String getProvenance() {
        return provenance;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof StackValueDescriptor)) {
            return false;
        }
        StackValueDescriptor that = (StackValueDescriptor) other;
        return kind == that.kind && provenance.equals(that.provenance);
    }

    @Override
    public int hashCode() {
        return 31 * kind.hashCode() + provenance.hashCode();
    }

    @Override
    public String toString() {
        return provenance.isEmpty() ? kind.name() : kind.name() + "(" + provenance + ")";
    }
}
