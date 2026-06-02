package compilers.html_css.ast;

public class AttributeNode extends HtmlNode {
    private final String name;
    private final AttributeValueNode value; // ممكن يكون null إذا ما في قيمة

    public AttributeNode(String name, AttributeValueNode value, int line, int column) {
        super("Attribute", line, column);
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public AttributeValueNode getValue() {
        return value;
    }

    @Override
    public void accept(HtmlVisitor visitor) {
        visitor.visit(this);
    }
}










