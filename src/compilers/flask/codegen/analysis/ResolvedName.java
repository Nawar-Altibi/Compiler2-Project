package compilers.flask.codegen.analysis;

import java.util.Objects;

/** Immutable binding decision consumed by bytecode generation. */
public final class ResolvedName {

    private final String name;
    private final BindingKind kind;
    private final ScopeLayout useScope;
    private final ScopeLayout definingScope;
    private final int slotIndex;

    public ResolvedName(
            String name,
            BindingKind kind,
            ScopeLayout useScope,
            ScopeLayout definingScope,
            int slotIndex) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Resolved name cannot be empty");
        }
        this.name = name;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.useScope = Objects.requireNonNull(useScope, "useScope");
        this.definingScope = definingScope;
        if ((kind == BindingKind.FAST || kind == BindingKind.DEREF) && slotIndex < 0) {
            throw new IllegalArgumentException(kind + " binding requires a slot index");
        }
        if ((kind == BindingKind.NAME || kind == BindingKind.GLOBAL) && slotIndex != -1) {
            throw new IllegalArgumentException(kind + " binding does not own a layout slot");
        }
        this.slotIndex = slotIndex;
    }

    public String getName() {
        return name;
    }

    public BindingKind getKind() {
        return kind;
    }

    public ScopeLayout getUseScope() {
        return useScope;
    }

    /**
     * Scope that owns the binding. It is the module for GLOBAL, the current
     * block for NAME/FAST/cell DEREF, and an enclosing function for free DEREF.
     */
    public ScopeLayout getDefiningScope() {
        return definingScope;
    }

    /** FAST or combined cell/free slot; {@code -1} for NAME/GLOBAL. */
    public int getSlotIndex() {
        return slotIndex;
    }

    public boolean isCellVariable() {
        return kind == BindingKind.DEREF && definingScope == useScope;
    }

    public boolean isFreeVariable() {
        return kind == BindingKind.DEREF && definingScope != useScope;
    }

    @Override
    public String toString() {
        if (slotIndex < 0) {
            return kind + "(" + name + ")";
        }
        return kind + "(" + name + "@" + slotIndex + ")";
    }
}
