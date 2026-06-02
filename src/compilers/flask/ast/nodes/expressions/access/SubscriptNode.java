package compilers.flask.ast.nodes.expressions.access;

import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public class SubscriptNode extends Expression {

    private final Expression object;  // What to subscript
    private final Expression index;   // Index/key

    public SubscriptNode(Expression object, Expression index) {
        this.object = object;
        this.index = index;

        object.setParent(this);
        index.setParent(this);
    }

    public Expression getObject() {
        return object;
    }

    public Expression getIndex() {
        return index;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitSubscript(this);
    }

    @Override
    public String getNodeType() {
        return "Subscript";
    }

    @Override
    public String toString() {
        return "Subscript(" + object + "[" + index + "])";
    }
}
