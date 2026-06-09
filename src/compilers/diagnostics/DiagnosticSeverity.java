package compilers.diagnostics;

public enum DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO;

    public String label() {
        return switch (this) {
            case ERROR -> "Error";
            case WARNING -> "Warning";
            case INFO -> "Info";
        };
    }
}
