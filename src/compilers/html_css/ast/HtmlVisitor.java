package compilers.html_css.ast;

public interface HtmlVisitor {
    void visit(HtmlDocumentNode node);
    void visit(ElementNode node);
    void visit(AttributeNode node);

    void visit(TextNode node);
    void visit(JinjaExpressionNode node);
    void visit(JinjaStatementNode node);

    void visit(LiteralAttributeValueNode node);
    void visit(JinjaAttributeValueNode node);

    void visit(StyleNode node); 
}










