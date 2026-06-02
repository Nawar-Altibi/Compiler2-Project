package compilers.flask.ast.nodes.expressions.atoms;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.ArrayList;
import java.util.List;

public class TupleNode extends Expression {

    private final List<Expression> elements;
    private final boolean hasParentheses;  // True if explicitly parenthesized

    public TupleNode(List<Expression> elements, boolean hasParentheses) {
        this.elements = elements;
        this.hasParentheses = hasParentheses;
        // Set parent for all elements
        for (Expression elem : elements) {
            elem.setParent(this);
        }
    }

    // Constructor without parentheses flag (default to true)
    public TupleNode(List<Expression> elements) {
        this(elements, true);
    }

    // Constructor for empty tuple
    public TupleNode() {
        this(new ArrayList<>(), true);
    }

    public List<Expression> getElements() {
        return elements;
    }

    public boolean hasParentheses() {
        return hasParentheses;
    }

    // Helper methods
    public boolean isEmpty() {
        return elements.isEmpty();
    }

    public int size() {
        return elements.size();
    }

    public boolean isSingleElement() {
        return elements.size() == 1;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitTuple(this);
    }

    @Override
    public String getNodeType() {
        return "Tuple";
    }

    @Override
    public String getExpressionType() {
        return "tuple";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Tuple(");
        sb.append(elements.size()).append(" elements");
        if (!hasParentheses) {
            sb.append(", implicit");
        }
        sb.append(")");
        return sb.toString();
    }
}

