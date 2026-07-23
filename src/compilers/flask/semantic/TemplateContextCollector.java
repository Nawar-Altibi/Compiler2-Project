package compilers.flask.semantic;

import compilers.flask.Visitor.ASTBaseVisitor;
import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.expressions.access.FunctionCallNode;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.expressions.atoms.LiteralNode;
import compilers.flask.ast.nodes.helpers.CallArgument;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Collects keyword context passed to render_template calls. */
public final class TemplateContextCollector extends ASTBaseVisitor<Void> {
    private final Map<String, Set<String>> templateContexts = new LinkedHashMap<>();

    public Map<String, Set<String>> getTemplateContexts() {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : templateContexts.entrySet()) {
            copy.put(
                    entry.getKey(),
                    Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public Void visitFunctionCall(FunctionCallNode node) {
        if (isRenderTemplate(node.getFunction())
                && !node.getArguments().isEmpty()
                && node.getArguments().get(0).isPositional()
                && node.getArguments().get(0).getValue() instanceof LiteralNode) {
            LiteralNode templateLiteral =
                    (LiteralNode) node.getArguments().get(0).getValue();
            if (templateLiteral.getLiteralType() == LiteralNode.LiteralType.STRING
                    && templateLiteral.getValue() != null) {
                String templateName = String.valueOf(templateLiteral.getValue());
                Set<String> variables = templateContexts.computeIfAbsent(
                        templateName,
                        ignored -> new LinkedHashSet<String>());
                for (CallArgument argument : node.getArguments()) {
                    if (argument.isKeyword()) {
                        variables.add(argument.getKeywordName());
                    }
                }
            }
        }
        return super.visitFunctionCall(node);
    }

    private boolean isRenderTemplate(Expression expression) {
        return expression instanceof IdentifierNode
                && "render_template".equals(((IdentifierNode) expression).getName());
    }
}
