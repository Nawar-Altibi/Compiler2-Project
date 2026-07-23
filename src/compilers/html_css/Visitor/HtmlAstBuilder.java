package compilers.html_css.Visitor;

import compilers.html_css.antlr.HtmlCssParser;
import compilers.html_css.antlr.HtmlCssParserBaseVisitor;
import compilers.html_css.ast.*;


import compilers.html_css.antlr.CssLexer;
import compilers.html_css.antlr.CssParser;
import compilers.html_css.Visitor.CssAstBuilder;
import compilers.html_css.ast.StylesheetNode;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;

import org.antlr.v4.runtime.Token;


import java.io.PrintStream;
import java.util.List;
import java.util.Objects;

/**
 * Builds HTML AST from the ANTLR parse tree produced by HtmlCssParser.
 *
 * Notes:
 * - Uses ctx.start for (line, column) on every node.
 * - Keeps Jinja as opaque nodes (does not parse inside).
 * - For <style> ... </style> stores raw CSS in StyleNode (later you can parse CSS AST separately).
 */
public class HtmlAstBuilder extends HtmlCssParserBaseVisitor<HtmlNode> {

    private final PrintStream errorStream;
    private final boolean debug;

    public HtmlAstBuilder() {
        this(System.err, false);
    }

    public HtmlAstBuilder(PrintStream errorStream) {
        this(errorStream, false);
    }

    /**
     * Creates a stream-injected builder. Java implementation traces are an
     * explicit presentation choice and remain disabled for normal compiler
     * diagnostics.
     */
    public HtmlAstBuilder(PrintStream errorStream, boolean debug) {
        this.errorStream = Objects.requireNonNull(errorStream, "errorStream");
        this.debug = debug;
    }

    private static boolean isVoidElement(String tag) {
        if (tag == null) return false;
        return switch (tag.toLowerCase()) {
            case "area", "base", "br", "col", "embed", "hr", "img", "input",
                 "link", "meta", "param", "source", "track", "wbr" -> true;
            default -> false;
        };
    }


    private static int line(Token t) {
        return t != null ? t.getLine() : -1;
    }

    private static int col(Token t) {
        return t != null ? t.getCharPositionInLine() : -1;
    }

    private static String stripQuotesIfAny(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static String normalizeText(String s) {
        return s == null ? "" : s;
    }

    // Try to extract inner CSS text from STYLE_BODY token:
    // STYLE_BODY looks like: "<css...> </style>" (because lexer grabs until closing tag)
    private static String extractCssFromStyleBodyToken(String tokenText) {
        if (tokenText == null) return "";
        // tokenText likely contains EVERYTHING between <style...> and </style> inclusive of </style>
        // We'll remove the trailing "</style>" if present.
        String lower = tokenText.toLowerCase();
        int closeIdx = lower.lastIndexOf("</style>");
        if (closeIdx >= 0) {
            return tokenText.substring(0, closeIdx);
        }
        // STYLE_SHORT_BODY ends with "</>" in your lexer; remove that too:
        int shortIdx = tokenText.lastIndexOf("</>");
        if (shortIdx >= 0) {
            return tokenText.substring(0, shortIdx);
        }
        return tokenText;
    }

    // -------------------------
    // Top-level
    // -------------------------

    @Override
    public HtmlNode visitHtmlDocument(HtmlCssParser.HtmlDocumentContext ctx) {
        HtmlDocumentNode doc = new HtmlDocumentNode(line(ctx.start), col(ctx.start));

        // htmlDocument : ( htmlElement | htmlChardata | htmlComment | jinjaExpression | jinjaStatement
        //               | JINJA_COMMENT | DTD | XML )* ;

        for (ParseTree child : ctx.children) {
            HtmlNode node = child.accept(this);
            // We ignore nodes we don't represent (DTD/XML/JINJA_COMMENT...) by returning null
            if (node != null) {
                // Optional: ignore pure whitespace text nodes if you want a "more abstract" AST:
                // if (node instanceof TextNode && ((TextNode) node).getText().trim().isEmpty()) continue;
                doc.addChild(node);
            }
        }
        return doc;
    }

    // -------------------------
    // Chardata / Text
    // -------------------------

    @Override
    public HtmlNode visitHtmlChardata(HtmlCssParser.HtmlChardataContext ctx) {
        // htmlChardata : HTML_TEXT | SEA_WS ;
        String text = ctx.getText();
        // You can choose to drop whitespace-only nodes for more abstraction, but I'll keep them for now.
        return new TextNode(normalizeText(text), line(ctx.start), col(ctx.start));
    }

    // -------------------------
    // Comments
    // -------------------------

    @Override
    public HtmlNode visitHtmlComment(HtmlCssParser.HtmlCommentContext ctx) {
        // htmlComment : HTML_COMMENT | HTML_CONDITIONAL_COMMENT ;
//        return new CommentNode(ctx.getText(), line(ctx.start), col(ctx.start));
        return null;
    }


    @Override
    public HtmlNode visitSelfClosingElement(HtmlCssParser.SelfClosingElementContext ctx) {

        String tagName = ctx.TAG_NAME().getText();
        ElementNode element = new ElementNode(tagName, line(ctx.start), col(ctx.start));

        // attributes
        for (HtmlCssParser.HtmlAttributeContext a : ctx.htmlAttribute()) {
            HtmlNode built = visitHtmlAttribute(a);
            if (built instanceof AttributeNode) element.addAttribute((AttributeNode) built);
        }

        // void elements (optional, but fine)
        if (isVoidElement(tagName)) return element;

        return element;
    }

    @Override
    public HtmlNode visitNormalElement(HtmlCssParser.NormalElementContext ctx) {

        String openName = ctx.TAG_NAME(0).getText();
        ElementNode element = new ElementNode(openName, line(ctx.start), col(ctx.start));

        // attributes
        for (HtmlCssParser.HtmlAttributeContext a : ctx.htmlAttribute()) {
            HtmlNode built = visitHtmlAttribute(a);
            if (built instanceof AttributeNode) element.addAttribute((AttributeNode) built);
        }

        // void elements: لا محتوى حتى لو grammar سمحت
        if (isVoidElement(openName)) {
            return element;
        }

        // content
        HtmlCssParser.HtmlContentContext contentCtx = ctx.htmlContent();
        if (contentCtx != null) {
            HtmlNode contentNode = visitHtmlContent(contentCtx);
            if (contentNode instanceof HtmlDocumentNode) {
                for (HtmlNode ch : ((HtmlDocumentNode) contentNode).getChildren()) {
                    element.addChild(ch);
                }
            } else if (contentNode != null) {
                element.addChild(contentNode);
            }
        }

        // closing tag check (semantic validation)
        if (ctx.TAG_NAME().size() > 1) {
            String closeName = ctx.TAG_NAME(1).getText();
            if (!openName.equalsIgnoreCase(closeName)) {
                errorStream.println("Mismatched closing tag: <" + openName + "> closed by </" + closeName + ">"
                        + " at line " + line(ctx.start) + ", col " + col(ctx.start));
            }
        }

        return element;
    }


    @Override
    public HtmlNode visitStyleElement(HtmlCssParser.StyleElementContext ctx) {

        return visitStyle(ctx.style());
    }


    @Override
    public HtmlNode visitHtmlContent(HtmlCssParser.HtmlContentContext ctx) {

        HtmlDocumentNode container = new HtmlDocumentNode(line(ctx.start), col(ctx.start));

        if (ctx.children == null) {
            return container;
        }

        for (ParseTree child : ctx.children) {
            // Skip CDATA and JINJA_COMMENT if you don't want them in AST.
            String text = child.getText();
            if (text == null) continue;

            // If you want to keep CDATA, you could map it to TextNode.
            if (text.startsWith("<![CDATA[")) {
                container.addChild(new TextNode(text, line(ctx.start), col(ctx.start)));
                continue;
            }
            if (text.startsWith("{#")) {
                // JINJA_COMMENT: ignore (or store as CommentNode if you want)
                continue;
            }

            HtmlNode node = child.accept(this);
            if (node != null) {
                container.addChild(node);
            }
        }

        return container;
    }

    // -------------------------
    // Attributes
    // -------------------------

    @Override
    public HtmlNode visitHtmlAttribute(HtmlCssParser.HtmlAttributeContext ctx) {
        // htmlAttribute : TAG_NAME (TAG_EQUALS ATTVALUE_VALUE)? ;

        String name = ctx.TAG_NAME().getText();
        int l = line(ctx.start);
        int c = col(ctx.start);

        AttributeValueNode valueNode = null;

        if (ctx.ATTVALUE_VALUE() != null) {
            String raw = ctx.ATTVALUE_VALUE().getText();
            raw = stripQuotesIfAny(raw);

            // If attribute value contains a full Jinja expression like {{ ... }},
            // represent it as JinjaAttributeValueNode, otherwise literal.
            String trimmed = raw.trim();
            if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
                // remove {{ and }}
                String inner = trimmed.substring(2, trimmed.length() - 2).trim();
                valueNode = new JinjaAttributeValueNode(inner, l, c);
            } else {
                valueNode = new LiteralAttributeValueNode(raw, l, c);
            }
        }

        return new AttributeNode(name, valueNode, l, c);
    }

    // -------------------------
    // Style
    // -------------------------

    @Override
    public HtmlNode visitStyle(HtmlCssParser.StyleContext ctx) {
        String raw = "";

        if (ctx.STYLE_BODY() != null) {
            raw = extractCssFromStyleBodyToken(ctx.STYLE_BODY().getText());
        } else if (ctx.STYLE_SHORT_BODY() != null) {
            raw = ctx.STYLE_SHORT_BODY().getText();
        }

        StyleNode styleNode = new StyleNode(raw.trim(), line(ctx.start), col(ctx.start));

        try {
            CharStream cs = CharStreams.fromString(styleNode.getRawCss());
            CssLexer cssLexer = new CssLexer(cs);
            cssLexer.removeErrorListeners();
            cssLexer.addErrorListener(new CssErrorListener(
                    errorStream, line(ctx.start)));
            CommonTokenStream cssTokens = new CommonTokenStream(cssLexer);
            CssParser cssParser = new CssParser(cssTokens);
            cssParser.removeErrorListeners();
            cssParser.addErrorListener(new CssErrorListener(
                    errorStream, line(ctx.start)));

            ParseTree cssTree = cssParser.stylesheet();

            CssAstBuilder cssBuilder = new CssAstBuilder(cssTokens);
            StylesheetNode cssAst = (StylesheetNode) cssBuilder.visit(cssTree);

            styleNode.setCssAst(cssAst);
        } catch (Exception ex) {
            errorStream.println("[CSS Error] <style>:" + line(ctx.start));
            errorStream.println("  CSS parsing failed: " + safeMessage(ex));
            if (debug) {
                ex.printStackTrace(errorStream);
            }

        }

        return styleNode;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private static final class CssErrorListener extends BaseErrorListener {
        private final PrintStream errorStream;
        private final int styleLine;

        private CssErrorListener(PrintStream errorStream, int styleLine) {
            this.errorStream = errorStream;
            this.styleLine = styleLine;
        }

        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String message,
                RecognitionException failure) {
            int documentLine = styleLine > 0 && line > 0
                    ? styleLine + line - 1
                    : Math.max(line, 0);
            errorStream.println("[CSS Error] <style>:" + documentLine + ":"
                    + Math.max(charPositionInLine, 0));
            errorStream.println("  Syntax error: " + message);
        }
    }


    // -------------------------
    // Jinja
    // -------------------------

    @Override
    public HtmlNode visitJinjaExpression(HtmlCssParser.JinjaExpressionContext ctx) {
        // jinjaExpression : JINJA_EXPRESSION_START JINJA_EXPRESSION_CONTENT JINJA_EXPRESSION_END ;
        String expr = ctx.JINJA_EXPRESSION_CONTENT() != null ? ctx.JINJA_EXPRESSION_CONTENT().getText() : "";
        return new JinjaExpressionNode(expr.trim(), line(ctx.start), col(ctx.start));
    }

    @Override
    public HtmlNode visitJinjaStatement(HtmlCssParser.JinjaStatementContext ctx) {
        // jinjaStatement : JINJA_STATEMENT_START JINJA_STATEMENT_CONTENT JINJA_STATEMENT_END ;
        String st = ctx.JINJA_STATEMENT_CONTENT() != null ? ctx.JINJA_STATEMENT_CONTENT().getText() : "";
        return new JinjaStatementNode(st.trim(), line(ctx.start), col(ctx.start));
    }
}








