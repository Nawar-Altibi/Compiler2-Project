package compilers.flask.ast.nodes;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public abstract class Expression extends ASTNode {

    @Override
    public abstract <T> T accept(ASTVisitor<T> visitor);

    @Override
    public abstract String getNodeType();

    public String getExpressionType() {
        return "unknown";
    }

}

