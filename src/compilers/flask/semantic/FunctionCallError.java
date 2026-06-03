package compilers.flask.semantic;

public class FunctionCallError extends SemanticError {
    public FunctionCallError(String message, int line, int column, String sourceFile) {
        super(message, line, column, sourceFile, Severity.ERROR);
    }

    @Override
    public String getErrorType() { return "Function Call Error"; }
}
