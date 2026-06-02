package compilers.flask.ast.nodes.expressions.atoms;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public class IdentifierNode extends Expression {

    private String name;

    public IdentifierNode(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitIdentifier(this);
    }

    @Override
    public String getNodeType() {
        return "Identifier";
    }

    @Override
    public String toString() {
        return "Identifier(" + name + ")";
    }
}

