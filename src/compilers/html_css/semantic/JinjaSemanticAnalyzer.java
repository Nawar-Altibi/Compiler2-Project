package compilers.html_css.semantic;

import compilers.flask.semantic.ErrorReporter;
import compilers.flask.semantic.MissingTemplateVariableError;
import compilers.html_css.ast.*;
import java.util.Set;
import java.util.HashSet;

public class JinjaSemanticAnalyzer implements HtmlVisitor {
    private final ErrorReporter errorReporter;
    private final String sourceFile;
    private final Set<String> providedVariables;

    public JinjaSemanticAnalyzer(ErrorReporter errorReporter, String sourceFile, Set<String> providedVariables) {
        this.errorReporter = errorReporter;
        this.sourceFile = sourceFile;
        this.providedVariables = providedVariables != null ? providedVariables : new HashSet<>();
    }

    public void analyze(HtmlNode root) {
        root.accept(this);
    }

    @Override
    public void visit(HtmlDocumentNode node) {
        for (HtmlNode child : node.getChildren()) child.accept(this);
    }

    @Override
    public void visit(ElementNode node) {
        for (AttributeNode attr : node.getAttributes()) attr.accept(this);
        for (HtmlNode child : node.getChildren()) child.accept(this);
    }

    @Override
    public void visit(AttributeNode node) {
        if (node.getValue() != null) node.getValue().accept(this);
    }

    @Override
    public void visit(TextNode node) {}

    @Override
    public void visit(JinjaExpressionNode node) {
        checkExpression(node.getExpression(), node.getLine(), node.getColumn());
    }

    @Override
    public void visit(JinjaStatementNode node) {
        // Jinja statements like {% if user %} or {% for item in items %}
        // For now, just check variables in them
        checkExpression(node.getStatement(), node.getLine(), node.getColumn());
    }

    @Override
    public void visit(LiteralAttributeValueNode node) {}

    @Override
    public void visit(JinjaAttributeValueNode node) {
        checkExpression(node.getExpression(), node.getLine(), node.getColumn());
    }

    @Override
    public void visit(StyleNode node) {}

    private void checkExpression(String expression, int line, int column) {
        if (expression == null) return;
        String[] tokens = expression.trim().split("[\\s.()\\[\\]|]+");
        for (String token : tokens) {
            token = token.trim();
            if (!token.isEmpty() && isPotentialVariable(token)) {
                if (!providedVariables.contains(token)) {
                    String templateName = new java.io.File(sourceFile).getName();
                    errorReporter.report(new MissingTemplateVariableError(templateName, token, line, column, sourceFile));
                }
            }
        }
    }

    private boolean isPotentialVariable(String token) {
        if (token == null || token.isEmpty()) return false;
        String[] keywords = {"and","or","not","in","is","if","else","for","with", "endfor", "endif", "block", "endblock", "extends", "include"};
        for (String keyword : keywords) if (token.equalsIgnoreCase(keyword)) return false;
        if (token.matches("^[+\\-*/%=<>!]+$")) return false;
        if (token.matches("^\\d+(\\.\\d+)?$")) return false;
        if ((token.startsWith("\"") && token.endsWith("\"")) || (token.startsWith("'") && token.endsWith("'"))) return false;
        return token.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }
}
