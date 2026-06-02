package compilers.flask.ast.nodes.expressions.access;

import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public class AttributeAccessNode extends Expression {

    private Expression object;
    private String attribute;

    public AttributeAccessNode(Expression object, String attribute) {
        this.object = object;
        this.attribute = attribute;
        object.setParent(this);
    }

    public Expression getObject() {
        return object;
    }

    public String getAttribute() {
        return attribute;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitAttributeAccess(this);
    }

    @Override
    public String getNodeType() {
        return "AttributeAccess";
    }

    @Override
    public String toString() {
        return "AttributeAccess(" + attribute + ")";
    }
}
