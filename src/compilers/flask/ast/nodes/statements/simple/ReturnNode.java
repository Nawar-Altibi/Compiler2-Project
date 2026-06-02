package compilers.flask.ast.nodes.statements.simple;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public class ReturnNode extends Statement {

    private Expression value;  // Can be null (bare return), single expression, or TupleNode (for multiple values)

    public ReturnNode() {
        this.value = null;
    }

    public ReturnNode(Expression value) {
        this.value = value;
        if (value != null) {
            value.setParent(this);
        }
    }

    public Expression getValue() {
        return value;
    }

    public boolean hasValue() {
        return value != null;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitReturn(this);
    }

    @Override
    public String getNodeType() {
        return "Return";
    }
}

