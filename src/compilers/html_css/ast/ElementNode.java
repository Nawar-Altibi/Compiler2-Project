package compilers.html_css.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ElementNode extends HtmlNode {
    private final String tagName;
    private final List<AttributeNode> attributes = new ArrayList<>();
    private final List<HtmlNode> children = new ArrayList<>();

    public ElementNode(String tagName, int line, int column) {
        super("Element", line, column);
        this.tagName = tagName;
    }

    public String getTagName() {
        return tagName;
    }

    public void addAttribute(AttributeNode attribute) {
        if (attribute != null) attributes.add(attribute);
    }

    public void addChild(HtmlNode child) {
        if (child != null) children.add(child);
    }

    public List<AttributeNode> getAttributes() {
        return Collections.unmodifiableList(attributes);
    }

    public List<HtmlNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    @Override
    public void accept(HtmlVisitor visitor) {
        visitor.visit(this);
    }
}










