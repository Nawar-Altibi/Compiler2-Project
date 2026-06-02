package compilers.html_css.ast;

import java.util.ArrayList;
import java.util.List;

public class ValueNode extends CssNode {
    public final List<ExprNode> groups = new ArrayList<>();
    public ValueNode(int line, int col) { super(line, col); }
    @Override public void accept(CssVisitor visitor) { visitor.visit(this); }
}









