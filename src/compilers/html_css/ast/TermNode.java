package compilers.html_css.ast;

public class TermNode extends CssNode {
    public String kind;
    public String text;
    public String unit;
    public ValueNode funcArgs;

    public TermNode(int line, int col) { super(line, col); }
    @Override public void accept(CssVisitor visitor) { visitor.visit(this); }
}









