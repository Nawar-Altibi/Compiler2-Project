package compilers.flask.ast.nodes;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

/**
 * Base class for all AST nodes
 * Every node in the Abstract Syntax Tree extends this class
 */
public abstract class ASTNode {

    // Source location information
    private int line;
    private int column;
    private ASTNode parent;

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public int getColumn() {
        return column;
    }

    public void setColumn(int column) {
        this.column = column;
    }

    public ASTNode getParent() {
        return parent;
    }

    public void setParent(ASTNode parent) {
        this.parent = parent;
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
