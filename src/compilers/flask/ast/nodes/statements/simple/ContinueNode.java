package compilers.flask.ast.nodes.statements.simple;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public class ContinueNode extends Statement {

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitContinue(this);
    }

    @Override
    public String getNodeType() {
        return "Continue";
    }
}

