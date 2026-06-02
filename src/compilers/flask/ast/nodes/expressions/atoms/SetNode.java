package compilers.flask.ast.nodes.expressions.atoms;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.List;

public class SetNode extends Expression {

    private final List<Expression> elements;

    public SetNode(List<Expression> elements) {
        this.elements = elements;
        // Set parent for all elements
        for (Expression elem : elements) {
            elem.setParent(this);
        }
    }

    public List<Expression> getElements() {
        return elements;
    }

    // Helper methods
    public boolean isEmpty() {
        return elements.isEmpty();
    }

    public int size() {
        return elements.size();
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitSet(this);
    }

    @Override
    public String getNodeType() {
        return "Set";
    }

    @Override
    public String getExpressionType() {
        return "set";
    }

    @Override
    public String toString() {
        return "Set(" + elements.size() + " elements)";
    }
}

