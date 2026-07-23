package compilers.flask.vm;

import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.vm.values.PyBaseException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable Python-like traceback, ordered from outermost to innermost frame. */
public final class VmTraceback {

    public static final class Entry {
        private final String codeName;
        private final SourceSpan sourceSpan;
        private final int instructionOffset;

        public Entry(String codeName, SourceSpan sourceSpan, int instructionOffset) {
            if (codeName == null || codeName.isEmpty()) {
                throw new IllegalArgumentException("Traceback code name cannot be empty");
            }
            if (instructionOffset < 0) {
                throw new IllegalArgumentException(
                        "Traceback instruction offset cannot be negative");
            }
            this.codeName = codeName;
            this.sourceSpan = sourceSpan == null ? SourceSpan.UNKNOWN : sourceSpan;
            this.instructionOffset = instructionOffset;
        }

        public String getCodeName() {
            return codeName;
        }

        public SourceSpan getSourceSpan() {
            return sourceSpan;
        }

        public int getInstructionOffset() {
            return instructionOffset;
        }
    }

    private final List<Entry> entries;
    private final PyBaseException exception;
    private final VmTraceback explicitCause;
    private final VmTraceback implicitContext;
    private final boolean contextSuppressed;

    public VmTraceback(List<Entry> entries, PyBaseException exception) {
        this(entries, exception, null, null, false);
    }

    public VmTraceback(
            List<Entry> entries,
            PyBaseException exception,
            VmTraceback explicitCause,
            VmTraceback implicitContext,
            boolean contextSuppressed) {
        Objects.requireNonNull(entries, "entries");
        List<Entry> copy = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            copy.add(Objects.requireNonNull(entry, "traceback entry"));
        }
        this.entries = Collections.unmodifiableList(copy);
        this.exception = Objects.requireNonNull(exception, "exception");
        this.explicitCause = explicitCause;
        this.implicitContext = implicitContext;
        this.contextSuppressed = contextSuppressed;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public PyBaseException getException() {
        return exception;
    }

    public VmTraceback getExplicitCause() { return explicitCause; }
    public VmTraceback getImplicitContext() { return implicitContext; }
    public boolean isContextSuppressed() { return contextSuppressed; }

    public String format() {
        StringBuilder result = new StringBuilder();
        appendChain(result);
        return result.toString();
    }

    private void appendChain(StringBuilder result) {
        if (explicitCause != null) {
            explicitCause.appendChain(result);
            result.append("\n\nThe above exception was the direct cause "
                    + "of the following exception:\n\n");
        } else if (!contextSuppressed && implicitContext != null) {
            implicitContext.appendChain(result);
            result.append("\n\nDuring handling of the above exception, "
                    + "another exception occurred:\n\n");
        }
        if (!entries.isEmpty()) {
            result.append("Traceback (most recent call last):\n");
            for (Entry entry : entries) {
                SourceSpan span = entry.sourceSpan;
                result.append("  File \"")
                        .append(span.isKnown() ? span.getSourceFile() : "<unknown>")
                        .append("\"");
                if (span.isKnown()) {
                    result.append(", line ").append(span.getStartLine());
                }
                result.append(", in ").append(entry.codeName).append('\n');
            }
        }
        result.append(exception.getExceptionTypeName());
        if (!exception.getMessageText().isEmpty()) {
            result.append(": ").append(exception.getMessageText());
        }
    }

    @Override
    public String toString() {
        return format();
    }
}
