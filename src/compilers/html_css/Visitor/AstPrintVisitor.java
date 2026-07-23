package compilers.html_css.Visitor;
import compilers.html_css.ast.StyleNode;

import compilers.html_css.ast.*;

import java.io.PrintStream;
import java.util.Objects;

public class AstPrintVisitor implements HtmlVisitor {

    private final PrintStream out;
    private int indent = 0;

    public AstPrintVisitor() {
        this(System.out);
    }

    public AstPrintVisitor(PrintStream out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    private void ind() {
        out.print("  ".repeat(Math.max(0, indent)));
    }

    private void printNode(HtmlNode n, String extra) {
        ind();
        out.println(n.getNodeName() + " (line=" + n.getLine() + ", col=" + n.getColumn() + ") " + extra);
    }

    @Override
    public void visit(HtmlDocumentNode node) {
        printNode(node, "");
        indent++;
        for (HtmlNode ch : node.getChildren()) ch.accept(this);
        indent--;
    }

    @Override
    public void visit(ElementNode node) {
        printNode(node, "tag=" + node.getTagName());
        indent++;

        if (!node.getAttributes().isEmpty()) {
            ind();
            out.println("Attributes:");
            indent++;
            for (AttributeNode a : node.getAttributes()) a.accept(this);
            indent--;
        }

        for (HtmlNode ch : node.getChildren()) ch.accept(this);
        indent--;
    }

    @Override
    public void visit(AttributeNode node) {
        printNode(node, "name=" + node.getName());
        indent++;
        if (node.getValue() != null) node.getValue().accept(this);
        indent--;
    }

    @Override
    public void visit(TextNode node) {
        // Whitespace-only text nodes exist for faithful HTML generation
        // (SEA_WS is a real token now); hide them from the teaching AST view.
        if (node.getText().trim().isEmpty()) {
            return;
        }
        printNode(node, "text=\"" + node.getText().replace("\n", "\\n") + "\"");
    }

    @Override
    public void visit(JinjaExpressionNode node) {
        printNode(node, "{{ " + node.getExpression() + " }}");
    }

    @Override
    public void visit(JinjaStatementNode node) {
        printNode(node, "{% " + node.getStatement() + " %}");
    }

    @Override
    public void visit(LiteralAttributeValueNode node) {
        printNode(node, "value=\"" + node.getValue() + "\"");
    }

    @Override
    public void visit(JinjaAttributeValueNode node) {
        printNode(node, "expr=\"" + node.getExpression() + "\"");
    }

    @Override
    public void visit(StyleNode node) {
        printNode(node, "Style");
        indent++;

        ind();
        out.println("css=\"" + shortCss(node.getRawCss()) + "\"");

        if (node.getCssAst() != null) {
            ind();
            out.println("CSS AST:");
            indent++;

            CssPrintVisitor cssPrinter = new CssPrintVisitor(out);
            node.getCssAst().accept(cssPrinter);

            indent--;
        } else {
            ind();
            out.println("CSS AST: <null>");
        }

        indent--;
    }

    private String shortCss(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replace("\r", "").replace("\n", "\\n");
        return (s.length() > 120) ? s.substring(0, 120) + "..." : s;
    }
}









