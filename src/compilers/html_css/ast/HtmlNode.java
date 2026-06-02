package compilers.html_css.ast;

public abstract class HtmlNode {
    private final String nodeName;
    private final int line;
    private final int column;

    protected HtmlNode(String nodeName, int line, int column) {
        this.nodeName = nodeName;
        this.line = line;
        this.column = column;
    }


    public String getNodeName() {
        return nodeName;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public abstract void accept(HtmlVisitor visitor);
}










