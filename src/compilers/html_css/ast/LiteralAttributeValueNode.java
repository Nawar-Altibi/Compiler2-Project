package compilers.html_css.ast;

public class LiteralAttributeValueNode extends AttributeValueNode {
    private final String value;

    public LiteralAttributeValueNode(String value, int line, int column) {
        super("LiteralAttributeValue", line, column);
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public void accept(HtmlVisitor visitor) {
        visitor.visit(this);
    }
}










