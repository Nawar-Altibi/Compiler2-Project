package compilers.html_css.ast;

import java.util.ArrayList;
import java.util.List;

public class SimpleSelectorSequenceNode extends CssNode {
    public final List<String> items = new ArrayList<>();

    public SimpleSelectorSequenceNode(int line, int col) { super(line, col); }
    @Override public void accept(CssVisitor visitor) { visitor.visit(this); }
}









