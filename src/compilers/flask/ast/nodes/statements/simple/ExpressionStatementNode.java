package compilers.flask.ast.nodes.statements.simple;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public class ExpressionStatementNode extends Statement {

    private Expression expression;

    public ExpressionStatementNode(Expression expression) {
        this.expression = expression;
        expression.setParent(this);
    }

    public Expression getExpression() {
        return expression;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitExpressionStatement(this);
    }

    @Override
    public String getNodeType() {
        return "ExpressionStatement";
    }
}

