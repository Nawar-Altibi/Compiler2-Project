package compilers.html_css.ast;

import java.util.ArrayList;
import java.util.List;

public class ExprNode extends CssNode {
    public final List<TermNode> terms = new ArrayList<>();
    public final List<String> operators = new ArrayList<>();

    public ExprNode(int line, int col) { super(line, col); }
    @Override public void accept(CssVisitor visitor) { visitor.visit(this); }
}









