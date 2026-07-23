package compilers.flask.ast.nodes.statements.compound;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ForStatementNode extends Statement {

    private final Expression target;        // Loop variable (can be tuple for unpacking)
    private final Expression iterable;      // What to iterate over
    private final List<Statement> body;     // Loop body
    private final List<Statement> elseBody; // Optional else clause (null if not present)

    public ForStatementNode(Expression target, Expression iterable,
                            List<Statement> body, List<Statement> elseBody) {
        this.target = target;
        this.iterable = iterable;
        this.body = Collections.unmodifiableList(new ArrayList<>(body));
        this.elseBody = elseBody == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(elseBody));

        // Set parents
        target.setParent(this);
        iterable.setParent(this);
        for (Statement stmt : this.body) {
            stmt.setParent(this);
        }
        if (this.elseBody != null) {
            for (Statement stmt : this.elseBody) {
                stmt.setParent(this);
            }
        }
    }

    // Constructor without else clause (most common)
    public ForStatementNode(Expression target, Expression iterable, List<Statement> body) {
        this(target, iterable, body, null);
    }

    // Getters
    public Expression getTarget() {
        return target;
    }

    public Expression getIterable() {
        return iterable;
    }

    public List<Statement> getBody() {
        return body;
    }

    public List<Statement> getElseBody() {
        return elseBody;
    }

    // Helper methods
    public boolean hasElse() {
        return elseBody != null;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitForStatement(this);
    }

    @Override
    public String getNodeType() {
        return "ForStatement";
    }

    @Override
    public String toString() {
        return "For" + (hasElse() ? " (with else)" : "");
    }
}
