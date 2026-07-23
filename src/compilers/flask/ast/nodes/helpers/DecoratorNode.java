package compilers.flask.ast.nodes.helpers;

import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.SourceSpan;

/**
 * Located decorator wrapper whose payload is the complete evaluated
 * expression.  In particular, an identifier and a zero-argument call remain
 * distinct AST shapes.
 */
public class DecoratorNode {

    private final Expression expression;
    private final SourceSpan span;

    public DecoratorNode(Expression expression, SourceSpan span) {
        if (expression == null) {
            throw new IllegalArgumentException("Decorator expression cannot be null");
        }
        this.expression = expression;
        this.span = span == null ? SourceSpan.UNKNOWN : span;
    }

    public DecoratorNode(Expression expression) {
        this(expression, expression == null
                ? SourceSpan.UNKNOWN
                : expression.getSourceSpan());
    }

    public Expression getExpression() {
        return expression;
    }

    public SourceSpan getSpan() {
        return span;
    }

    @Override
    public String toString() {
        return "@" + expression;
    }
}
