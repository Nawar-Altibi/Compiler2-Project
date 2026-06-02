package compilers.html_css.ast;

import java.util.ArrayList;
import java.util.List;

public class SelectorGroupNode extends CssNode {
    public final List<SelectorNode> selectors = new ArrayList<>();
    public SelectorGroupNode(int line, int col) { super(line, col); }
    public void add(SelectorNode s) { if (s != null) selectors.add(s); }
    @Override public void accept(CssVisitor visitor) { visitor.visit(this); }
}











