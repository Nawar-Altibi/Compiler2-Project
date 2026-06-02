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
    private final List<Decorator> decorators;
    private final Expression returnType;  // Optional return type hint

    public FunctionDefNode(String name, List<Parameter> parameters,
                           List<Statement> body, List<Decorator> decorators,
                           Expression returnType) {
        this.name = name;
        this.parameters = parameters;
        this.body = body;
        this.decorators = decorators;
        this.returnType = returnType;

        // Set parent for all statements in body
        for (Statement stmt : body) {
            stmt.setParent(this);
        }

        // Set parent for return type if present
        if (returnType != null) {
            returnType.setParent(this);
        }
    }

    // Constructor without return type (most common)
    public FunctionDefNode(String name, List<Parameter> parameters,
                           List<Statement> body, List<Decorator> decorators) {
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
        return parameters;
    }

    public List<Statement> getBody() {
        return body;
    }

    public List<Decorator> getDecorators() {
        return decorators;
    }

    public Expression getReturnType() {
        return returnType;
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

