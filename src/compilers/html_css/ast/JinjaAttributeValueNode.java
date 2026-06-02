package compilers.html_css.ast;

public class JinjaAttributeValueNode extends AttributeValueNode {
    private final String expression; // مثال: product.image

    public JinjaAttributeValueNode(String expression, int line, int column) {
        super("JinjaAttributeValue", line, column);
        this.expression = expression;
    }

    public String getExpression() {
        return expression;
    }

    @Override
    public void accept(HtmlVisitor visitor) {
        visitor.visit(this);
    }
}










