package compilers.flask.ast.nodes.statements.compound;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.List;

public class WhileStatementNode extends Statement {

    private final Expression condition;      // Loop condition
    private final List<Statement> body;      // Loop body
    private final List<Statement> elseBody;  // Optional else clause (null if not present)

    public WhileStatementNode(Expression condition, List<Statement> body,
                              List<Statement> elseBody) {
        this.condition = condition;
        this.body = body;
        this.elseBody = elseBody;

        // Set parents
        condition.setParent(this);
        for (Statement stmt : body) {
            stmt.setParent(this);
        }
        if (elseBody != null) {
            for (Statement stmt : elseBody) {
                stmt.setParent(this);
            }
        }
    }

    // Constructor without else clause (most common)
    public WhileStatementNode(Expression condition, List<Statement> body) {
        this(condition, body, null);
    }

    // Getters
    public Expression getCondition() {
        return condition;
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
        return visitor.visitWhileStatement(this);
    }

    @Override
    public String getNodeType() {
        return "WhileStatement";
    }

    @Override
    public String toString() {
        return "While" + (hasElse() ? " (with else)" : "");
    }
}

