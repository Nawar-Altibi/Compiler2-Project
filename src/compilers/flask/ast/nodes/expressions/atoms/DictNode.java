package compilers.flask.ast.nodes.expressions.atoms;

import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DictNode extends Expression {

    /**
     * Helper class for dictionary key-value pairs
     */
    public static class DictItem {
        private final Expression key;
        private final Expression value;
        private final SourceSpan span;

        public DictItem(Expression key, Expression value) {
            this(key, value, SourceSpan.UNKNOWN);
        }

        public DictItem(Expression key, Expression value, SourceSpan span) {
            this.key = key;
            this.value = value;
            this.span = span == null ? SourceSpan.UNKNOWN : span;
        }

        public Expression getKey() {
            return key;
        }

        public Expression getValue() {
            return value;
        }

        public SourceSpan getSpan() {
            return span;
        }

        @Override
        public String toString() {
            return key + ": " + value;
        }
    }

    private final List<DictItem> items;

    public DictNode(List<DictItem> items) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        // Set parent for all keys and values
        for (DictItem item : this.items) {
            item.key.setParent(this);
            item.value.setParent(this);
        }
    }

    // Constructor for empty dict
    public DictNode() {
        this(new ArrayList<>());
    }

    public List<DictItem> getItems() {
        return items;
    }

    // Helper methods
    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitDict(this);
    }

    @Override
    public String getNodeType() {
        return "Dict";
    }

    @Override
    public String getExpressionType() {
        return "dict";
    }

    @Override
    public String toString() {
        return "Dict(" + items.size() + " items)";
    }
}
