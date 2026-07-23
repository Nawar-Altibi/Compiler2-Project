package compilers.flask.ast.nodes.statements.simple;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DelNode extends Statement {

    private final List<Expression> targets;

    public DelNode(List<Expression> targets) {
        this.targets = Collections.unmodifiableList(new ArrayList<>(targets));
        for (Expression target : this.targets) {
            target.setParent(this);
        }
    }

    public DelNode(Expression target) {
        this(Collections.singletonList(target));
    }

    public List<Expression> getTargets() {
        return targets;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitDel(this);
    }

    @Override
    public String getNodeType() {
        return "Del";
    }

    @Override
    public String toString() {
        return "Del(" + targets.size() + " targets)";
    }
}
