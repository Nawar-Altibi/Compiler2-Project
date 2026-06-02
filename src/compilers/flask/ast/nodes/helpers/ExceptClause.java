package compilers.flask.ast.nodes.helpers;

import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.Statement;

import java.util.List;

public class ExceptClause {

    private final Expression exceptionType;  // Exception type (null for bare except)
    private final String asName;             // Variable name (null if not present)
    private final List<Statement> body;      // Handler body

    // Constructor with all fields
    public ExceptClause(Expression exceptionType, String asName, List<Statement> body) {
        this.exceptionType = exceptionType;
        this.asName = asName;
        this.body = body;
    }

    // Constructor without asName
    public ExceptClause(Expression exceptionType, List<Statement> body) {
        this(exceptionType, null, body);
    }

    // Constructor for bare except
    public ExceptClause(List<Statement> body) {
        this(null, null, body);
    }

    // Getters
    public Expression getExceptionType() {
        return exceptionType;
    }

    public String getAsName() {
        return asName;
    }

    public List<Statement> getBody() {
        return body;
    }

    // Helper methods
    public boolean isBareExcept() {
        return exceptionType == null;
    }

    public boolean hasAsName() {
        return asName != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("except");
        if (exceptionType != null) {
            sb.append(" ").append(exceptionType);
            if (asName != null) {
                sb.append(" as ").append(asName);
            }
        }
        return sb.toString();
    }
}
