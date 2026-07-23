package compilers.flask.vm;

import compilers.flask.codegen.analysis.ResolvedName;

import java.util.Objects;

/** One dynamically handled exception, visible to bare raise across calls. */
public final class ExceptionState {
    private final Frame ownerFrame;
    private final int handlerId;
    private final int cleanupRegionId;
    private final VmRuntimeException failure;
    private final ResolvedName alias;
    /**
     * A transfer which was already running a cleanup when this exception was
     * raised.  A handler which completes normally resumes it; an abrupt
     * handler exit deliberately discards it because the new transfer wins.
     */
    private final PendingTransfer suspendedTransfer;

    public ExceptionState(
            Frame ownerFrame,
            int handlerId,
            int cleanupRegionId,
            VmRuntimeException failure,
            ResolvedName alias) {
        this(ownerFrame, handlerId, cleanupRegionId, failure, alias, null);
    }

    public ExceptionState(
            Frame ownerFrame,
            int handlerId,
            int cleanupRegionId,
            VmRuntimeException failure,
            ResolvedName alias,
            PendingTransfer suspendedTransfer) {
        this.ownerFrame = Objects.requireNonNull(ownerFrame, "ownerFrame");
        if (handlerId < 0 || cleanupRegionId < 0) {
            throw new IllegalArgumentException("Exception-state ids cannot be negative");
        }
        this.handlerId = handlerId;
        this.cleanupRegionId = cleanupRegionId;
        this.failure = Objects.requireNonNull(failure, "failure");
        this.alias = alias;
        this.suspendedTransfer = suspendedTransfer;
    }

    public Frame getOwnerFrame() { return ownerFrame; }
    public int getHandlerId() { return handlerId; }
    public int getCleanupRegionId() { return cleanupRegionId; }
    public VmRuntimeException getFailure() { return failure; }
    public ResolvedName getAlias() { return alias; }
    public PendingTransfer getSuspendedTransfer() { return suspendedTransfer; }
}
