package compilers.flask.semantic;

public class TypeMismatchError extends SemanticError {
    public TypeMismatchError(String variableName, String expected, String received, int line, int column, String sourceFile) {
        super("Variable '" + variableName + "' expected " + expected + " but received " + received, line, column, sourceFile, Severity.ERROR);
    }

    @Override
    public String getErrorType() { return "Type Mismatch"; }
}
