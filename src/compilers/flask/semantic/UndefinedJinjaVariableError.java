package compilers.flask.semantic;

public class UndefinedJinjaVariableError extends SemanticError {
    public UndefinedJinjaVariableError(String variableName, int line, int column, String sourceFile) {
        super("'" + variableName + "' is not supplied by Flask", line, column, sourceFile, Severity.ERROR);
    }

    @Override
    public String getErrorType() { return "Undefined Jinja Variable"; }
}
