package compilers.flask.ast.nodes.expressions.atoms;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.ArrayList;
import java.util.List;

public class ListNode extends Expression {

    private final List<Expression> elements;

    public ListNode(List<Expression> elements) {
        this.elements = elements;
        // Set parent for all elements
        for (Expression elem : elements) {
            elem.setParent(this);
        }
    }

    // Constructor for empty list
    public ListNode() {
        this(new ArrayList<>());
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
        return visitor.visitList(this);
    }

    @Override
    public String getNodeType() {
        return "List";
    }

    @Override
    public String getExpressionType() {
        return "list";
    }

    @Override
    public String toString() {
        return "List(" + elements.size() + " elements)";
    }
}

