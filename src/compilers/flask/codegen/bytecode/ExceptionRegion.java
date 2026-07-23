package compilers.flask.codegen.bytecode;

/** Resolved protected range and handler-dispatch entry. */
public final class ExceptionRegion {
    private final int id;
    private final int parentBoundaryId;
    private final int nestingDepth;
    private final int startOffset;
    private final int endOffset;
    private final int handlerOffset;
    private final StackAnchor anchor;

    public ExceptionRegion(
            int id, int startOffset, int endOffset, int handlerOffset,
            StackAnchor anchor) {
        this(id, -1, 0, startOffset, endOffset, handlerOffset, anchor);
    }

    public ExceptionRegion(
            int id,
            int parentBoundaryId,
            int nestingDepth,
            int startOffset,
            int endOffset,
            int handlerOffset,
            StackAnchor anchor) {
        if (id < 0 || startOffset < 0 || endOffset <= startOffset
                || handlerOffset < 0 || parentBoundaryId < -1 || nestingDepth < 0) {
            throw new IllegalArgumentException("Invalid exception region bounds");
        }
        this.id = id;
        this.parentBoundaryId = parentBoundaryId;
        this.nestingDepth = nestingDepth;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.handlerOffset = handlerOffset;
        this.anchor = anchor;
    }

    public int getId() { return id; }
    public int getParentBoundaryId() { return parentBoundaryId; }
    public int getNestingDepth() { return nestingDepth; }
    public int getStartOffset() { return startOffset; }
    public int getEndOffset() { return endOffset; }
    public int getHandlerOffset() { return handlerOffset; }
    public StackAnchor getAnchor() { return anchor; }

    public ExceptionRegion withAnchor(StackAnchor resolvedAnchor) {
        return new ExceptionRegion(
                id, parentBoundaryId, nestingDepth, startOffset, endOffset,
                handlerOffset, resolvedAnchor);
    }
}
