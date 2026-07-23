package compilers.flask.vm;

import java.util.Objects;

/** Immutable host-configured execution limits; counters are fresh per run. */
public final class ExecutionLimits {
    public interface CancellationHook {
        boolean isCancellationRequested();
    }

    private static final CancellationHook NEVER_CANCEL = new CancellationHook() {
        @Override
        public boolean isCancellationRequested() {
            return false;
        }
    };

    public static final ExecutionLimits DEFAULT =
            new ExecutionLimits(10_000_000L, 1_000, 100_000, NEVER_CANCEL);

    private final long maximumInstructionCount;
    private final int maximumCallDepth;
    private final int maximumOperandStackSize;
    private final CancellationHook cancellationHook;

    public ExecutionLimits(
            long maximumInstructionCount,
            int maximumCallDepth,
            int maximumOperandStackSize,
            CancellationHook cancellationHook) {
        if (maximumInstructionCount <= 0) {
            throw new IllegalArgumentException("Maximum instruction count must be positive");
        }
        if (maximumCallDepth <= 0) {
            throw new IllegalArgumentException("Maximum call depth must be positive");
        }
        if (maximumOperandStackSize <= 0) {
            throw new IllegalArgumentException("Maximum operand-stack size must be positive");
        }
        this.maximumInstructionCount = maximumInstructionCount;
        this.maximumCallDepth = maximumCallDepth;
        this.maximumOperandStackSize = maximumOperandStackSize;
        this.cancellationHook = Objects.requireNonNull(cancellationHook, "cancellationHook");
    }

    public ExecutionLimits(
            long maximumInstructionCount,
            int maximumCallDepth,
            int maximumOperandStackSize) {
        this(maximumInstructionCount, maximumCallDepth,
                maximumOperandStackSize, NEVER_CANCEL);
    }

    public long getMaximumInstructionCount() {
        return maximumInstructionCount;
    }

    public int getMaximumCallDepth() {
        return maximumCallDepth;
    }

    public int getMaximumOperandStackSize() {
        return maximumOperandStackSize;
    }

    public CancellationHook getCancellationHook() {
        return cancellationHook;
    }

    public Meter newMeter() {
        return new Meter(this);
    }

    /** Mutable counter state deliberately owned by one execution only. */
    public static final class Meter {
        private final ExecutionLimits limits;
        private long instructionCount;
        private int callDepth;

        private Meter(ExecutionLimits limits) {
            this.limits = limits;
        }

        public void beforeInstruction() {
            if (limits.cancellationHook.isCancellationRequested()) {
                throw RuntimeOps.error("RuntimeError", "execution cancelled");
            }
            if (instructionCount >= limits.maximumInstructionCount) {
                throw RuntimeOps.error("RuntimeError", "maximum instruction count exceeded");
            }
            instructionCount++;
        }

        public void enterCall() {
            callDepth++;
            if (callDepth > limits.maximumCallDepth) {
                callDepth--;
                throw RuntimeOps.error("RuntimeError", "maximum call depth exceeded");
            }
        }

        public void exitCall() {
            if (callDepth <= 0) {
                throw new IllegalStateException("Execution call-depth counter underflow");
            }
            callDepth--;
        }

        public long getInstructionCount() {
            return instructionCount;
        }

        public int getCallDepth() {
            return callDepth;
        }
    }
}
