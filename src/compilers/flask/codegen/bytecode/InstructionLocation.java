package compilers.flask.codegen.bytecode;

import compilers.flask.ast.nodes.SourceSpan;

/** Source provenance for one instruction. */
public final class InstructionLocation {
    private final SourceSpan span;
    private final boolean synthetic;

    public InstructionLocation(SourceSpan span, boolean synthetic) {
        this.span = span == null ? SourceSpan.UNKNOWN : span;
        this.synthetic = synthetic;
    }

    public SourceSpan getSpan() {
        return span;
    }

    public boolean isSynthetic() {
        return synthetic;
    }
}
