package compilers.flask.ast.nodes.statements.simple;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public class RaiseNode extends Statement {

    private final Expression exception;  // Exception to raise (null for bare raise)
    private final Expression cause;      // Optional "from" cause (null if not present)

    public RaiseNode(Expression exception, Expression cause) {
        this.exception = exception;
        this.cause = cause;

        if (exception != null) {
            exception.setParent(this);
        }
        if (cause != null) {
            cause.setParent(this);
        }
    }

    // Constructor without cause
    public RaiseNode(Expression exception) {
        this(exception, null);
    }

    // Constructor for bare raise
    public RaiseNode() {
        this(null, null);
    }

    public Expression getException() {
        return exception;
    }

    public Expression getCause() {
        return cause;
    }

    public boolean isBareRaise() {
        return exception == null;
    }

    public boolean hasCause() {
        return cause != null;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitRaise(this);
    }

    @Override
    public String getNodeType() {
        return "Raise";
    }

    @Override
    public String toString() {
        if (isBareRaise()) {
            return "Raise(bare)";
        } else if (hasCause()) {
            return "Raise(with cause)";
        } else {
            return "Raise";
        }
    }
}

