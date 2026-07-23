package compilers.flask.ast.nodes.helpers;

import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.ast.nodes.Statement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ExceptClause {

    private final Expression exceptionType;  // Exception type (null for bare except)
    private final String asName;             // Variable name (null if not present)
    private final List<Statement> body;      // Handler body
    private final SourceSpan span;
    private final SourceSpan aliasSpan;

    // Constructor with all fields
    public ExceptClause(Expression exceptionType, String asName, List<Statement> body) {
        this(exceptionType, asName, body, SourceSpan.UNKNOWN, SourceSpan.UNKNOWN);
    }

    public ExceptClause(
            Expression exceptionType,
            String asName,
            List<Statement> body,
            SourceSpan span,
            SourceSpan aliasSpan) {
        this.exceptionType = exceptionType;
        this.asName = asName;
        this.body = new ArrayList<>(body);
        this.span = span == null ? SourceSpan.UNKNOWN : span;
        this.aliasSpan = aliasSpan == null ? SourceSpan.UNKNOWN : aliasSpan;
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
        return Collections.unmodifiableList(body);
    }

    public SourceSpan getSpan() {
        return span;
    }

    public SourceSpan getAliasSpan() {
        return aliasSpan;
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
