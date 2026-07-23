package compilers.flask.ast.nodes.statements.compound;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IfStatementNode extends Statement {

    /**
     * Helper class for elif clauses
     */
    public static class ElifClause {
        private final Expression condition;
        private final List<Statement> body;
        private final SourceSpan span;

        public ElifClause(Expression condition, List<Statement> body) {
            this(condition, body, SourceSpan.UNKNOWN);
        }

        public ElifClause(
                Expression condition, List<Statement> body, SourceSpan span) {
            this.condition = condition;
            this.body = Collections.unmodifiableList(new ArrayList<>(body));
            this.span = span == null ? SourceSpan.UNKNOWN : span;
        }

        public Expression getCondition() {
            return condition;
        }

        public List<Statement> getBody() {
            return body;
        }

        public SourceSpan getSpan() {
            return span;
        }

        @Override
        public String toString() {
            return "elif " + condition;
        }
    }

    private final Expression condition;         // Main if condition
    private final List<Statement> thenBody;     // Statements in if block
    private final List<ElifClause> elifClauses; // elif blocks
    private final List<Statement> elseBody;     // else block (null if not present)

    public IfStatementNode(Expression condition, List<Statement> thenBody,
                           List<ElifClause> elifClauses, List<Statement> elseBody) {
        this.condition = condition;
        this.thenBody = Collections.unmodifiableList(new ArrayList<>(thenBody));
        this.elifClauses = Collections.unmodifiableList(elifClauses != null
                ? new ArrayList<>(elifClauses)
                : new ArrayList<ElifClause>());
        this.elseBody = elseBody == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(elseBody));

        // Set parents
        condition.setParent(this);
        for (Statement stmt : this.thenBody) {
            stmt.setParent(this);
        }
        for (ElifClause elifClause : this.elifClauses) {
            elifClause.condition.setParent(this);
            for (Statement stmt : elifClause.body) {
                stmt.setParent(this);
            }
        }
        if (this.elseBody != null) {
            for (Statement stmt : this.elseBody) {
                stmt.setParent(this);
            }
        }
    }

    // Constructor without elif and else
    public IfStatementNode(Expression condition, List<Statement> thenBody) {
        this(condition, thenBody, new ArrayList<>(), null);
    }

    // Getters
    public Expression getCondition() {
        return condition;
    }

    public List<Statement> getThenBody() {
        return thenBody;
    }

    public List<ElifClause> getElifClauses() {
        return elifClauses;
    }

    public List<Statement> getElseBody() {
        return elseBody;
    }

    // Helper methods
    public boolean hasElif() {
        return !elifClauses.isEmpty();
    }

    public boolean hasElse() {
        return elseBody != null;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitIfStatement(this);
    }

    @Override
    public String getNodeType() {
        return "IfStatement";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("If");
        if (hasElif()) {
            sb.append(" (").append(elifClauses.size()).append(" elif)");
        }
        if (hasElse()) {
            sb.append(" (with else)");
        }
        return sb.toString();
    }
}
