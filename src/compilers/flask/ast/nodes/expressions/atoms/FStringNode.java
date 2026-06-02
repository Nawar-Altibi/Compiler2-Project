package compilers.flask.ast.nodes.expressions.atoms;

import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.ArrayList;
import java.util.List;

public class FStringNode extends Expression {

    private final List<FStringPart> parts;

    public FStringNode(List<FStringPart> parts) {
        this.parts = parts != null ? parts : new ArrayList<>();
        // Set parent for all expression parts
        for (FStringPart part : this.parts) {
            if (part instanceof FStringPart.ExpressionPart) {
                Expression expr = ((FStringPart.ExpressionPart) part).getExpression();
                if (expr != null) {
                    expr.setParent(this);
                }
            }
        }
    }

    public FStringNode() {
        this(new ArrayList<>());
    }

    public List<FStringPart> getParts() {
        return parts;
    }

    public boolean isEmpty() {
        return parts.isEmpty();
    }

    public int size() {
        return parts.size();
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitFString(this);
    }

    @Override
    public String getNodeType() {
        return "FString";
    }

    @Override
    public String getExpressionType() {
        return "f-string";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("FString(");
        sb.append(parts.size()).append(" parts");
        sb.append(")");
        return sb.toString();
    }
}


