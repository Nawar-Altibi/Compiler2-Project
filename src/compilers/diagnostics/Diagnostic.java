package compilers.diagnostics;

import java.util.Objects;

/** Immutable compiler diagnostic with a stable source location. */
public final class Diagnostic {
    private final DiagnosticCategory category;
    private final DiagnosticSeverity severity;
    private final CompilerPhase phase;
    private final String sourceFile;
    private final int line;
    private final int column;
    private final String message;

    public Diagnostic(
            DiagnosticCategory category,
            DiagnosticSeverity severity,
            CompilerPhase phase,
            String sourceFile,
            int line,
            int column,
            String message) {
        this.category = Objects.requireNonNull(category, "category");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.sourceFile = sourceFile == null || sourceFile.trim().isEmpty()
                ? "<unknown>"
                : sourceFile;
        this.line = Math.max(line, 0);
        this.column = Math.max(column, 0);
        this.message = Objects.requireNonNull(message, "message");
    }

    public DiagnosticCategory category() {
        return category;
    }

    public DiagnosticSeverity severity() {
        return severity;
    }

    public CompilerPhase phase() {
        return phase;
    }

    public String sourceFile() {
        return sourceFile;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public String message() {
        return message;
    }

    public boolean isError() {
        return severity == DiagnosticSeverity.ERROR;
    }

    public boolean isWarning() {
        return severity == DiagnosticSeverity.WARNING;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Diagnostic)) {
            return false;
        }
        Diagnostic that = (Diagnostic) other;
        return line == that.line
                && column == that.column
                && category == that.category
                && severity == that.severity
                && phase == that.phase
                && sourceFile.equals(that.sourceFile)
                && message.equals(that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(category, severity, phase, sourceFile, line, column, message);
    }

    @Override
    public String toString() {
        String location;
        if (line <= 0) {
            location = sourceFile;
        } else if (column > 0) {
            location = String.format("%s:%d:%d", sourceFile, line, column);
        } else {
            location = String.format("%s:%d", sourceFile, line);
        }

        return String.format(
                "[%s %s] %s%n  %s: %s",
                phase.displayName(),
                severity.label(),
                location,
                category.displayName(),
                message);
    }
}
