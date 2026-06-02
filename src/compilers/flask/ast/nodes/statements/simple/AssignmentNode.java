package compilers.flask.ast.nodes.statements.simple;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public class AssignmentNode extends Statement {

    private Expression target;
    private String operator; // "=", "+=", "-=", etc.
    private Expression value;

    public AssignmentNode(Expression target, String operator, Expression value) {
        if (target == null) {
            throw new IllegalArgumentException("Assignment target cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Assignment value cannot be null");
        }
        if (operator == null) {
            throw new IllegalArgumentException("Assignment operator cannot be null");
        }

        this.target = target;
        this.operator = operator;
        this.value = value;

        target.setParent(this);
        value.setParent(this);
    }

    public Expression getTarget() {
        return target;
    }

    public String getOperator() {
        return operator;
    }

    public Expression getValue() {
        return value;
    }

    public boolean isAugmented() {
        return !operator.equals("=");
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitAssignment(this);
    }

    @Override
    public String getNodeType() {
        return "Assignment";
    }
}

