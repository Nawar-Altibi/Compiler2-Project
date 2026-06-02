package compilers.html_css.Visitor;

import compilers.html_css.ast.*;
import compilers.html_css.antlr.CssLexer;
import compilers.html_css.antlr.CssParser;
import compilers.html_css.antlr.CssParserBaseVisitor;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;

public class CssAstBuilder extends CssParserBaseVisitor<CssNode> {

    private final TokenStream tokens; // مهم لتحديد هل combinator كان WS أم GT

    public CssAstBuilder(TokenStream tokens) {
        this.tokens = tokens;
    }

    private static int line(Token t) { return t != null ? t.getLine() : -1; }
    private static int col(Token t)  { return t != null ? t.getCharPositionInLine() : -1; }

    // -----------------------------------------
    // Entry
    // stylesheet : (ruleset WS*)* EOF
    // -----------------------------------------
    @Override
    public CssNode visitStylesheet(CssParser.StylesheetContext ctx) {
        StylesheetNode sheet = new StylesheetNode(line(ctx.start), col(ctx.start));
        for (CssParser.RulesetContext rs : ctx.ruleset()) {
            sheet.add(visit(rs));
        }
        return sheet;
    }

    // -----------------------------------------
    // ruleset : selectorGroup WS* LBRACE WS* declaration* WS* RBRACE WS*
    // -----------------------------------------
    @Override
    public CssNode visitRuleset(CssParser.RulesetContext ctx) {
        RulesetNode rs = new RulesetNode(line(ctx.start), col(ctx.start));
        rs.selectors = (SelectorGroupNode) visit(ctx.selectorGroup());

        DeclarationBlockNode block = new DeclarationBlockNode(line(ctx.start), col(ctx.start));
        for (CssParser.DeclarationContext dctx : ctx.declaration()) {
            block.add((DeclarationNode) visit(dctx));
        }
        rs.block = block;

        return rs;
    }

    // -----------------------------------------
    // selectorGroup : selector (WS* COMMA WS* selector)*
    // -----------------------------------------
    @Override
    public CssNode visitSelectorGroup(CssParser.SelectorGroupContext ctx) {
        SelectorGroupNode g = new SelectorGroupNode(line(ctx.start), col(ctx.start));
        for (CssParser.SelectorContext s : ctx.selector()) {
            g.add((SelectorNode) visit(s));
        }
        return g;
    }

    // -----------------------------------------
    // selector : simpleSelector ( (GT WS* | WS+) simpleSelector )*
    //
    // نحدد combinator بين كل selectorين باستخدام token stream:
    // إذا وُجد GT بينهما -> CHILD
    // وإلا -> DESCENDANT
    // -----------------------------------------
    @Override
    public CssNode visitSelector(CssParser.SelectorContext ctx) {
        SelectorNode n = new SelectorNode(line(ctx.start), col(ctx.start));

        // أول simpleSelector
        SimpleSelectorSequenceNode first = (SimpleSelectorSequenceNode) visit(ctx.simpleSelector(0));
        n.addFirst(first);

        for (int i = 1; i < ctx.simpleSelector().size(); i++) {
            CssParser.SimpleSelectorContext prev = ctx.simpleSelector(i - 1);
            CssParser.SimpleSelectorContext next = ctx.simpleSelector(i);

            Combinator comb = detectCombinatorBetween(prev, next);
            SimpleSelectorSequenceNode nextNode = (SimpleSelectorSequenceNode) visit(next);

            n.addStep(comb, nextNode);
        }

        return n;
    }

    private Combinator detectCombinatorBetween(CssParser.SimpleSelectorContext left, CssParser.SimpleSelectorContext right) {
        // افتراضيًا descendant
        Combinator comb = Combinator.DESCENDANT;

        if (tokens == null || left.stop == null || right.start == null) {
            return comb;
        }

        int leftStop = left.stop.getTokenIndex();
        int rightStart = right.start.getTokenIndex();

        for (int k = leftStop + 1; k < rightStart; k++) {
            Token tk = tokens.get(k);
            if (tk.getType() == CssLexer.GT) {
                return Combinator.CHILD;
            }
        }
        return comb;
    }

    // -----------------------------------------
    // simpleSelector : typeSelector | classSelector | idSelector
    // نحن نرجعه كـ SimpleSelectorSequenceNode (للتوافق مع AST الحالي)
    // -----------------------------------------
    @Override
    public CssNode visitSimpleSelector(CssParser.SimpleSelectorContext ctx) {
        SimpleSelectorSequenceNode n = new SimpleSelectorSequenceNode(line(ctx.start), col(ctx.start));
        // نخزن النص النهائي مباشرة (مثلاً: "body" أو ".card" أو "#main")
        n.items.add(ctx.getText());
        return n;
    }

    // -----------------------------------------
    // declaration : IDENT WS* COLON WS* value (WS* COMMA WS* value)* WS* SEMI WS*
    // ملاحظة: grammar المبسط لا يحتوي !important عادة.
    // سنجعل important=false (وإذا أضفت IMPORTANT لاحقًا نعدّل بسهولة)
    // -----------------------------------------
    @Override
    public CssNode visitDeclaration(CssParser.DeclarationContext ctx) {
        DeclarationNode d = new DeclarationNode(line(ctx.start), col(ctx.start));
        d.property = ctx.IDENT().getText();
        d.important = false;

        ValueNode v = new ValueNode(line(ctx.start), col(ctx.start));
        // كل ValueContext = مجموعة (ExprNode) واحدة
        for (CssParser.ValueContext vc : ctx.value()) {
            v.groups.add(buildExprFromValue(vc));
        }
        d.value = v;

        return d;
    }

    // -----------------------------------------
    // value : valuePart (WS+ valuePart)*
    // سنحوّلها إلى ExprNode حيث terms = valuePart كـ TermNode
    // operators لا نستخدمها الآن (فارغة) لأن grammar المبسط ما فيه + - * /
    // -----------------------------------------
    private ExprNode buildExprFromValue(CssParser.ValueContext ctx) {
        ExprNode e = new ExprNode(line(ctx.start), col(ctx.start));
        for (CssParser.ValuePartContext vp : ctx.valuePart()) {
            e.terms.add((TermNode) visit(vp));
        }
        return e;
    }

    // -----------------------------------------
    // valuePart :
    //    NUMBER UNIT?
    //  | IDENT
    //  | STRING
    //  | HexColor
    //  | functionCall
    // -----------------------------------------
    @Override
    public CssNode visitValuePart(CssParser.ValuePartContext ctx) {
        TermNode t = new TermNode(line(ctx.start), col(ctx.start));
        t.text = ctx.getText();

        if (ctx.NUMBER() != null) {
            if (ctx.UNIT() != null) {
                t.kind = "DIMENSION";
                t.unit = ctx.UNIT().getText();
            } else {
                t.kind = "NUMBER";
            }
            return t;
        }

        if (ctx.HexColor() != null) {
            t.kind = "HEX";
            return t;
        }

        if (ctx.STRING() != null) {
            t.kind = "STRING";
            return t;
        }

        if (ctx.IDENT() != null) {
            t.kind = "IDENT";
            return t;
        }

        if (ctx.functionCall() != null) {
            return visit(ctx.functionCall());
        }

        t.kind = "UNKNOWN";
        return t;
    }

    // -----------------------------------------
    // functionCall : FUNCTION WS* value (WS* COMMA WS* value)* WS* RPAREN
    //
    // FUNCTION token نصه عادة مثل "rgba(" أو "url("
    // سنخزن اسم الدالة بدون '(' في TermNode.textName (داخل text) أو نستعمل text الحالي.
    // وسنخزن args داخل t.funcArgs كـ ValueNode.groups
    // -----------------------------------------
    @Override
    public CssNode visitFunctionCall(CssParser.FunctionCallContext ctx) {
        TermNode t = new TermNode(line(ctx.start), col(ctx.start));
        t.kind = "FUNC";

        String fn = ctx.FUNCTION().getText(); // مثال: "rgba("
        if (fn.endsWith("(")) fn = fn.substring(0, fn.length() - 1);

        // نخزن اسم الدالة في text (أو تقدر تضيف حقل جديد إذا تحب)
        t.text = fn;

        ValueNode args = new ValueNode(line(ctx.start), col(ctx.start));
        for (CssParser.ValueContext vc : ctx.value()) {
            args.groups.add(buildExprFromValue(vc));
        }
        t.funcArgs = args;

        return t;
    }
}










