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
    private final List<Decorator> decorators;   // Decorators (can be empty)

    public ClassDefNode(String name, List<Expression> bases,
                        List<Statement> body, List<Decorator> decorators) {
        this.name = name;
        this.bases = bases != null ? bases : new ArrayList<>();
        this.body = body;
        this.decorators = decorators != null ? decorators : new ArrayList<>();

        // Set parents for base classes
        for (Expression base : this.bases) {
            base.setParent(this);
        }

        // Set parents for body statements
        for (Statement stmt : body) {
            stmt.setParent(this);
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
        return bases;
    }

    public List<Statement> getBody() {
        return body;
    }

    public List<Decorator> getDecorators() {
        return decorators;
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

