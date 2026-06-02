package compilers.html_css.ast;

public abstract class CssNode {
    public final int line;
    public final int col;

    protected CssNode(int line, int col) {
        this.line = line;
        this.col = col;
    }

    public abstract void accept(CssVisitor visitor);
}










