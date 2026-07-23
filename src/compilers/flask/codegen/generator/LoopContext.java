package compilers.flask.codegen.generator;

import compilers.flask.codegen.bytecode.Label;

import java.util.Objects;

/** Per-code-object lowering targets for one active loop. */
public final class LoopContext {
    private final Label continueTarget;
    private final Label breakTarget;
    private final boolean breakRequiresUnwind;

    public LoopContext(
            Label continueTarget, Label breakTarget, boolean breakRequiresUnwind) {
        this.continueTarget = Objects.requireNonNull(continueTarget, "continueTarget");
        this.breakTarget = Objects.requireNonNull(breakTarget, "breakTarget");
        this.breakRequiresUnwind = breakRequiresUnwind;
    }

    public Label getContinueTarget() {
        return continueTarget;
    }

    public Label getBreakTarget() {
        return breakTarget;
    }

    public boolean isBreakRequiresUnwind() {
        return breakRequiresUnwind;
    }
}
