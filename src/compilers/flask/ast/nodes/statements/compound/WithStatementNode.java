package compilers.flask.ast.nodes.statements.compound;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WithStatementNode extends Statement {

    private final List<WithItem> items;  // Context managers
    private final List<Statement> body;  // With body

    public WithStatementNode(List<WithItem> items, List<Statement> body) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.body = Collections.unmodifiableList(new ArrayList<>(body));

        // Set parents for body statements
        for (Statement stmt : this.body) {
            stmt.setParent(this);
        }

        // Set parents for context expressions and as names
        for (WithItem item : this.items) {
            item.getContextExpr().setParent(this);
            if (item.getAsName() != null) {
                item.getAsName().setParent(this);
            }
        }
    }

    // Constructor for single item (most common)
    public WithStatementNode(WithItem item, List<Statement> body) {
        this(Collections.singletonList(item), body);
    }

    // Getters
    public List<WithItem> getItems() {
        return items;
    }

    public List<Statement> getBody() {
        return body;
    }

    // Helper methods
    public boolean hasMultipleItems() {
        return items.size() > 1;
    }

    public int getItemCount() {
        return items.size();
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitWithStatement(this);
    }

    @Override
    public String getNodeType() {
        return "WithStatement";
    }

    @Override
    public String toString() {
        return "With (" + items.size() + " context" + (items.size() > 1 ? "s" : "") + ")";
    }
}
