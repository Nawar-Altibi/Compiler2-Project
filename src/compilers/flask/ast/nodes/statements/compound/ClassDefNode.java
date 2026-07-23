package compilers.flask.ast.nodes.statements.compound;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.ArrayList;
import java.util.List;

public class ClassDefNode extends Statement {

    private final String name;                  // Class name
    private final List<Expression> bases;       // Base classes (can be empty)
    private final List<Statement> body;         // Class body
    private final List<DecoratorNode> decorators;   // Decorators (can be empty)
    private final SourceSpan nameSpan;

    public ClassDefNode(String name, List<Expression> bases,
                        List<Statement> body,
                        List<? extends DecoratorNode> decorators) {
        this(name, bases, body, decorators, SourceSpan.UNKNOWN);
    }

    public ClassDefNode(String name, List<Expression> bases,
                        List<Statement> body,
                        List<? extends DecoratorNode> decorators,
                        SourceSpan nameSpan) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Class name cannot be empty");
        }
        this.name = name;
        this.bases = bases != null ? new ArrayList<>(bases) : new ArrayList<>();
        this.body = new ArrayList<>(body);
        this.decorators = decorators != null
                ? new ArrayList<>(decorators)
                : new ArrayList<>();
        this.nameSpan = nameSpan == null ? SourceSpan.UNKNOWN : nameSpan;

        // Set parents for base classes
        for (Expression base : this.bases) {
            base.setParent(this);
        }

        // Set parents for body statements
        for (Statement stmt : this.body) {
            stmt.setParent(this);
        }

        for (DecoratorNode decorator : this.decorators) {
            decorator.getExpression().setParent(this);
        }
    }

    // Constructor without decorators (most common)
    public ClassDefNode(String name, List<Expression> bases, List<Statement> body) {
        this(name, bases, body, new ArrayList<>());
    }

    // Constructor without bases (simple class)
    public ClassDefNode(String name, List<Statement> body) {
        this(name, new ArrayList<>(), body, new ArrayList<>());
    }

    // Getters
    public String getName() {
        return name;
    }

    public List<Expression> getBases() {
        return java.util.Collections.unmodifiableList(bases);
    }

    public List<Statement> getBody() {
        return java.util.Collections.unmodifiableList(body);
    }

    public List<DecoratorNode> getDecorators() {
        return java.util.Collections.unmodifiableList(decorators);
    }

    public SourceSpan getNameSpan() {
        return nameSpan;
    }

    // Helper methods
    public boolean hasBases() {
        return !bases.isEmpty();
    }

    public boolean hasDecorators() {
        return !decorators.isEmpty();
    }

    public int getBaseCount() {
        return bases.size();
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitClassDef(this);
    }

    @Override
    public String getNodeType() {
        return "ClassDef";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ClassDef(");
        sb.append(name);
        if (hasBases()) {
            sb.append(", ").append(bases.size()).append(" base");
            if (bases.size() > 1) sb.append("s");
        }
        if (hasDecorators()) {
            sb.append(", ").append(decorators.size()).append(" decorator");
            if (decorators.size() > 1) sb.append("s");
        }
        sb.append(")");
        return sb.toString();
    }
}
