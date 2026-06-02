package compilers.flask.ast.nodes.helpers;

import compilers.flask.ast.nodes.Expression;
public class Parameter {

    private final String name;
    private final Expression defaultValue;  // null if no default
    private final Expression typeHint;      // null if no type hint

    // Constructor with all fields
    public Parameter(String name, Expression defaultValue, Expression typeHint) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.typeHint = typeHint;
    }

    // Constructor without type hint (most common)
    public Parameter(String name, Expression defaultValue) {
        this(name, defaultValue, null);
    }

    // Constructor for simple parameter (no default, no type)
    public Parameter(String name) {
        this(name, null, null);
    }

    // Getters
    public String getName() {
        return name;
    }

    public Expression getDefaultValue() {
        return defaultValue;
    }

    public Expression getTypeHint() {
        return typeHint;
    }

    // Helper methods
    public boolean hasDefault() {
        return defaultValue != null;
    }

    public boolean hasTypeHint() {
        return typeHint != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Parameter(");
        sb.append(name);
        if (typeHint != null) {
            sb.append(": ").append(typeHint);
        }
        if (defaultValue != null) {
            sb.append("=").append(defaultValue);
        }
        sb.append(")");
        return sb.toString();
    }
}
