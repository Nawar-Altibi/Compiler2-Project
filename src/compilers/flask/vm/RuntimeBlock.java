package compilers.flask.vm;

/** Explicit activation record for runtime-only cleanup state. */
public final class RuntimeBlock {
    public enum Kind { WITH, EXCEPT_HANDLER }

    private final Kind kind;
    private final int boundaryId;
    private final int handlerId;

    private RuntimeBlock(Kind kind, int boundaryId, int handlerId) {
        if (kind == null || boundaryId < 0 || handlerId < -1) {
            throw new IllegalArgumentException("Invalid runtime block");
        }
        this.kind = kind;
        this.boundaryId = boundaryId;
        this.handlerId = handlerId;
    }

    public static RuntimeBlock with(int boundaryId) {
        return new RuntimeBlock(Kind.WITH, boundaryId, -1);
    }

    public static RuntimeBlock handler(int boundaryId, int handlerId) {
        return new RuntimeBlock(Kind.EXCEPT_HANDLER, boundaryId, handlerId);
    }

    public Kind getKind() { return kind; }
    public int getBoundaryId() { return boundaryId; }
    public int getHandlerId() { return handlerId; }
}
