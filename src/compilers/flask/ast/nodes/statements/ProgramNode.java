package compilers.flask.ast.nodes.statements;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.ArrayList;
import java.util.List;

public class ProgramNode extends ASTNode {

    private List<Statement> statements;

    public ProgramNode() {
        this.statements = new ArrayList<>();
    }

    public ProgramNode(List<Statement> statements) {
        this.statements = statements;
        // Set parent for all statements
        for (Statement stmt : statements) {
            stmt.setParent(this);
        }
    }

    public void addStatement(Statement stmt) {
        this.statements.add(stmt);
        stmt.setParent(this);
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

