package compilers.flask.ast.nodes.statements.simple;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public class AssertNode extends Statement {

    private final Expression test;       // Condition to assert
    private final Expression message;    // Optional error message (null if not present)

    public AssertNode(Expression test, Expression message) {
        this.test = test;
        this.message = message;

        test.setParent(this);
        if (message != null) {
            message.setParent(this);
        }
    }

    // Constructor without message
    public AssertNode(Expression test) {
        this(test, null);
    }

    public Expression getTest() {
        return test;
    }

    public Expression getMessage() {
        return message;
    }

    public boolean hasMessage() {
        return message != null;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitAssert(this);
    }

    @Override
    public String getNodeType() {
        return "Assert";
    }

    @Override
    public String toString() {
        return "Assert(" + (hasMessage() ? "with message" : "no message") + ")";
    }
}

