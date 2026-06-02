package compilers.html_css.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HtmlDocumentNode extends HtmlNode {
    private final List<HtmlNode> children = new ArrayList<>();

    public HtmlDocumentNode(int line, int column) {
        super("HtmlDocument", line, column);
    }

    public void addChild(HtmlNode node) {
        if (node != null) children.add(node);
    }

    public List<HtmlNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    @Override
    public void accept(HtmlVisitor visitor) {
        visitor.visit(this);
    }
}










