package compilers.html_css.ast;

public class TextNode extends HtmlNode {
    private final String text;

    public TextNode(String text, int line, int column) {
        super("Text", line, column);
        this.text = text;
    }

    public String getText() {
        return text;
    }

    @Override
    public void accept(HtmlVisitor visitor) {
        visitor.visit(this);
    }
}










