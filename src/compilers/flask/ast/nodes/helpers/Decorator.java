package compilers.flask.ast.nodes.helpers;

import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.expressions.access.FunctionCallNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @deprecated Use {@link DecoratorNode}. This adapter keeps the previous
 * construction/access API available while storing a complete expression.
 */
@Deprecated
public class Decorator extends DecoratorNode {

    // Constructor with all fields
    public Decorator(Expression name, List<Expression> args, Map<String, Expression> kwargs) {
        super(buildExpression(name, args, kwargs));
    }

    // Constructor without kwargs (most common)
    public Decorator(Expression name, List<Expression> args) {
        this(name, args, Collections.<String, Expression>emptyMap());
    }

    // Constructor for simple decorator (no args)
    public Decorator(Expression name) {
        super(name);
    }

    // Getters
    public Expression getName() {
        if (getExpression() instanceof FunctionCallNode) {
            return ((FunctionCallNode) getExpression()).getFunction();
        }
        return getExpression();
    }

    public List<Expression> getArgs() {
        if (getExpression() instanceof FunctionCallNode) {
            return ((FunctionCallNode) getExpression()).getArgs();
        }
        return Collections.emptyList();
    }

    public Map<String, Expression> getKwargs() {
        if (getExpression() instanceof FunctionCallNode) {
            return ((FunctionCallNode) getExpression()).getKwargs();
        }
        return Collections.emptyMap();
    }

    // Helper methods
    public boolean hasArgs() {
        return !getArgs().isEmpty();
    }

    public boolean hasKwargs() {
        return !getKwargs().isEmpty();
    }

    public boolean hasArguments() {
        return hasArgs() || hasKwargs();
    }

    public boolean isCall() {
        return getExpression() instanceof FunctionCallNode;
    }

    private static Expression buildExpression(
            Expression name, List<Expression> args, Map<String, Expression> kwargs) {
        List<CallArgument> arguments = new ArrayList<>();
        if (args != null) {
            for (Expression argument : args) {
                arguments.add(CallArgument.positional(argument));
            }
        }
        if (kwargs != null) {
            for (Map.Entry<String, Expression> argument : kwargs.entrySet()) {
                arguments.add(CallArgument.keyword(argument.getKey(), argument.getValue()));
            }
        }
        return new FunctionCallNode(name, arguments);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("@");
        sb.append(getName());
        if (isCall()) {
            sb.append("(");
            // Args
            List<Expression> args = getArgs();
            Map<String, Expression> kwargs = getKwargs();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(args.get(i));
            }
            // Kwargs
            if (hasArgs() && hasKwargs()) sb.append(", ");
            int i = 0;
//            for (var entry : kwargs.entrySet()) {
//                if (i++ > 0) sb.append(", ");
//                sb.append(entry.getKey()).append("=").append(entry.getValue());
//            }
            for (Map.Entry<String, Expression> entry : kwargs.entrySet()) {
                if (i++ > 0) sb.append(", ");
                sb.append(entry.getKey())
                        .append("=")
                        .append(entry.getValue());
            }

            sb.append(")");
        }
        return sb.toString();
    }
}
