package compilers.html_css.Visitor;

import compilers.html_css.ast.*;

public class CssPrintVisitor implements CssVisitor {

    private int indent = 0;

    private void ind() {
        for (int i = 0; i < indent; i++) System.out.print("  ");
    }

    private void printNode(CssNode n, String label) {
        ind();
        System.out.println(label + " (line=" + n.line + ", col=" + n.col + ")");
    }

    @Override
    public void visit(StylesheetNode n) {
        printNode(n, "Stylesheet");
        indent++;
        for (CssNode st : n.statements) {
            if (st != null) st.accept(this);
        }
        indent--;
    }

    @Override
    public void visit(RulesetNode n) {
        printNode(n, "Ruleset");
        indent++;

        if (n.selectors != null) n.selectors.accept(this);
        if (n.block != null) n.block.accept(this);

        indent--;
    }

    @Override
    public void visit(SelectorGroupNode n) {
        printNode(n, "SelectorGroup");
        indent++;

        for (SelectorNode s : n.selectors) {
            if (s != null) s.accept(this);
        }

        indent--;
    }

    @Override
    public void visit(SelectorNode n) {
        StringBuilder sb = new StringBuilder();

        for (Object p : n.parts) {
            if (p == null) continue;

            if (p instanceof Combinator comb) {
                // Combinator printing
                if (comb == Combinator.DESCENDANT) {
                    sb.append(" ");
                } else if (comb == Combinator.CHILD) {
                    sb.append(" > ");
                }
            } else if (p instanceof SimpleSelectorSequenceNode seq) {
                sb.append(buildSeqText(seq));
            } else if (p instanceof String) {
                String comb = ((String) p).trim();
                if (!comb.isEmpty()) sb.append(" ").append(comb).append(" ");
            } else if (p instanceof CssNode) {
                sb.append(((CssNode) p).getClass().getSimpleName());
            } else {
                sb.append(p.toString());
            }
        }

        printNode(n, "Selector \"" + sb.toString().trim() + "\"");

        indent++;
        for (Object p : n.parts) {
            if (p instanceof SimpleSelectorSequenceNode) {
                ((SimpleSelectorSequenceNode) p).accept(this);
            } else if (p instanceof Combinator) {
                ind();
                System.out.println("Combinator: " + p);
            } else if (p instanceof String) {
                ind();
                System.out.println("Combinator: " + p);
            }
        }
        indent--;
    }


    @Override
    public void visit(SimpleSelectorSequenceNode n) {
        printNode(n, "SimpleSelectorSequence " + buildSeqText(n));
    }

    private String buildSeqText(SimpleSelectorSequenceNode n) {
        if (n == null || n.items == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String it : n.items) {
            if (it == null) continue;
            sb.append(it);
        }
        return sb.toString();
    }

    @Override
    public void visit(DeclarationBlockNode n) {
        printNode(n, "DeclarationBlock");
        indent++;

        for (DeclarationNode d : n.declarations) {
            if (d != null) d.accept(this);
        }

        indent--;
    }

    @Override
    public void visit(DeclarationNode n) {
        String prop = n.property == null ? "<null>" : n.property;
        String imp = n.important ? " !important" : "";
        printNode(n, "Declaration " + prop + imp);

        indent++;
        if (n.value != null) n.value.accept(this);
        indent--;
    }

    @Override
    public void visit(ValueNode n) {
        printNode(n, "Value");
        indent++;

        for (int i = 0; i < n.groups.size(); i++) {
            ExprNode e = n.groups.get(i);
            if (e == null) continue;
            ind();
            System.out.println("Group[" + i + "]:");
            indent++;
            e.accept(this);
            indent--;
        }

        indent--;
    }

    @Override
    public void visit(ExprNode n) {
        printNode(n, "Expr");
        indent++;

        ind();
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < n.terms.size(); i++) {
            TermNode term = n.terms.get(i);
            if (term != null) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(term.text);
            }
            if (i < n.operators.size()) sb.append(" ").append(n.operators.get(i)).append(" ");
        }

        System.out.println("Text: " + sb);

        for (TermNode t : n.terms) {
            if (t != null) t.accept(this);
        }

        indent--;
    }


    @Override
    public void visit(TermNode n) {
        String kind = n.kind == null ? "UNKNOWN" : n.kind;
        String extra = "";

        if ("DIMENSION".equals(kind) && n.unit != null) {
            extra = " unit=" + n.unit;
        } else if ("FUNC".equals(kind)) {
            extra = " func=" + n.text;
        }

        printNode(n, "Term " + kind + extra + " text=\"" + safe(n.text) + "\"");

        if ("FUNC".equals(kind) && n.funcArgs != null) {
            indent++;
            ind();
            System.out.println("Args:");
            indent++;
            n.funcArgs.accept(this);
            indent -= 2;
        }
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.replace("\n", "\\n").replace("\r", "\\r");
    }



}










