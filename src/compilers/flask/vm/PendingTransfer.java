package compilers.flask.vm;

import compilers.flask.codegen.bytecode.StackAnchor;
import compilers.flask.vm.values.PyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Cleanup-aware control transfer suspended outside the Python operand stack.
 *
 * <p>The ordered visited-boundary set is deliberately inherited when cleanup
 * bytecode replaces an older transfer.  This is what prevents a finally or
 * context manager from entering itself a second time while still allowing
 * outer boundaries to run.</p>
 */
public final class PendingTransfer {
    public enum Kind { NORMAL, RETURN, JUMP, EXCEPTION }

    private final Kind kind;
    private final PyValue returnValue;
    private final VmRuntimeException exception;
    private final int targetOffset;
    private final StackAnchor targetAnchor;
    private final int originOffset;
    private final List<VmStackValue> originStack;
    private final LinkedHashSet<Integer> visitedBoundaryIds;
    private PendingTransfer suspendedTransfer;
    private int activeCleanupId;
    private boolean dispatching;

    private PendingTransfer(
            Kind kind,
            PyValue returnValue,
            VmRuntimeException exception,
            int targetOffset,
            StackAnchor targetAnchor,
            int originOffset,
            List<VmStackValue> originStack,
            Set<Integer> inheritedVisited,
            PendingTransfer suspendedTransfer) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.returnValue = returnValue;
        this.exception = exception;
        this.targetOffset = targetOffset;
        this.targetAnchor = targetAnchor;
        if (originOffset < 0) {
            throw new IllegalArgumentException("Transfer origin cannot be negative");
        }
        this.originOffset = originOffset;
        Objects.requireNonNull(originStack, "originStack");
        this.originStack = Collections.unmodifiableList(
                new ArrayList<>(originStack));
        this.visitedBoundaryIds = new LinkedHashSet<>(
                Objects.requireNonNull(inheritedVisited, "inheritedVisited"));
        this.suspendedTransfer = suspendedTransfer;
        this.activeCleanupId = -1;

        if ((kind == Kind.NORMAL || kind == Kind.JUMP)
                != (targetOffset >= 0)) {
            throw new IllegalArgumentException(
                    "NORMAL/JUMP transfers require a target offset");
        }
        if (kind != Kind.NORMAL && kind != Kind.JUMP && targetAnchor != null) {
            throw new IllegalArgumentException(
                    "Only destination transfers may carry a target anchor");
        }
        if ((kind == Kind.RETURN) != (returnValue != null)) {
            throw new IllegalArgumentException(
                    "RETURN transfers require exactly one return value");
        }
        if ((kind == Kind.EXCEPTION) != (exception != null)) {
            throw new IllegalArgumentException(
                    "EXCEPTION transfers require exactly one exception signal");
        }
    }

    public static PendingTransfer normal(
            int targetOffset,
            StackAnchor targetAnchor,
            int originOffset,
            List<VmStackValue> originStack,
            Set<Integer> inheritedVisited) {
        return new PendingTransfer(
                Kind.NORMAL, null, null, targetOffset, targetAnchor,
                originOffset, originStack, inheritedVisited, null);
    }

    public static PendingTransfer normal(
            int targetOffset,
            StackAnchor targetAnchor,
            int originOffset,
            List<VmStackValue> originStack,
            Set<Integer> inheritedVisited,
            PendingTransfer suspendedTransfer) {
        return new PendingTransfer(
                Kind.NORMAL, null, null, targetOffset, targetAnchor,
                originOffset, originStack, inheritedVisited,
                suspendedTransfer);
    }

    public static PendingTransfer jump(
            int targetOffset,
            StackAnchor targetAnchor,
            int originOffset,
            List<VmStackValue> originStack,
            Set<Integer> inheritedVisited) {
        return new PendingTransfer(
                Kind.JUMP, null, null, targetOffset, targetAnchor,
                originOffset, originStack, inheritedVisited, null);
    }

    public static PendingTransfer returning(
            PyValue value,
            int originOffset,
            List<VmStackValue> originStack,
            Set<Integer> inheritedVisited) {
        return new PendingTransfer(
                Kind.RETURN, Objects.requireNonNull(value, "value"), null,
                -1, null, originOffset, originStack, inheritedVisited, null);
    }

    public static PendingTransfer exception(
            VmRuntimeException failure,
            int originOffset,
            List<VmStackValue> originStack,
            Set<Integer> inheritedVisited) {
        return exception(
                failure, originOffset, originStack, inheritedVisited, null);
    }

    /**
     * Creates an exception transfer which temporarily supersedes an older
     * transfer suspended in cleanup bytecode.  If this exception is handled
     * normally, {@code BEGIN_EXCEPT}/{@code END_EXCEPT} restore that older
     * transfer; if it escapes, the replacement wins.
     */
    public static PendingTransfer exception(
            VmRuntimeException failure,
            int originOffset,
            List<VmStackValue> originStack,
            Set<Integer> inheritedVisited,
            PendingTransfer suspendedTransfer) {
        return new PendingTransfer(
                Kind.EXCEPTION, null,
                Objects.requireNonNull(failure, "failure"), -1, null,
                originOffset, originStack, inheritedVisited,
                suspendedTransfer);
    }

    public Kind getKind() { return kind; }
    public PyValue getReturnValue() { return returnValue; }
    public VmRuntimeException getException() { return exception; }
    public int getTargetOffset() { return targetOffset; }
    public StackAnchor getTargetAnchor() { return targetAnchor; }
    public int getOriginOffset() { return originOffset; }
    public List<VmStackValue> getOriginStack() { return originStack; }
    public int getActiveCleanupId() { return activeCleanupId; }
    public boolean isDispatching() { return dispatching; }
    public PendingTransfer getSuspendedTransfer() { return suspendedTransfer; }

    /**
     * Narrows a replacement exception's resumable continuation when handler
     * dispatch crosses out of one or more cleanup handlers.  The transfer is
     * otherwise immutable; this is dynamic unwinder state, like dispatching
     * and activeCleanupId, and is only changed before BEGIN_EXCEPT.
     */
    void retainSuspendedTransfer(PendingTransfer resumableTransfer) {
        suspendedTransfer = resumableTransfer;
    }

    public Set<Integer> getVisitedBoundaryIds() {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(visitedBoundaryIds));
    }

    public boolean hasVisited(int boundaryId) {
        return visitedBoundaryIds.contains(boundaryId);
    }

    public void visit(int boundaryId) {
        if (boundaryId < 0) {
            throw new IllegalArgumentException("Boundary id cannot be negative");
        }
        visitedBoundaryIds.add(boundaryId);
    }

    public void enterCleanup(int cleanupId) {
        if (cleanupId < 0 || activeCleanupId >= 0) {
            throw new IllegalStateException("PendingTransfer cleanup-state corruption");
        }
        activeCleanupId = cleanupId;
        dispatching = false;
    }

    public void leaveCleanup(int cleanupId) {
        if (activeCleanupId != cleanupId) {
            throw new IllegalStateException(
                    "END_CLEANUP does not match the active cleanup");
        }
        activeCleanupId = -1;
    }

    public void beginDispatch() {
        if (kind != Kind.EXCEPTION || activeCleanupId >= 0) {
            throw new IllegalStateException(
                    "Only an unsuspended exception can enter handler dispatch");
        }
        dispatching = true;
    }

    public void endDispatch() {
        dispatching = false;
    }
}
