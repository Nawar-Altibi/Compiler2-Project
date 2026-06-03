package compilers.flask.semantic;

public class ScopeError extends SemanticError {
    public ScopeError(String message, int line, int column, String sourceFile) {
        super(message, line, column, sourceFile, Severity.ERROR);
    }

    @Override
    public String getErrorType() { return "Scope Error"; }
}
