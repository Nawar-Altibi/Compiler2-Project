package compilers.flask.ast.nodes.statements.simple;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.Collections;
import java.util.List;

public class GlobalNode extends Statement {

    private final List<String> names;  // Variable names

    public GlobalNode(List<String> names) {
        this.names = names;
    }

    // Constructor for single name
    public GlobalNode(String name) {
        this(Collections.singletonList(name));
    }

    public List<String> getNames() {
        return names;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitGlobal(this);
    }

    @Override
    public String getNodeType() {
        return "Global";
    }

    @Override
    public String toString() {
        return "Global(" + String.join(", ", names) + ")";
    }
}

