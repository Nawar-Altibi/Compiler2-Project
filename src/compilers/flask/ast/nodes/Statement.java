package compilers.flask.ast.nodes;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public abstract class Statement extends ASTNode {

    @Override
    public abstract <T> T accept(ASTVisitor<T> visitor);

    @Override
    public abstract String getNodeType();
}

