package compilers.html_css.semantic;



import compilers.diagnostics.DiagnosticReporter;

import compilers.diagnostics.Diagnostics;

import compilers.html_css.ast.*;

import java.util.Set;

import java.util.HashSet;

import java.util.Stack;

import java.util.Arrays;



public class JinjaSemanticAnalyzer implements HtmlVisitor {

    private final DiagnosticReporter reporter;

    private final String sourceFile;

    private final Set<String> providedVariables;

    private final boolean crossFileValidation;

    private final Stack<Set<String>> localScopes = new Stack<>();



    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(

        "and", "or", "not", "in", "is", "if", "else", "elif", "for", "with",

        "endfor", "endif", "block", "endblock", "extends", "include",

        "url_for", "static", "range", "loop", "self", "super"

    ));



    public JinjaSemanticAnalyzer(DiagnosticReporter reporter, String sourceFile, Set<String> providedVariables) {

        this.reporter = reporter;

        this.sourceFile = sourceFile;

        this.providedVariables = providedVariables != null ? providedVariables : new HashSet<>();

        this.crossFileValidation = providedVariables != null;

        this.localScopes.push(new HashSet<>());

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

        String stmt = node.getStatement().trim();

        String[] parts = stmt.split("\\s+");

        if (parts.length == 0) return;



        String cmd = parts[0].toLowerCase();



        if (cmd.equals("for")) {

            localScopes.push(new HashSet<>(localScopes.peek()));

            if (parts.length >= 4 && parts[2].equals("in")) {

                String target = parts[1];

                for (String t : target.split(",")) {

                    localScopes.peek().add(t.trim());

                }

                StringBuilder iterableExpr = new StringBuilder();

                for (int i = 3; i < parts.length; i++) iterableExpr.append(parts[i]).append(" ");

                checkExpression(iterableExpr.toString(), node.getLine(), node.getColumn());

            }

        } else if (cmd.equals("endfor")) {

            if (localScopes.size() > 1) localScopes.pop();

        } else if (cmd.equals("block") || cmd.equals("extends") || cmd.equals("include")) {

            return;

        } else if (cmd.equals("if") || cmd.equals("elif")) {

            StringBuilder condExpr = new StringBuilder();

            for (int i = 1; i < parts.length; i++) condExpr.append(parts[i]).append(" ");

            checkExpression(condExpr.toString(), node.getLine(), node.getColumn());

        } else if (cmd.equals("set")) {

            if (parts.length >= 4 && parts[2].equals("=")) {

                localScopes.peek().add(parts[1]);

                StringBuilder valExpr = new StringBuilder();

                for (int i = 3; i < parts.length; i++) valExpr.append(parts[i]).append(" ");

                checkExpression(valExpr.toString(), node.getLine(), node.getColumn());

            }

        } else {

            checkExpression(stmt, node.getLine(), node.getColumn());

        }

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



        String[] tokens = expression.trim().split("[\\s()|,\\[\\]]+");

        for (String token : tokens) {

            token = token.trim();

            if (token.isEmpty()) continue;



            String rootVar = token;

            if (token.contains(".")) {

                rootVar = token.substring(0, token.indexOf("."));

            }



            if (isPotentialVariable(rootVar)) {

                if (!providedVariables.contains(rootVar) && !isLocal(rootVar)) {

                    if (crossFileValidation) {

                        String templateName = new java.io.File(sourceFile).getName();

                        reporter.report(Diagnostics.missingTemplateVariable(

                                templateName, rootVar, line, column, sourceFile));

                    } else {

                        reporter.report(Diagnostics.undefinedJinjaVariable(

                                rootVar, line, column, sourceFile));

                    }

                }

            }

        }

    }



    private boolean isLocal(String token) {

        for (Set<String> scope : localScopes) {

            if (scope.contains(token)) return true;

        }

        return false;

    }



    private boolean isPotentialVariable(String token) {

        if (token == null || token.isEmpty()) return false;

        if (KEYWORDS.contains(token.toLowerCase())) return false;

        if (token.matches("^[+\\-*/%=<>!&|]+$")) return false;

        if (token.matches("^\\d+(\\.\\d+)?$")) return false;

        if ((token.startsWith("\"") && token.endsWith("\"")) || (token.startsWith("'") && token.endsWith("'"))) return false;

        return token.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");

    }

}


