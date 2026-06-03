package compilers.flask.semantic;

public class UndefinedVariableError extends SemanticError {
    public UndefinedVariableError(String variableName, int line, int column, String sourceFile) {
        super("'" + variableName + "' is not defined", line, column, sourceFile, Severity.ERROR);
    }

    @Override
    public String getErrorType() { return "Undefined Variable"; }
}
