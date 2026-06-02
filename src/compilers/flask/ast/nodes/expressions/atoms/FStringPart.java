package compilers.flask.ast.nodes.expressions.atoms;

import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

/**
 * Base class for F-string parts (either string literal or expression)
 */
public abstract class FStringPart {
    
    public enum PartType {
        STRING,      // String literal part
        EXPRESSION   // Expression inside {}
    }
    
    private final PartType type;
    
    protected FStringPart(PartType type) {
        this.type = type;
    }
    
    public PartType getType() {
        return type;
    }
    
    /**
     * String part - contains literal text
     */
    public static class StringPart extends FStringPart {
        private final String value;
        
        public StringPart(String value) {
            super(PartType.STRING);
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
    }
    
    /**
     * Expression part - contains an expression inside {}
     */
    public static class ExpressionPart extends FStringPart {
        private final Expression expression;
        
        public ExpressionPart(Expression expression) {
            super(PartType.EXPRESSION);
            this.expression = expression;
            if (expression != null) {
                expression.setParent(null); // Will be set by FStringNode
            }
        }
        
        public Expression getExpression() {
            return expression;
        }
    }
}


