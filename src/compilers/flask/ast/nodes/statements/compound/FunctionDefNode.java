package compilers.flask.ast.nodes.statements.compound;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.ArrayList;
import java.util.List;

public class FunctionDefNode extends Statement {

    private final String name;
    private final List<Parameter> parameters;
    private final List<Statement> body;
    private final List<DecoratorNode> decorators;
    private final Expression returnType;  // Optional return type hint
    private final SourceSpan nameSpan;

    public FunctionDefNode(String name, List<Parameter> parameters,
                           List<Statement> body,
                           List<? extends DecoratorNode> decorators,
                           Expression returnType) {
        this(name, parameters, body, decorators, returnType, SourceSpan.UNKNOWN);
    }

    public FunctionDefNode(String name, List<Parameter> parameters,
                           List<Statement> body,
                           List<? extends DecoratorNode> decorators,
                           Expression returnType,
                           SourceSpan nameSpan) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Function name cannot be empty");
        }
        this.name = name;
        this.parameters = new ArrayList<>(parameters);
        this.body = new ArrayList<>(body);
        this.decorators = new ArrayList<>(decorators);
        this.returnType = returnType;
        this.nameSpan = nameSpan == null ? SourceSpan.UNKNOWN : nameSpan;

        // Set parent for all statements in body
        for (Statement stmt : this.body) {
            stmt.setParent(this);
        }

        for (Parameter parameter : this.parameters) {
            if (parameter.hasDefault()) {
                parameter.getDefaultValue().setParent(this);
            }
            if (parameter.hasTypeHint()) {
                parameter.getTypeHint().setParent(this);
            }
        }

        for (DecoratorNode decorator : this.decorators) {
            decorator.getExpression().setParent(this);
        }

        // Set parent for return type if present
        if (returnType != null) {
            returnType.setParent(this);
        }
    }

    // Constructor without return type (most common)
    public FunctionDefNode(String name, List<Parameter> parameters,
                           List<Statement> body,
                           List<? extends DecoratorNode> decorators) {
        this(name, parameters, body, decorators, null);
    }

    // Constructor without decorators
    public FunctionDefNode(String name, List<Parameter> parameters, List<Statement> body) {
        this(name, parameters, body, new ArrayList<>(), null);
    }

    // Getters
    public String getName() {
        return name;
    }

    public List<Parameter> getParameters() {
        return java.util.Collections.unmodifiableList(parameters);
    }

    public List<Statement> getBody() {
        return java.util.Collections.unmodifiableList(body);
    }

    public List<DecoratorNode> getDecorators() {
        return java.util.Collections.unmodifiableList(decorators);
    }

    public Expression getReturnType() {
        return returnType;
    }

    public SourceSpan getNameSpan() {
        return nameSpan;
    }

    // Helper methods
    public boolean hasParameters() {
        return !parameters.isEmpty();
    }

    public boolean hasDecorators() {
        return !decorators.isEmpty();
    }

    public boolean hasReturnType() {
        return returnType != null;
    }

    public int getParameterCount() {
        return parameters.size();
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitFunctionDef(this);
    }

    @Override
    public String getNodeType() {
        return "FunctionDef";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("FunctionDef(");
        sb.append(name);
        sb.append(", ").append(parameters.size()).append(" params");
        if (hasDecorators()) {
            sb.append(", ").append(decorators.size()).append(" decorators");
        }
        sb.append(")");
        return sb.toString();
    }
}
