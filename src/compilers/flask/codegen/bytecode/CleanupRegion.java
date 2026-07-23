package compilers.flask.codegen.bytecode;

import compilers.flask.codegen.analysis.ResolvedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Resolved cleanup/lifetime boundary used by verifier and VM unwinding. */
public final class CleanupRegion {
    public enum Kind { FINALLY, WITH, EXCEPT_HANDLER, TEMP_CLEAR }

    private final int id;
    private final int parentBoundaryId;
    private final int nestingDepth;
    private final Kind kind;
    private final int startOffset;
    private final int endOffset;
    private final int handlerOffset;
    private final int normalContinuationOffset;
    private final List<Integer> resourceSlots;
    private final int handlerId;
    private final ResolvedName alias;
    private final StackAnchor anchor;

    public CleanupRegion(
            int id,
            Kind kind,
            int startOffset,
            int endOffset,
            int handlerOffset,
            int normalContinuationOffset,
            int resourceSlot,
            StackAnchor anchor) {
        this(id, -1, 0, kind, startOffset, endOffset, handlerOffset,
                normalContinuationOffset,
                resourceSlot < 0
                        ? Collections.<Integer>emptyList()
                        : Collections.singletonList(resourceSlot),
                -1, null, anchor);
    }

    public CleanupRegion(
            int id,
            int parentBoundaryId,
            int nestingDepth,
            Kind kind,
            int startOffset,
            int endOffset,
            int handlerOffset,
            int normalContinuationOffset,
            List<Integer> resourceSlots,
            int handlerId,
            ResolvedName alias,
            StackAnchor anchor) {
        if (id < 0 || kind == null || startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException("Invalid cleanup region identity/bounds");
        }
        if (parentBoundaryId < -1 || nestingDepth < 0 || handlerOffset < -1
                || normalContinuationOffset < -1 || handlerId < -1) {
            throw new IllegalArgumentException("Invalid cleanup region offset/slot");
        }
        Objects.requireNonNull(resourceSlots, "resourceSlots");
        List<Integer> copiedSlots = new ArrayList<>(resourceSlots.size());
        for (Integer slot : resourceSlots) {
            if (slot == null || slot < 0) {
                throw new IllegalArgumentException("Cleanup resource slot cannot be negative");
            }
            copiedSlots.add(slot);
        }
        if ((kind == Kind.WITH || kind == Kind.TEMP_CLEAR) && copiedSlots.isEmpty()) {
            throw new IllegalArgumentException(kind + " cleanup requires resource slots");
        }
        if (kind == Kind.WITH && copiedSlots.size() != 1) {
            throw new IllegalArgumentException("WITH cleanup owns exactly one resource slot");
        }
        if (kind == Kind.EXCEPT_HANDLER && handlerId < 0) {
            throw new IllegalArgumentException("EXCEPT_HANDLER cleanup requires a handler id");
        }
        this.id = id;
        this.parentBoundaryId = parentBoundaryId;
        this.nestingDepth = nestingDepth;
        this.kind = kind;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.handlerOffset = handlerOffset;
        this.normalContinuationOffset = normalContinuationOffset;
        this.resourceSlots = Collections.unmodifiableList(copiedSlots);
        this.handlerId = handlerId;
        this.alias = alias;
        this.anchor = anchor;
    }

    public int getId() { return id; }
    public int getParentBoundaryId() { return parentBoundaryId; }
    public int getNestingDepth() { return nestingDepth; }
    public Kind getKind() { return kind; }
    public int getStartOffset() { return startOffset; }
    public int getEndOffset() { return endOffset; }
    public int getHandlerOffset() { return handlerOffset; }
    public int getNormalContinuationOffset() { return normalContinuationOffset; }
    public List<Integer> getResourceSlots() { return resourceSlots; }
    public int getResourceSlot() {
        return resourceSlots.isEmpty() ? -1 : resourceSlots.get(0);
    }
    public int getHandlerId() { return handlerId; }
    public ResolvedName getAlias() { return alias; }
    public StackAnchor getAnchor() { return anchor; }

    public boolean contains(int offset) {
        return offset >= startOffset && offset < endOffset;
    }

    public CleanupRegion withAnchor(StackAnchor resolvedAnchor) {
        return new CleanupRegion(
                id, parentBoundaryId, nestingDepth, kind, startOffset, endOffset,
                handlerOffset, normalContinuationOffset, resourceSlots,
                handlerId, alias, resolvedAnchor);
    }
}
