package compilers.html_css.render;

import compilers.html_css.ast.AttributeNode;
import compilers.html_css.ast.ElementNode;
import compilers.html_css.ast.HtmlDocumentNode;
import compilers.html_css.ast.HtmlNode;
import compilers.html_css.ast.JinjaAttributeValueNode;
import compilers.html_css.ast.JinjaExpressionNode;
import compilers.html_css.ast.JinjaStatementNode;
import compilers.html_css.ast.LiteralAttributeValueNode;
import compilers.html_css.ast.StyleNode;
import compilers.html_css.ast.TextNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Structures one parsed template into a renderable tree (plan 6.3.1):
 *
 * <ol>
 *   <li>Flatten the HTML/Jinja AST into a linear <b>event stream</b>
 *       (text / expression / statement). HTML tags become text events, so
 *       {@code {% for %}} … {@code {% endfor %}} may legally cross element
 *       boundaries — exactly the rubric's example.</li>
 *   <li>Apply the frozen whitespace policy (6.3.6): a line holding only a
 *       {@code {% %}} statement contributes nothing.</li>
 *   <li>Pair openers/closers with a single pass + open-block stack into
 *       {@code Text | Output | For | If | Block} nodes; mismatches are
 *       structural errors.</li>
 * </ol>
 */
public final class JinjaTemplateModel {

    /** Structural template failure (unbalanced blocks, bad statements). */
    public static final class TemplateStructureException extends RuntimeException {
        public final int line;
        public final int column;

        public TemplateStructureException(String message, int line, int column) {
            super(message);
            this.line = line;
            this.column = column;
        }
    }

    // ==================== structured nodes ====================

    /** Base of the structured render tree. */
    public abstract static class Node {
        public final int line;
        public final int column;

        Node(int line, int column) {
            this.line = line;
            this.column = column;
        }
    }

    public static final class Text extends Node {
        public final String text;

        Text(String text, int line, int column) {
            super(line, column);
            this.text = text;
        }
    }

    /** {@code {{ expression }}} output (also used inside attribute values). */
    public static final class Output extends Node {
        public final String expression;

        Output(String expression, int line, int column) {
            super(line, column);
            this.expression = expression;
        }
    }

    public static final class ForBlock extends Node {
        public final List<String> targets;
        public final String iterableExpression;
        public final List<Node> body = new ArrayList<>();
        public final List<Node> elseBody = new ArrayList<>();

        ForBlock(List<String> targets, String iterableExpression, int line, int column) {
            super(line, column);
            this.targets = targets;
            this.iterableExpression = iterableExpression;
        }
    }

    public static final class IfBlock extends Node {
        /** condition == null means the trailing {% else %} branch. */
        public static final class Branch {
            public final String condition;
            public final List<Node> body = new ArrayList<>();

            Branch(String condition) {
                this.condition = condition;
            }
        }

        public final List<Branch> branches = new ArrayList<>();

        IfBlock(int line, int column) {
            super(line, column);
        }
    }

    public static final class BlockDef extends Node {
        public final String name;
        public final List<Node> body = new ArrayList<>();

        BlockDef(String name, int line, int column) {
            super(line, column);
            this.name = name;
        }
    }

    // ==================== model ====================

    private final String templateName;
    private final String doctypeLine; // recovered from raw source; AST drops DTD
    private final String extendsName; // null when the template stands alone
    private final List<Node> root;
    private final Map<String, BlockDef> blocks;

    private JinjaTemplateModel(
            String templateName,
            String doctypeLine,
            String extendsName,
            List<Node> root,
            Map<String, BlockDef> blocks) {
        this.templateName = templateName;
        this.doctypeLine = doctypeLine;
        this.extendsName = extendsName;
        this.root = root;
        this.blocks = blocks;
    }

    public String getTemplateName() {
        return templateName;
    }

    public String getDoctypeLine() {
        return doctypeLine;
    }

    public String getExtendsName() {
        return extendsName;
    }

    public List<Node> getRoot() {
        return root;
    }

    public Map<String, BlockDef> getBlocks() {
        return blocks;
    }

    // ==================== event stream ====================

    private abstract static class Event {
        final int line;
        final int column;

        Event(int line, int column) {
            this.line = line;
            this.column = column;
        }
    }

    private static final class TextEvent extends Event {
        String text; // mutable: the whitespace policy trims neighbors

        TextEvent(String text, int line, int column) {
            super(line, column);
            this.text = text;
        }
    }

    private static final class ExprEvent extends Event {
        final String expression;

        ExprEvent(String expression, int line, int column) {
            super(line, column);
            this.expression = expression;
        }
    }

    private static final class StmtEvent extends Event {
        final String statement;

        StmtEvent(String statement, int line, int column) {
            super(line, column);
            this.statement = statement;
        }
    }

    /** Parses a template AST (+ recovered doctype) into a structured model. */
    public static JinjaTemplateModel parse(
            String templateName, HtmlNode astRoot, String doctypeLine) {
        List<Event> events = new ArrayList<>();
        flatten(astRoot, events);
        applyWhitespacePolicy(events);
        return structure(templateName, doctypeLine, events);
    }

    // -------- step 1: flatten --------

    private static void flatten(HtmlNode node, List<Event> events) {
        if (node instanceof HtmlDocumentNode) {
            for (HtmlNode child : ((HtmlDocumentNode) node).getChildren()) {
                flatten(child, events);
            }
            return;
        }
        if (node instanceof TextNode) {
            events.add(new TextEvent(((TextNode) node).getText(),
                    node.getLine(), node.getColumn()));
            return;
        }
        if (node instanceof JinjaExpressionNode) {
            events.add(new ExprEvent(((JinjaExpressionNode) node).getExpression(),
                    node.getLine(), node.getColumn()));
            return;
        }
        if (node instanceof JinjaStatementNode) {
            events.add(new StmtEvent(((JinjaStatementNode) node).getStatement(),
                    node.getLine(), node.getColumn()));
            return;
        }
        if (node instanceof StyleNode) {
            events.add(new TextEvent("<style>\n" + ((StyleNode) node).getRawCss()
                    + "\n</style>", node.getLine(), node.getColumn()));
            return;
        }
        if (node instanceof ElementNode) {
            flattenElement((ElementNode) node, events);
            return;
        }
        // Attribute nodes are handled inside flattenElement; anything else
        // (dropped comments etc.) contributes nothing.
    }

    private static void flattenElement(ElementNode element, List<Event> events) {
        StringBuilder open = new StringBuilder("<").append(element.getTagName());
        List<Event> pendingAttributeEvents = new ArrayList<>();
        for (AttributeNode attribute : element.getAttributes()) {
            open.append(' ').append(attribute.getName());
            if (attribute.getValue() == null) {
                continue;
            }
            open.append("=\"");
            if (attribute.getValue() instanceof JinjaAttributeValueNode) {
                // Whole value is {{ ... }} — flush text, emit expression.
                pendingAttributeEvents.add(new TextEvent(open.toString(),
                        element.getLine(), element.getColumn()));
                open.setLength(0);
                JinjaAttributeValueNode value =
                        (JinjaAttributeValueNode) attribute.getValue();
                pendingAttributeEvents.add(new ExprEvent(value.getExpression(),
                        value.getLine(), value.getColumn()));
            } else if (attribute.getValue() instanceof LiteralAttributeValueNode) {
                LiteralAttributeValueNode value =
                        (LiteralAttributeValueNode) attribute.getValue();
                // A literal may still embed {{ ... }} pieces (mixed values).
                open.append('\0'); // placeholder marker; split below
                pendingAttributeEvents.add(new TextEvent(open.toString(),
                        element.getLine(), element.getColumn()));
                open.setLength(0);
                splitEmbedded(value.getValue(), value.getLine(), value.getColumn(),
                        pendingAttributeEvents);
            }
            open.append('"');
        }

        boolean isVoid = isVoidElement(element.getTagName());
        open.append(isVoid ? " />" : ">");
        pendingAttributeEvents.add(new TextEvent(open.toString(),
                element.getLine(), element.getColumn()));

        // Merge: replace the '\0' markers by joining consecutive text pieces.
        for (Event event : pendingAttributeEvents) {
            if (event instanceof TextEvent) {
                ((TextEvent) event).text = ((TextEvent) event).text.replace("\0", "");
            }
            events.add(event);
        }

        if (isVoid) {
            return;
        }
        for (HtmlNode child : element.getChildren()) {
            flatten(child, events);
        }
        events.add(new TextEvent("</" + element.getTagName() + ">",
                element.getLine(), element.getColumn()));
    }

    /** Splits a literal attribute value around embedded {@code {{ ... }}}. */
    private static void splitEmbedded(
            String value, int line, int column, List<Event> sink) {
        int cursor = 0;
        while (true) {
            int start = value.indexOf("{{", cursor);
            if (start < 0) {
                if (cursor < value.length()) {
                    sink.add(new TextEvent(value.substring(cursor), line, column));
                }
                return;
            }
            int end = value.indexOf("}}", start + 2);
            if (end < 0) {
                sink.add(new TextEvent(value.substring(cursor), line, column));
                return;
            }
            if (start > cursor) {
                sink.add(new TextEvent(value.substring(cursor, start), line, column));
            }
            sink.add(new ExprEvent(
                    value.substring(start + 2, end).trim(), line, column));
            cursor = end + 2;
        }
    }

    private static boolean isVoidElement(String tag) {
        switch (tag.toLowerCase(Locale.ROOT)) {
            case "area":
            case "base":
            case "br":
            case "col":
            case "embed":
            case "hr":
            case "img":
            case "input":
            case "link":
            case "meta":
            case "param":
            case "source":
            case "track":
            case "wbr":
                return true;
            default:
                return false;
        }
    }

    // -------- step 2: whitespace policy (frozen 6.3.6) --------

    private static void applyWhitespacePolicy(List<Event> events) {
        for (int index = 0; index < events.size(); index++) {
            if (!(events.get(index) instanceof StmtEvent)) {
                continue;
            }

            // ---- left side: is the statement at the start of its line? ----
            // Walk back over statements (they render nothing) and emptied
            // text pieces to the nearest visible text.
            boolean atLineStart = true;
            TextEvent indentOwner = null; // text whose trailing indent we trim
            for (int back = index - 1; back >= 0; back--) {
                Event prior = events.get(back);
                if (prior instanceof StmtEvent) {
                    continue;
                }
                if (prior instanceof ExprEvent) {
                    atLineStart = false;
                    break;
                }
                TextEvent text = (TextEvent) prior;
                if (text.text.isEmpty()) {
                    continue;
                }
                int newline = text.text.lastIndexOf('\n');
                String tail = text.text.substring(newline + 1);
                if (newline >= 0) {
                    atLineStart = tail.trim().isEmpty();
                    indentOwner = atLineStart && !tail.isEmpty() ? text : null;
                } else {
                    atLineStart = back == 0 && tail.trim().isEmpty();
                    indentOwner = atLineStart && !tail.isEmpty() ? text : null;
                }
                break;
            }
            if (!atLineStart) {
                continue;
            }

            // ---- right side: does the statement own the rest of its line? ----
            Event following = index + 1 < events.size() ? events.get(index + 1) : null;
            if (following instanceof ExprEvent) {
                continue;
            }
            TextEvent next = following instanceof TextEvent
                    ? (TextEvent) following
                    : null;
            if (next != null) {
                String after = next.text;
                int firstBreak = after.indexOf('\n');
                String head = firstBreak < 0 ? after : after.substring(0, firstBreak);
                if (!head.trim().isEmpty()) {
                    continue;
                }
                if (firstBreak >= 0) {
                    next.text = after.substring(firstBreak + 1);
                } else if (after.trim().isEmpty()) {
                    next.text = "";
                } else {
                    continue;
                }
            }
            // following == null or another statement: the line renders nothing.

            if (indentOwner != null) {
                int newline = indentOwner.text.lastIndexOf('\n');
                indentOwner.text = newline >= 0
                        ? indentOwner.text.substring(0, newline + 1)
                        : "";
            }
        }
    }

    // -------- step 3: structure (stack pairing) --------

    private static JinjaTemplateModel structure(
            String templateName, String doctypeLine, List<Event> events) {
        List<Node> rootNodes = new ArrayList<>();
        Map<String, BlockDef> blocks = new LinkedHashMap<>();
        Deque<Object> stack = new ArrayDeque<>(); // ForBlock | IfBlock | BlockDef
        Deque<List<Node>> sinks = new ArrayDeque<>();
        sinks.push(rootNodes);
        String extendsName = null;

        for (Event event : events) {
            if (event instanceof TextEvent) {
                String text = ((TextEvent) event).text;
                if (!text.isEmpty()) {
                    sinks.peek().add(new Text(text, event.line, event.column));
                }
                continue;
            }
            if (event instanceof ExprEvent) {
                sinks.peek().add(new Output(((ExprEvent) event).expression,
                        event.line, event.column));
                continue;
            }

            String raw = ((StmtEvent) event).statement.trim();
            String keyword = firstWord(raw);
            String rest = raw.substring(keyword.length()).trim();
            switch (keyword) {
                case "extends": {
                    extendsName = unquote(rest, event);
                    break;
                }
                case "block": {
                    if (rest.isEmpty()) {
                        throw new TemplateStructureException(
                                "{% block %} needs a name", event.line, event.column);
                    }
                    BlockDef block = new BlockDef(firstWord(rest),
                            event.line, event.column);
                    sinks.peek().add(block);
                    stack.push(block);
                    sinks.push(block.body);
                    break;
                }
                case "endblock": {
                    Object open = stack.peek();
                    if (!(open instanceof BlockDef)) {
                        throw new TemplateStructureException(
                                "{% endblock %} without an open block",
                                event.line, event.column);
                    }
                    stack.pop();
                    sinks.pop();
                    BlockDef block = (BlockDef) open;
                    if (blocks.containsKey(block.name)) {
                        throw new TemplateStructureException(
                                "Duplicate {% block " + block.name + " %}",
                                event.line, event.column);
                    }
                    blocks.put(block.name, block);
                    break;
                }
                case "for": {
                    int split = topLevelIn(rest);
                    if (split < 0) {
                        throw new TemplateStructureException(
                                "{% for %} must look like 'for target in iterable'",
                                event.line, event.column);
                    }
                    List<String> targets = new ArrayList<>();
                    for (String target : rest.substring(0, split).split(",")) {
                        String trimmed = target.trim();
                        if (trimmed.isEmpty()) {
                            throw new TemplateStructureException(
                                    "Empty loop target", event.line, event.column);
                        }
                        targets.add(trimmed);
                    }
                    ForBlock loop = new ForBlock(targets,
                            rest.substring(split + 4).trim(),
                            event.line, event.column);
                    sinks.peek().add(loop);
                    stack.push(loop);
                    sinks.push(loop.body);
                    break;
                }
                case "endfor": {
                    Object open = stack.peek();
                    if (!(open instanceof ForBlock)) {
                        throw new TemplateStructureException(
                                "{% endfor %} without an open for",
                                event.line, event.column);
                    }
                    stack.pop();
                    sinks.pop();
                    break;
                }
                case "if": {
                    IfBlock conditional = new IfBlock(event.line, event.column);
                    conditional.branches.add(new IfBlock.Branch(rest));
                    sinks.peek().add(conditional);
                    stack.push(conditional);
                    sinks.push(conditional.branches.get(0).body);
                    break;
                }
                case "elif": {
                    Object open = stack.peek();
                    if (!(open instanceof IfBlock)) {
                        throw new TemplateStructureException(
                                "{% elif %} without an open if",
                                event.line, event.column);
                    }
                    IfBlock conditional = (IfBlock) open;
                    ensureNoElseYet(conditional, "elif", event);
                    sinks.pop();
                    IfBlock.Branch branch = new IfBlock.Branch(rest);
                    conditional.branches.add(branch);
                    sinks.push(branch.body);
                    break;
                }
                case "else": {
                    Object open = stack.peek();
                    if (open instanceof IfBlock) {
                        IfBlock conditional = (IfBlock) open;
                        ensureNoElseYet(conditional, "else", event);
                        sinks.pop();
                        IfBlock.Branch branch = new IfBlock.Branch(null);
                        conditional.branches.add(branch);
                        sinks.push(branch.body);
                    } else if (open instanceof ForBlock) {
                        sinks.pop();
                        sinks.push(((ForBlock) open).elseBody);
                    } else {
                        throw new TemplateStructureException(
                                "{% else %} without an open if/for",
                                event.line, event.column);
                    }
                    break;
                }
                case "endif": {
                    Object open = stack.peek();
                    if (!(open instanceof IfBlock)) {
                        throw new TemplateStructureException(
                                "{% endif %} without an open if",
                                event.line, event.column);
                    }
                    stack.pop();
                    sinks.pop();
                    break;
                }
                default:
                    throw new TemplateStructureException(
                            "Unsupported template statement '{% " + keyword + " %}'",
                            event.line, event.column);
            }
        }

        if (!stack.isEmpty()) {
            Object open = stack.peek();
            String what = open instanceof ForBlock ? "{% for %}"
                    : open instanceof IfBlock ? "{% if %}" : "{% block %}";
            Node node = (Node) open;
            throw new TemplateStructureException(
                    what + " is never closed", node.line, node.column);
        }
        return new JinjaTemplateModel(
                templateName, doctypeLine, extendsName, rootNodes, blocks);
    }

    private static void ensureNoElseYet(IfBlock conditional, String kind, Event event) {
        IfBlock.Branch last = conditional.branches.get(conditional.branches.size() - 1);
        if (last.condition == null) {
            throw new TemplateStructureException(
                    "{% " + kind + " %} after {% else %}", event.line, event.column);
        }
    }

    private static String firstWord(String text) {
        int end = 0;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return text.substring(0, end);
    }

    /** Finds a top-level ' in ' separator (not inside quotes/brackets). */
    private static int topLevelIn(String text) {
        int depth = 0;
        char quote = 0;
        for (int index = 0; index + 4 <= text.length(); index++) {
            char c = text.charAt(index);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (depth == 0 && text.startsWith(" in ", index)) {
                return index;
            }
        }
        return -1;
    }

    private static String unquote(String text, Event event) {
        String trimmed = text.trim();
        if (trimmed.length() >= 2
                && ((trimmed.charAt(0) == '"' && trimmed.endsWith("\""))
                        || (trimmed.charAt(0) == '\'' && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        throw new TemplateStructureException(
                "{% extends %} needs a quoted template name", event.line, event.column);
    }
}
