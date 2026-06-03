package compilers.flask.semantic;

public abstract class SemanticError {
    protected String message;
    protected int line;
    protected int column;
    protected String sourceFile;
    protected Severity severity;

    public enum Severity {
        ERROR, WARNING, INFO
    }

    public SemanticError(String message, int line, int column, String sourceFile, Severity severity) {
        this.message = message;
        this.line = line;
        this.column = column;
        this.sourceFile = sourceFile;
        this.severity = severity;
    }

    public String getMessage() { return message; }
    public int getLine() { return line; }
    public int getColumn() { return column; }
    public String getSourceFile() { return sourceFile; }
    public Severity getSeverity() { return severity; }

    @Override
    public String toString() {
        return String.format("[%s] File: %s, Line: %d\n\n%s: %s\n",
                severity == Severity.ERROR ? "Semantic Error" : "Semantic Warning",
                sourceFile, line, getErrorType(), message);
    }

    public abstract String getErrorType();
}
