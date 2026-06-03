package compilers.flask.semantic;

public class TypeError extends SemanticError {
    public TypeError(String message, int line, int column, String sourceFile) {
        super(message, line, column, sourceFile, Severity.ERROR);
    }

    @Override
    public String getErrorType() { return "Type Error"; }
}
