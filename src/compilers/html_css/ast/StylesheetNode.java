package compilers.html_css.ast;

import java.util.ArrayList;
import java.util.List;

public class StylesheetNode extends CssNode {
    public final List<CssNode> statements = new ArrayList<>();

    public StylesheetNode(int line, int col) {
        super(line, col);
    }

    public void add(CssNode s) { if (s != null) statements.add(s); }

    @Override public void accept(CssVisitor visitor) { visitor.visit(this); }
}










