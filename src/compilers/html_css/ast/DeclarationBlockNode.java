package compilers.html_css.ast;

import java.util.ArrayList;
import java.util.List;

public class DeclarationBlockNode extends CssNode {
    public final List<DeclarationNode> declarations = new ArrayList<>();
    public DeclarationBlockNode(int line, int col) { super(line, col); }
    public void add(DeclarationNode d) { if (d != null) declarations.add(d); }
    @Override public void accept(CssVisitor visitor) { visitor.visit(this); }
}











