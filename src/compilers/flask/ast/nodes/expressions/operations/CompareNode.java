package compilers.flask.ast.nodes.expressions.operations;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.Collections;
import java.util.List;

public class CompareNode extends Expression {

    /**
     * Comparison operators enum
     */
    public enum CompareOp {
        EQ("=="),       // Equal
        NEQ("!="),      // Not equal
        LT("<"),        // Less than
        LTE("<="),      // Less than or equal
        GT(">"),        // Greater than
        GTE(">="),      // Greater than or equal
        IN("in"),       // Membership
        IS("is");       // Identity

        private final String symbol;

        CompareOp(String symbol) {
            this.symbol = symbol;
        }

        public String getSymbol() {
            return symbol;
        }

        @Override
        public String toString() {
            return symbol;
        }
    }

    private final Expression left;              // Left expression
    private final List<CompareOp> operators;    // Comparison operators
    private final List<Expression> comparators; // Right-side expressions

    public CompareNode(Expression left, List<CompareOp> operators, List<Expression> comparators) {
        this.left = left;
        this.operators = operators;
        this.comparators = comparators;

        // Set parent
        left.setParent(this);
        for (Expression comparator : comparators) {
            comparator.setParent(this);
        }

        // Validation: operators and comparators must have same size
        if (operators.size() != comparators.size()) {
            throw new IllegalArgumentException(
                    "CompareNode: operators count must equal comparators count. " +
                            "Got " + operators.size() + " operators and " + comparators.size() + " comparators"
            );
        }
    }

    // Constructor for simple (non-chained) comparison
    public CompareNode(Expression left, CompareOp operator, Expression right) {
        this(left, Collections.singletonList(operator), Collections.singletonList(right));
    }

    public Expression getLeft() {
        return left;
    }

    public List<CompareOp> getOperators() {
        return operators;
    }

    public List<Expression> getComparators() {
        return comparators;
    }

    // Helper methods
    public boolean isChained() {
        return operators.size() > 1;
    }

    public int getComparisonCount() {
        return operators.size();
    }

    /**
     * Get comparison at specific index
     * For x < y < z: index 0 is (x < y), index 1 is (y < z)
     */
    public CompareOp getOperator(int index) {
        return operators.get(index);
    }

    public Expression getComparator(int index) {
        return comparators.get(index);
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitCompare(this);
    }

    @Override
    public String getNodeType() {
        return "Compare";
    }

    @Override
    public String toString() {
        if (isChained()) {
            return "Compare(chained: " + operators.size() + " ops)";
        } else {
            return "Compare(" + operators.get(0) + ")";
        }
    }
}

