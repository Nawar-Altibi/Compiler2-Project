package compilers.flask.ast.nodes.statements;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProgramNode extends ASTNode {

    private final List<Statement> statements;

    public ProgramNode() {
        this(Collections.<Statement>emptyList());
    }

    public ProgramNode(List<Statement> statements) {
        this.statements = Collections.unmodifiableList(new ArrayList<>(statements));
        // Set parent for all statements
        for (Statement stmt : statements) {
            stmt.setParent(this);
        }
    }

    public List<Statement> getStatements() {
        return statements;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitProgram(this);
    }

    @Override
    public String getNodeType() {
        return "Program";
    }
}
