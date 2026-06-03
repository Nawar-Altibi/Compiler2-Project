package compilers.flask.semantic;

import compilers.flask.Visitor.ASTBaseVisitor;
import compilers.flask.ast.nodes.expressions.access.FunctionCallNode;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.expressions.atoms.LiteralNode;
import compilers.flask.ast.nodes.Expression;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;

public class TemplateContextCollector extends ASTBaseVisitor<Void> {
    private final Map<String, Set<String>> templateContexts = new HashMap<>();

    public Map<String, Set<String>> getTemplateContexts() {
        return templateContexts;
    }

    @Override
    public Void visitFunctionCall(FunctionCallNode node) {
        Expression funcExpr = node.getFunction();
        if (funcExpr instanceof IdentifierNode && ((IdentifierNode) funcExpr).getName().equals("render_template")) {
            List<Expression> args = node.getArgs();
            if (!args.isEmpty() && args.get(0) instanceof LiteralNode) {
                LiteralNode templateNameLiteral = (LiteralNode) args.get(0);
                if (templateNameLiteral.getLiteralType() == LiteralNode.LiteralType.STRING) {
                    String templateName = templateNameLiteral.getValue().toString().replace("\"", "").replace("'", "");
                    Set<String> vars = templateContexts.computeIfAbsent(templateName, k -> new HashSet<>());
                    
                    // Collect keyword arguments as provided variables
                    for (String key : node.getKwargs().keySet()) {
                        vars.add(key);
                    }
                }
            }
        }
        return super.visitFunctionCall(node);
    }
}
