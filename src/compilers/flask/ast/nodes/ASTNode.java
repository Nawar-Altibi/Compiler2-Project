package compilers.flask.ast.nodes;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

/**
 * Base class for all AST nodes
 * Every node in the Abstract Syntax Tree extends this class
 */
public abstract class ASTNode {

    // Source location information. SourceSpan is authoritative; the legacy
    // line/column API remains as a compatibility view of its start position.
    private SourceSpan sourceSpan = SourceSpan.UNKNOWN;
    private ASTNode parent;
    private SymbolTable scope;

    public int getLine() {
        return sourceSpan.getStartLine();
    }

    public void setLine(int line) {
        if (line < 0) {
            throw new IllegalArgumentException("Source line cannot be negative");
        }
        if (line == 0) {
            sourceSpan = SourceSpan.UNKNOWN;
            return;
        }
        int column = sourceSpan.isKnown() ? sourceSpan.getStartColumn() : 0;
        sourceSpan = SourceSpan.point(sourceSpan.getSourceFile(), line, column);
    }

    public int getColumn() {
        return sourceSpan.getStartColumn();
    }

    public void setColumn(int column) {
        if (column < 0) {
            throw new IllegalArgumentException("Source column cannot be negative");
        }
        int line = sourceSpan.isKnown() ? sourceSpan.getStartLine() : 1;
        sourceSpan = SourceSpan.point(sourceSpan.getSourceFile(), line, column);
    }

    public SourceSpan getSourceSpan() {
        return sourceSpan;
    }

    /** Short alias useful to source-map consumers. */
    public SourceSpan getSpan() {
        return sourceSpan;
    }

    public void setSourceSpan(SourceSpan sourceSpan) {
        this.sourceSpan = sourceSpan == null ? SourceSpan.UNKNOWN : sourceSpan;
    }

    /** Short alias useful to AST builders. */
    public void setSpan(SourceSpan sourceSpan) {
        setSourceSpan(sourceSpan);
    }

    public ASTNode getParent() {
        return parent;
    }

    public void setParent(ASTNode parent) {
        this.parent = parent;
    }

    public SymbolTable getScope() {
        return scope;
    }

    public void setScope(SymbolTable scope) {
        this.scope = scope;
    }

    /**
     * Accept a visitor
     */
    public abstract <T> T accept(ASTVisitor<T> visitor);

    /**
     * Get the type of this node as a string
     */
    public abstract String getNodeType();

    /**
     * Check if this node is a statement
     */
    public boolean isStatement() {
        return this instanceof Statement;
    }

    /**
     * Check if this node is an expression
     */
    public boolean isExpression() {
        return this instanceof Expression;
    }
}
