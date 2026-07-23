package compilers.diagnostics;

/** Severity used consistently by every compiler phase. */
public enum DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO;

    public String label() {
        switch (this) {
            case ERROR:
                return "Error";
            case WARNING:
                return "Warning";
            case INFO:
                return "Info";
            default:
                throw new IllegalStateException("Unexpected severity: " + this);
        }
    }
}
