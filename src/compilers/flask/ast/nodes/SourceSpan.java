package compilers.flask.ast.nodes;

import java.util.Objects;

/**
 * Immutable, end-exclusive source range.
 *
 * <p>Lines are one-based and columns are zero-based.  The only unknown range
 * is {@link #UNKNOWN}; ordinary ranges never use negative coordinates.</p>
 */
public final class SourceSpan {

    public static final String UNKNOWN_SOURCE = "<unknown>";
    public static final SourceSpan UNKNOWN =
            new SourceSpan(UNKNOWN_SOURCE, 0, 0, 0, 0, true);

    private final String sourceFile;
    private final int startLine;
    private final int startColumn;
    private final int endLine;
    private final int endColumn;

    public SourceSpan(
            String sourceFile,
            int startLine,
            int startColumn,
            int endLine,
            int endColumn) {
        this(sourceFile, startLine, startColumn, endLine, endColumn, false);
    }

    private SourceSpan(
            String sourceFile,
            int startLine,
            int startColumn,
            int endLine,
            int endColumn,
            boolean allowUnknown) {
        this.sourceFile = normalizeSourceFile(sourceFile);
        this.startLine = startLine;
        this.startColumn = startColumn;
        this.endLine = endLine;
        this.endColumn = endColumn;
        validate(allowUnknown);
    }

    /** Creates a zero-width located span for legacy line/column setters. */
    public static SourceSpan point(String sourceFile, int line, int column) {
        return new SourceSpan(sourceFile, line, column, line, column);
    }

    private static String normalizeSourceFile(String sourceFile) {
        if (sourceFile == null || sourceFile.trim().isEmpty()) {
            return UNKNOWN_SOURCE;
        }
        return sourceFile;
    }

    private void validate(boolean allowUnknown) {
        boolean allZero = startLine == 0 && startColumn == 0
                && endLine == 0 && endColumn == 0;
        if (allZero) {
            if (!allowUnknown) {
                throw new IllegalArgumentException(
                        "Only SourceSpan.UNKNOWN may use zero coordinates");
            }
            return;
        }

        if (startLine < 1 || endLine < 1) {
            throw new IllegalArgumentException("SourceSpan lines must be one-based");
        }
        if (startColumn < 0 || endColumn < 0) {
            throw new IllegalArgumentException("SourceSpan columns cannot be negative");
        }
        if (endLine < startLine
                || (endLine == startLine && endColumn < startColumn)) {
            throw new IllegalArgumentException(
                    "SourceSpan end must not precede its start");
        }
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getStartColumn() {
        return startColumn;
    }

    public int getEndLine() {
        return endLine;
    }

    public int getEndColumn() {
        return endColumn;
    }

    public boolean isKnown() {
        return startLine != 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SourceSpan)) {
            return false;
        }
        SourceSpan that = (SourceSpan) other;
        return startLine == that.startLine
                && startColumn == that.startColumn
                && endLine == that.endLine
                && endColumn == that.endColumn
                && sourceFile.equals(that.sourceFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceFile, startLine, startColumn, endLine, endColumn);
    }

    @Override
    public String toString() {
        if (!isKnown()) {
            return UNKNOWN_SOURCE;
        }
        return sourceFile + ":" + startLine + ":" + startColumn
                + "-" + endLine + ":" + endColumn;
    }
}
