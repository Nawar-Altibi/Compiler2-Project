package compilers.flask.ast.nodes.expressions.access;

import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FunctionCallNode extends Expression {

    private final Expression function;  // Can be Identifier or AttributeAccess
    private final List<CallArgument> arguments;

    public FunctionCallNode(Expression function) {
        this(function, Collections.<CallArgument>emptyList());
    }

    public FunctionCallNode(Expression function, List<CallArgument> arguments) {
        if (function == null) {
            throw new IllegalArgumentException("Called expression cannot be null");
        }
        this.function = function;
        function.setParent(this);

        List<CallArgument> argumentCopy = new ArrayList<>();
        if (arguments != null) {
            for (CallArgument argument : arguments) {
                if (argument == null) {
                    throw new IllegalArgumentException("Call argument cannot be null");
                }
                argumentCopy.add(argument);
                argument.getValue().setParent(this);
            }
        }
        this.arguments = Collections.unmodifiableList(argumentCopy);
    }

    public Expression getFunction() {
        return function;
    }

    /** Ordered, immutable canonical representation. */
    public List<CallArgument> getArguments() {
        return arguments;
    }

    /** Derived positional-only compatibility view. */
    public List<Expression> getArgs() {
        List<Expression> result = new ArrayList<>();
        for (CallArgument argument : arguments) {
            if (argument.isPositional()) {
                result.add(argument.getValue());
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Derived keyword-only compatibility view. Duplicate names retain their
     * final value in this map; use {@link #getArguments()} when duplicates or
     * exact source order matter.
     */
    public Map<String, Expression> getKwargs() {
        Map<String, Expression> result = new LinkedHashMap<>();
        for (CallArgument argument : arguments) {
            if (argument.isKeyword()) {
                result.put(argument.getKeywordName(), argument.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitFunctionCall(this);
    }

    @Override
    public String getNodeType() {
        return "FunctionCall";
    }
}
