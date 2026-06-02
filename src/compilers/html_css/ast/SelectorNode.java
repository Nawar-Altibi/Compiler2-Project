package compilers.html_css.ast;

import java.util.ArrayList;
import java.util.List;

public class SelectorNode extends CssNode {

    public final List<Object> parts = new ArrayList<>();

    public SelectorNode(int line, int col) {
        super(line, col);
    }

    public void addFirst(SimpleSelectorSequenceNode first) {
        if (first != null) parts.add(first);
    }

    public void addStep(Combinator combinator, SimpleSelectorSequenceNode next) {
        if (combinator != null) parts.add(combinator);
        if (next != null) parts.add(next);
    }

    @Override
    public void accept(CssVisitor visitor) {
        visitor.visit(this);
    }
}










