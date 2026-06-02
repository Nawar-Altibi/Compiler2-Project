package compilers.flask.ast.nodes.expressions.atoms;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public class LiteralNode extends Expression {

    public enum LiteralType {
        INTEGER, FLOAT, STRING, BOOLEAN, NONE
    }

    private Object value;
    private LiteralType type;

    public LiteralNode(Object value, LiteralType type) {
        this.value = value;
        this.type = type;
    }

    // Convenience constructors
    public static LiteralNode integer(int value) {
        return new LiteralNode(value, LiteralType.INTEGER);
    }

    public static LiteralNode floatVal(double value) {
        return new LiteralNode(value, LiteralType.FLOAT);
    }

    public static LiteralNode string(String value) {
        return new LiteralNode(value, LiteralType.STRING);
    }

    public static LiteralNode bool(boolean value) {
        return new LiteralNode(value, LiteralType.BOOLEAN);
    }

    public static LiteralNode none() {
        return new LiteralNode(null, LiteralType.NONE);
    }

    public Object getValue() {
        return value;
    }

    public LiteralType getLiteralType() {
        return type;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitLiteral(this);
    }

    @Override
    public String getNodeType() {
        return "Literal";
    }

    @Override
    public String getExpressionType() {
        switch (type) {
            case INTEGER: return "int";
            case FLOAT: return "float";
            case STRING: return "str";
            case BOOLEAN: return "bool";
            case NONE: return "None";
            default: return "unknown";
        }
    }

    @Override
    public String toString() {
        return "Literal(" + type + ", " + value + ")";
    }
}

