package compilers.html_css.ast;

public class JinjaExpressionNode extends JinjaNode {
    private final String expression; // محتوى داخل {{ ... }}

    public JinjaExpressionNode(String expression, int line, int column) {
        super("JinjaExpression", line, column);
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










