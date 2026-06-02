package compilers.html_css.ast;

public class JinjaStatementNode extends JinjaNode {
    private final String statement; // محتوى داخل {% ... %}

    public JinjaStatementNode(String statement, int line, int column) {
        super("JinjaStatement", line, column);
        this.statement = statement;
    }

    public String getStatement() {
        return statement;
    }

    @Override
    public void accept(HtmlVisitor visitor) {
        visitor.visit(this);
    }
}










