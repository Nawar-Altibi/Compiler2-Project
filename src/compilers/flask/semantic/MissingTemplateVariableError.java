package compilers.flask.semantic;

public class MissingTemplateVariableError extends SemanticError {
    public MissingTemplateVariableError(String templateName, String variableName, int line, int column, String sourceFile) {
        super("Template '" + templateName + "' requires variable '" + variableName + "' but Flask route does not provide it", line, column, sourceFile, Severity.ERROR);
    }

    @Override
    public String getErrorType() { return "Missing Template Variable"; }
}
