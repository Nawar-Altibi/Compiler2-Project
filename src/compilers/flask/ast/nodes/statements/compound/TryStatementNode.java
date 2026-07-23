package compilers.flask.ast.nodes.statements.compound;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TryStatementNode extends Statement {

    private final List<Statement> tryBody;          // Try block (required)
    private final List<ExceptClause> exceptClauses; // Except blocks (can be empty if finally-only)
    private final List<Statement> elseBody;         // Else block (optional, null if not present)
    private final List<Statement> finallyBody;      // Finally block (optional, null if not present)

    public TryStatementNode(List<Statement> tryBody, List<ExceptClause> exceptClauses,
                            List<Statement> elseBody, List<Statement> finallyBody) {
        this.tryBody = Collections.unmodifiableList(new ArrayList<>(tryBody));
        this.exceptClauses = Collections.unmodifiableList(exceptClauses != null
                ? new ArrayList<>(exceptClauses)
                : new ArrayList<ExceptClause>());
        this.elseBody = elseBody == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(elseBody));
        this.finallyBody = finallyBody == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(finallyBody));

        // Set parents for try body
        for (Statement stmt : this.tryBody) {
            stmt.setParent(this);
        }

        // Set parents for except clauses
        for (ExceptClause exceptClause : this.exceptClauses) {
            if (exceptClause.getExceptionType() != null) {
                exceptClause.getExceptionType().setParent(this);
            }
            for (Statement stmt : exceptClause.getBody()) {
                stmt.setParent(this);
            }
        }

        // Set parents for else body
        if (this.elseBody != null) {
            for (Statement stmt : this.elseBody) {
                stmt.setParent(this);
            }
        }

        // Set parents for finally body
        if (this.finallyBody != null) {
            for (Statement stmt : this.finallyBody) {
                stmt.setParent(this);
            }
        }
    }

    // Constructor without else (common)
    public TryStatementNode(List<Statement> tryBody, List<ExceptClause> exceptClauses,
                            List<Statement> finallyBody) {
        this(tryBody, exceptClauses, null, finallyBody);
    }

    // Constructor for try-finally only
    public TryStatementNode(List<Statement> tryBody, List<Statement> finallyBody) {
        this(tryBody, new ArrayList<>(), null, finallyBody);
    }

    // Getters
    public List<Statement> getTryBody() {
        return tryBody;
    }

    public List<ExceptClause> getExceptClauses() {
        return exceptClauses;
    }

    public List<Statement> getElseBody() {
        return elseBody;
    }

    public List<Statement> getFinallyBody() {
        return finallyBody;
    }

    // Helper methods
    public boolean hasExcept() {
        return !exceptClauses.isEmpty();
    }

    public boolean hasElse() {
        return elseBody != null;
    }

    public boolean hasFinally() {
        return finallyBody != null;
    }

    public int getExceptClauseCount() {
        return exceptClauses.size();
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitTryStatement(this);
    }

    @Override
    public String getNodeType() {
        return "TryStatement";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Try");
        if (hasExcept()) {
            sb.append(" (").append(exceptClauses.size()).append(" except)");
        }
        if (hasElse()) {
            sb.append(" (with else)");
        }
        if (hasFinally()) {
            sb.append(" (with finally)");
        }
        return sb.toString();
    }
}
