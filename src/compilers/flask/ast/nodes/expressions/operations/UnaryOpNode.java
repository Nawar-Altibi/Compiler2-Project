package compilers.flask.ast.nodes.expressions.operations;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public class UnaryOpNode extends Expression {

    private String operator;
    private Expression operand;

    public UnaryOpNode(String operator, Expression operand) {
        this.operator = operator;
        this.operand = operand;
        operand.setParent(this);
    }

    public String getOperator() {
        return operator;
    }

    public Expression getOperand() {
        return operand;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitUnaryOp(this);
    }

    @Override
    public String getNodeType() {
        return "UnaryOp";
    }

    @Override
    public String toString() {
        return "UnaryOp(" + operator + ")";
    }
}

