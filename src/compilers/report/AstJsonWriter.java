package compilers.report;

import compilers.flask.Visitor.ASTBaseVisitor;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.expressions.access.AttributeAccessNode;
import compilers.flask.ast.nodes.expressions.access.FunctionCallNode;
import compilers.flask.ast.nodes.expressions.access.SubscriptNode;
import compilers.flask.ast.nodes.expressions.atoms.DictNode;
import compilers.flask.ast.nodes.expressions.atoms.FStringNode;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.expressions.atoms.ListNode;
import compilers.flask.ast.nodes.expressions.atoms.LiteralNode;
import compilers.flask.ast.nodes.expressions.atoms.SetNode;
import compilers.flask.ast.nodes.expressions.atoms.TupleNode;
import compilers.flask.ast.nodes.expressions.operations.BinaryOpNode;
import compilers.flask.ast.nodes.expressions.operations.CompareNode;
import compilers.flask.ast.nodes.expressions.operations.UnaryOpNode;
import compilers.flask.ast.nodes.helpers.CallArgument;
import compilers.flask.ast.nodes.helpers.Parameter;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.ast.nodes.statements.compound.ClassDefNode;
import compilers.flask.ast.nodes.statements.compound.ForStatementNode;
import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;
import compilers.flask.ast.nodes.statements.compound.IfStatementNode;
import compilers.flask.ast.nodes.statements.compound.TryStatementNode;
import compilers.flask.ast.nodes.statements.compound.WhileStatementNode;
import compilers.flask.ast.nodes.statements.compound.WithStatementNode;
import compilers.flask.ast.nodes.statements.imports.FromImportNode;
import compilers.flask.ast.nodes.statements.imports.ImportNode;
import compilers.flask.ast.nodes.statements.simple.AssertNode;
import compilers.flask.ast.nodes.statements.simple.AssignmentNode;
import compilers.flask.ast.nodes.statements.simple.BreakNode;
import compilers.flask.ast.nodes.statements.simple.ContinueNode;
import compilers.flask.ast.nodes.statements.simple.DelNode;
import compilers.flask.ast.nodes.statements.simple.ExpressionStatementNode;
import compilers.flask.ast.nodes.statements.simple.GlobalNode;
import compilers.flask.ast.nodes.statements.simple.PassNode;
import compilers.flask.ast.nodes.statements.simple.RaiseNode;
import compilers.flask.ast.nodes.statements.simple.ReturnNode;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes the Python AST and the Jinja/HTML ASTs into the two
 * deterministic report files of plan section 7.2 —
 * {@code ast_python.json} and {@code ast_jinja.json}.
 *
 * <p>Uniform node shape: {@code {"kind", "line", "column", scalars…,
 * "children": […]}} with fixed key order, 2-space indent, LF endings.
 * Hand-rolled writer — no external libraries.</p>
 */
public final class AstJsonWriter {

    // ==================== tiny JSON tree ====================

    private static final class JsonNode {
        final String kind;
        final int line;
        final int column;
        final Map<String, String> scalars = new LinkedHashMap<>(); // pre-encoded
        final List<JsonNode> children = new ArrayList<>();

        JsonNode(String kind, int line, int column) {
            this.kind = kind;
            this.line = line;
            this.column = column;
        }
    }

    private AstJsonWriter() {
    }

    /** {@code ast_python.json}: one JSON object for the whole module. */
    public static String pythonAstJson(ProgramNode program) {
        PythonJsonVisitor visitor = new PythonJsonVisitor();
        program.accept(visitor);
        StringBuilder out = new StringBuilder();
        writeNode(visitor.rootResult, out, 0);
        out.append('\n');
        return out.toString();
    }

    /** {@code ast_jinja.json}: {"templates": {name: tree, …}}. */
    public static String jinjaAstJson(Map<String, HtmlNode> templates) {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"templates\": {");
        boolean first = true;
        for (Map.Entry<String, HtmlNode> entry : templates.entrySet()) {
            if (!first) {
                out.append(',');
            }
            out.append('\n').append("    ").append(quote(entry.getKey())).append(": ");
            writeNode(htmlToJson(entry.getValue()), out, 2);
            first = false;
        }
        out.append(templates.isEmpty() ? "}" : "\n  }");
        out.append("\n}\n");
        return out.toString();
    }

    // ==================== serialization ====================

    private static void writeNode(JsonNode node, StringBuilder out, int depth) {
        String pad = "  ".repeat(depth);
        String inner = "  ".repeat(depth + 1);
        out.append("{\n");
        out.append(inner).append("\"kind\": ").append(quote(node.kind)).append(",\n");
        out.append(inner).append("\"line\": ").append(node.line).append(",\n");
        out.append(inner).append("\"column\": ").append(node.column);
        for (Map.Entry<String, String> scalar : node.scalars.entrySet()) {
            out.append(",\n").append(inner)
                    .append(quote(scalar.getKey())).append(": ").append(scalar.getValue());
        }
        if (!node.children.isEmpty()) {
            out.append(",\n").append(inner).append("\"children\": [\n");
            for (int index = 0; index < node.children.size(); index++) {
                out.append("  ".repeat(depth + 2));
                writeNode(node.children.get(index), out, depth + 2);
                if (index + 1 < node.children.size()) {
                    out.append(',');
                }
                out.append('\n');
            }
            out.append(inner).append(']');
        }
        out.append('\n').append(pad).append('}');
    }

    private static String quote(String text) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            switch (c) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
            }
        }
        return escaped.append('"').toString();
    }

    private static String quoteList(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                out.append(", ");
            }
            out.append(quote(values.get(index)));
        }
        return out.append(']').toString();
    }

    // ==================== Python AST → JSON ====================

    /**
     * Stack-based builder: every visit opens a node, lets the base visitor
     * descend (children attach themselves), then closes it.
     */
    private static final class PythonJsonVisitor extends ASTBaseVisitor<Void> {
        JsonNode rootResult;
        private JsonNode current;

        private Void wrap(ASTNode node, String kind, Map<String, String> scalars,
                Runnable descend) {
            JsonNode json = new JsonNode(kind, node.getLine(), node.getColumn());
            json.scalars.putAll(scalars);
            if (current == null) {
                rootResult = json;
            } else {
                current.children.add(json);
            }
            JsonNode parent = current;
            current = json;
            try {
                descend.run();
            } finally {
                current = parent;
            }
            return null;
        }

        private static Map<String, String> none() {
            return new LinkedHashMap<>();
        }

        private static Map<String, String> one(String key, String encoded) {
            Map<String, String> scalars = new LinkedHashMap<>();
            scalars.put(key, encoded);
            return scalars;
        }

        @Override
        public Void visitProgram(ProgramNode node) {
            return wrap(node, "Program", none(), () -> super.visitProgram(node));
        }

        @Override
        public Void visitLiteral(LiteralNode node) {
            Map<String, String> scalars = new LinkedHashMap<>();
            scalars.put("type", quote(String.valueOf(node.getLiteralType())));
            Object value = node.getValue();
            scalars.put("value", value == null
                    ? "null"
                    : value instanceof Boolean || value instanceof Number
                            ? String.valueOf(value)
                            : quote(String.valueOf(value)));
            return wrap(node, "Literal", scalars, () -> { });
        }

        @Override
        public Void visitIdentifier(IdentifierNode node) {
            return wrap(node, "Identifier",
                    one("name", quote(node.getName())), () -> { });
        }

        @Override
        public Void visitBinaryOp(BinaryOpNode node) {
            return wrap(node, "BinaryOp", one("operator", quote(node.getOperator())),
                    () -> super.visitBinaryOp(node));
        }

        @Override
        public Void visitUnaryOp(UnaryOpNode node) {
            return wrap(node, "UnaryOp", one("operator", quote(node.getOperator())),
                    () -> super.visitUnaryOp(node));
        }

        @Override
        public Void visitCompare(CompareNode node) {
            List<String> operators = new ArrayList<>();
            for (CompareNode.CompareOp op : node.getOperators()) {
                operators.add(op.getSymbol());
            }
            return wrap(node, "Compare", one("operators", quoteList(operators)),
                    () -> super.visitCompare(node));
        }

        @Override
        public Void visitAttributeAccess(AttributeAccessNode node) {
            return wrap(node, "AttributeAccess",
                    one("attribute", quote(node.getAttribute())),
                    () -> super.visitAttributeAccess(node));
        }

        @Override
        public Void visitSubscript(SubscriptNode node) {
            return wrap(node, "Subscript", none(), () -> super.visitSubscript(node));
        }

        @Override
        public Void visitFunctionCall(FunctionCallNode node) {
            List<String> keywords = new ArrayList<>();
            for (CallArgument argument : node.getArguments()) {
                if (argument.isKeyword()) {
                    keywords.add(argument.getKeywordName());
                }
            }
            Map<String, String> scalars = keywords.isEmpty()
                    ? none()
                    : one("keywords", quoteList(keywords));
            return wrap(node, "FunctionCall", scalars,
                    () -> super.visitFunctionCall(node));
        }

        @Override
        public Void visitAssignment(AssignmentNode node) {
            return wrap(node, "Assignment", one("operator", quote(node.getOperator())),
                    () -> super.visitAssignment(node));
        }

        @Override
        public Void visitExpressionStatement(ExpressionStatementNode node) {
            return wrap(node, "ExpressionStatement", none(),
                    () -> super.visitExpressionStatement(node));
        }

        @Override
        public Void visitFunctionDef(FunctionDefNode node) {
            List<String> parameters = new ArrayList<>();
            for (Parameter parameter : node.getParameters()) {
                parameters.add(parameter.getName());
            }
            Map<String, String> scalars = new LinkedHashMap<>();
            scalars.put("name", quote(node.getName()));
            scalars.put("parameters", quoteList(parameters));
            return wrap(node, "FunctionDef", scalars,
                    () -> super.visitFunctionDef(node));
        }

        @Override
        public Void visitClassDef(ClassDefNode node) {
            return wrap(node, "ClassDef", one("name", quote(node.getName())),
                    () -> super.visitClassDef(node));
        }

        @Override
        public Void visitIfStatement(IfStatementNode node) {
            return wrap(node, "If", none(), () -> super.visitIfStatement(node));
        }

        @Override
        public Void visitForStatement(ForStatementNode node) {
            return wrap(node, "For", none(), () -> super.visitForStatement(node));
        }

        @Override
        public Void visitWhileStatement(WhileStatementNode node) {
            return wrap(node, "While", none(), () -> super.visitWhileStatement(node));
        }

        @Override
        public Void visitWithStatement(WithStatementNode node) {
            return wrap(node, "With", none(), () -> super.visitWithStatement(node));
        }

        @Override
        public Void visitTryStatement(TryStatementNode node) {
            return wrap(node, "Try", none(), () -> super.visitTryStatement(node));
        }

        @Override
        public Void visitImport(ImportNode node) {
            Map<String, String> scalars = new LinkedHashMap<>();
            scalars.put("module", quote(node.getModuleName()));
            if (node.hasAlias()) {
                scalars.put("alias", quote(node.getAsName()));
            }
            return wrap(node, "Import", scalars, () -> { });
        }

        @Override
        public Void visitFromImport(FromImportNode node) {
            List<String> names = new ArrayList<>();
            if (node.isImportAll()) {
                names.add("*");
            } else {
                for (FromImportNode.ImportItem item : node.getItems()) {
                    names.add(item.hasAlias()
                            ? item.getName() + " as " + item.getAsName()
                            : item.getName());
                }
            }
            Map<String, String> scalars = new LinkedHashMap<>();
            scalars.put("module", quote(node.getModuleName()));
            scalars.put("names", quoteList(names));
            return wrap(node, "FromImport", scalars, () -> { });
        }

        @Override
        public Void visitReturn(ReturnNode node) {
            return wrap(node, "Return", none(), () -> super.visitReturn(node));
        }

        @Override
        public Void visitPass(PassNode node) {
            return wrap(node, "Pass", none(), () -> { });
        }

        @Override
        public Void visitBreak(BreakNode node) {
            return wrap(node, "Break", none(), () -> { });
        }

        @Override
        public Void visitContinue(ContinueNode node) {
            return wrap(node, "Continue", none(), () -> { });
        }

        @Override
        public Void visitDel(DelNode node) {
            return wrap(node, "Del", none(), () -> super.visitDel(node));
        }

        @Override
        public Void visitAssert(AssertNode node) {
            return wrap(node, "Assert", none(), () -> super.visitAssert(node));
        }

        @Override
        public Void visitGlobal(GlobalNode node) {
            return wrap(node, "Global", one("names", quoteList(node.getNames())),
                    () -> { });
        }

        @Override
        public Void visitRaise(RaiseNode node) {
            return wrap(node, "Raise", none(), () -> super.visitRaise(node));
        }

        @Override
        public Void visitList(ListNode node) {
            return wrap(node, "List", none(), () -> super.visitList(node));
        }

        @Override
        public Void visitDict(DictNode node) {
            return wrap(node, "Dict", none(), () -> super.visitDict(node));
        }

        @Override
        public Void visitTuple(TupleNode node) {
            return wrap(node, "Tuple", none(), () -> super.visitTuple(node));
        }

        @Override
        public Void visitSet(SetNode node) {
            return wrap(node, "Set", none(), () -> super.visitSet(node));
        }

        @Override
        public Void visitFString(FStringNode node) {
            return wrap(node, "FString", none(), () -> super.visitFString(node));
        }
    }

    // ==================== Jinja/HTML AST → JSON ====================

    private static JsonNode htmlToJson(HtmlNode node) {
        if (node instanceof HtmlDocumentNode) {
            JsonNode json = new JsonNode("Document", node.getLine(), node.getColumn());
            for (HtmlNode child : ((HtmlDocumentNode) node).getChildren()) {
                json.children.add(htmlToJson(child));
            }
            return json;
        }
        if (node instanceof ElementNode) {
            ElementNode element = (ElementNode) node;
            JsonNode json = new JsonNode("Element", node.getLine(), node.getColumn());
            json.scalars.put("tag", quote(element.getTagName()));
            for (AttributeNode attribute : element.getAttributes()) {
                json.children.add(htmlToJson(attribute));
            }
            for (HtmlNode child : element.getChildren()) {
                json.children.add(htmlToJson(child));
            }
            return json;
        }
        if (node instanceof AttributeNode) {
            AttributeNode attribute = (AttributeNode) node;
            JsonNode json = new JsonNode("Attribute", node.getLine(), node.getColumn());
            json.scalars.put("name", quote(attribute.getName()));
            if (attribute.getValue() instanceof JinjaAttributeValueNode) {
                json.scalars.put("expression", quote(
                        ((JinjaAttributeValueNode) attribute.getValue()).getExpression()));
            } else if (attribute.getValue() instanceof LiteralAttributeValueNode) {
                json.scalars.put("value", quote(
                        ((LiteralAttributeValueNode) attribute.getValue()).getValue()));
            }
            return json;
        }
        if (node instanceof TextNode) {
            JsonNode json = new JsonNode("Text", node.getLine(), node.getColumn());
            json.scalars.put("text", quote(((TextNode) node).getText()));
            return json;
        }
        if (node instanceof JinjaExpressionNode) {
            JsonNode json = new JsonNode("JinjaExpression",
                    node.getLine(), node.getColumn());
            json.scalars.put("expression",
                    quote(((JinjaExpressionNode) node).getExpression()));
            return json;
        }
        if (node instanceof JinjaStatementNode) {
            JsonNode json = new JsonNode("JinjaStatement",
                    node.getLine(), node.getColumn());
            json.scalars.put("statement",
                    quote(((JinjaStatementNode) node).getStatement()));
            return json;
        }
        if (node instanceof StyleNode) {
            JsonNode json = new JsonNode("Style", node.getLine(), node.getColumn());
            json.scalars.put("css", quote(((StyleNode) node).getRawCss()));
            return json;
        }
        JsonNode json = new JsonNode(node.getNodeName(),
                node.getLine(), node.getColumn());
        return json;
    }
}
