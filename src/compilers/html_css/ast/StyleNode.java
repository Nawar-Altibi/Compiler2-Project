package compilers.html_css.ast;

import compilers.html_css.ast.StylesheetNode;

public class StyleNode extends HtmlNode {
    private final String rawCss;
    private StylesheetNode cssAst;

    public StyleNode(String rawCss, int line, int col) {
        super("Style", line, col);
        this.rawCss = rawCss;
    }

    public String getRawCss() { return rawCss; }
    public StylesheetNode getCssAst() { return cssAst; }
    public void setCssAst(StylesheetNode cssAst) { this.cssAst = cssAst; }

    @Override
    public void accept(HtmlVisitor visitor) { visitor.visit(this); }
}










