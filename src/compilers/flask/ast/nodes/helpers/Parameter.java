package compilers.flask.ast.nodes.helpers;

import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.SourceSpan;
public class Parameter {

    private final String name;
    private final Expression defaultValue;  // null if no default
    private final Expression typeHint;      // null if no type hint
    private final SourceSpan span;
    private final SourceSpan nameSpan;

    // Constructor with all fields
    public Parameter(String name, Expression defaultValue, Expression typeHint) {
        this(name, defaultValue, typeHint, SourceSpan.UNKNOWN, SourceSpan.UNKNOWN);
    }

    public Parameter(
            String name,
            Expression defaultValue,
            Expression typeHint,
            SourceSpan span,
            SourceSpan nameSpan) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Parameter name cannot be empty");
        }
        this.name = name;
        this.defaultValue = defaultValue;
        this.typeHint = typeHint;
        this.span = span == null ? SourceSpan.UNKNOWN : span;
        this.nameSpan = nameSpan == null ? SourceSpan.UNKNOWN : nameSpan;
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

    public SourceSpan getSpan() {
        return span;
    }

    public SourceSpan getNameSpan() {
        return nameSpan;
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
