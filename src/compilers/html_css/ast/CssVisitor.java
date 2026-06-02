package compilers.html_css.ast;

public interface CssVisitor {
    void visit(StylesheetNode n);
    void visit(RulesetNode n);
    void visit(SelectorGroupNode n);
    void visit(SelectorNode n);
    void visit(SimpleSelectorSequenceNode n);

    void visit(DeclarationBlockNode n);
    void visit(DeclarationNode n);

    void visit(ValueNode n);
    void visit(ExprNode n);
    void visit(TermNode n);

}










