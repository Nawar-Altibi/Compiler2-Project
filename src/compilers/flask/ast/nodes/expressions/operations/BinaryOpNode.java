package compilers.flask.ast.nodes.expressions.operations;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public class BinaryOpNode extends Expression {

    private Expression left;
    private String operator;
    private Expression right;

    public BinaryOpNode(Expression left, String operator, Expression right) {
        this.left = left;
        this.operator = operator;
        this.right = right;

        left.setParent(this);
        right.setParent(this);
    }

    public Expression getLeft() {
        return left;
    }

    public String getOperator() {
        return operator;
    }

    public Expression getRight() {
        return right;
    }

    public boolean isArithmetic() {
        return operator.matches("[+\\-*/%]|//|\\*\\*");
    }

    public boolean isComparison() {
        return operator.matches("==|!=|<|>|<=|>=");
    }

    public boolean isLogical() {
        return operator.equals("and") || operator.equals("or");
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitBinaryOp(this);
    }

    @Override
    public String getNodeType() {
        return "BinaryOp";
    }

    @Override
    public String toString() {
        return "BinaryOp(" + operator + ")";
    }
}

