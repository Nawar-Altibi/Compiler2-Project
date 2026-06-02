package compilers.flask.ast.nodes.helpers;

import compilers.flask.ast.nodes.Expression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Decorator {

    private final Expression name;              // Decorator name (can be dotted: app.route)
    private final List<Expression> args;        // Positional arguments
    private final Map<String, Expression> kwargs; // Keyword arguments

    // Constructor with all fields
    public Decorator(Expression name, List<Expression> args, Map<String, Expression> kwargs) {
        this.name = name;
        this.args = args != null ? args : new ArrayList<>();
        this.kwargs = kwargs != null ? kwargs : new HashMap<>();
    }

    // Constructor without kwargs (most common)
    public Decorator(Expression name, List<Expression> args) {
        this(name, args, new HashMap<>());
    }

    // Constructor for simple decorator (no args)
    public Decorator(Expression name) {
        this(name, new ArrayList<>(), new HashMap<>());
    }

    // Getters
    public Expression getName() {
        return name;
    }

    public List<Expression> getArgs() {
        return args;
    }

    public Map<String, Expression> getKwargs() {
        return kwargs;
    }

    // Helper methods
    public boolean hasArgs() {
        return !args.isEmpty();
    }

    public boolean hasKwargs() {
        return !kwargs.isEmpty();
    }

    public boolean hasArguments() {
        return hasArgs() || hasKwargs();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("@");
        sb.append(name);
        if (hasArguments()) {
            sb.append("(");
            // Args
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
