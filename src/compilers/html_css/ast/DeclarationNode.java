package compilers.html_css.ast;

public class DeclarationNode extends CssNode {
    public String property;
    public ValueNode value;       // ممكن null
    public boolean important;

    public DeclarationNode(int line, int col) { super(line, col); }

    @Override public void accept(CssVisitor visitor) { visitor.visit(this); }
}











