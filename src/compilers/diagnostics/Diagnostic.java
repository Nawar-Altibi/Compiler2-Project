package compilers.diagnostics;

import java.util.Objects;

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
        this.category = Objects.requireNonNull(category);
        this.severity = Objects.requireNonNull(severity);
        this.phase = Objects.requireNonNull(phase);
        this.sourceFile = sourceFile != null ? sourceFile : "<unknown>";
        this.line = line;
        this.column = column;
        this.message = Objects.requireNonNull(message);
    }

    public DiagnosticCategory category() { return category; }
    public DiagnosticSeverity severity() { return severity; }
    public CompilerPhase phase() { return phase; }
    public String sourceFile() { return sourceFile; }
    public int line() { return line; }
    public int column() { return column; }
    public String message() { return message; }

    public boolean isError() {
        return severity == DiagnosticSeverity.ERROR;
    }

    public boolean isWarning() {
        return severity == DiagnosticSeverity.WARNING;
    }

    @Override
    public String toString() {
        String location = column > 0
                ? String.format("%s:%d:%d", sourceFile, line, column)
                : String.format("%s:%d", sourceFile, line);

        return String.format(
                "[%s %s] %s%n  %s: %s",
                phase.displayName(),
                severity.label(),
                location,
                category.displayName(),
                message);
    }
}
