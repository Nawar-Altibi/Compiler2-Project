package compilers.flask.ast.nodes.helpers;

import compilers.flask.ast.nodes.Expression;

public class WithItem {

    private final Expression contextExpr;  // Context manager expression
    private final Expression asName;       // Optional "as" target (null if not present)

    // Constructor with all fields
    public WithItem(Expression contextExpr, Expression asName) {
        this.contextExpr = contextExpr;
        this.asName = asName;
    }

    // Constructor without asName (common for context managers without binding)
    public WithItem(Expression contextExpr) {
        this(contextExpr, null);
    }

    // Getters
    public Expression getContextExpr() {
        return contextExpr;
    }

    public Expression getAsName() {
        return asName;
    }

    // Helper method
    public boolean hasAsName() {
        return asName != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(contextExpr);
        if (asName != null) {
            sb.append(" as ").append(asName);
        }
        return sb.toString();
    }
}
