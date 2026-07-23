package compilers.flask.semantic;

import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.SymbolTable.SymbolEntry;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.expressions.access.FunctionCallNode;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.helpers.Parameter;
import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;

/** Validates arity for direct calls to functions declared in the source. */
public final class FunctionCallChecker extends ScopedSemanticVisitor<Void> {
    public FunctionCallChecker(
            SymbolTable symbolTable,
            DiagnosticReporter reporter,
            String sourceFile) {
        super(symbolTable, reporter, sourceFile);
    }

    @Override
    public Void visitFunctionCall(FunctionCallNode node) {
        Expression functionExpression = node.getFunction();

        // Method calls require receiver/class resolution and are deliberately
        // outside this phase. Only direct name(...) calls are checked.
        if (functionExpression instanceof IdentifierNode) {
            String functionName = ((IdentifierNode) functionExpression).getName();
            SymbolEntry entry = currentScope.lookup(functionName);
            if (entry != null && entry.getFunctionNode() != null
                    // A decorator may replace the function with any callable
                    // contract, so the original signature is no longer a
                    // sound static arity source.
                    && !entry.getFunctionNode().hasDecorators()) {
                checkArity(functionName, entry.getFunctionNode(), node);
            }
        }

        return super.visitFunctionCall(node);
    }

    private void checkArity(
            String functionName,
            FunctionDefNode definition,
            FunctionCallNode call) {
        int total = definition.getParameters().size();
        int required = 0;
        for (Parameter parameter : definition.getParameters()) {
            if (!parameter.hasDefault()) {
                required++;
            }
        }

        int provided = call.getArguments().size();
        if (provided >= required && provided <= total) {
            return;
        }

        String expected = required == total
                ? String.valueOf(total)
                : required + "-" + total;
        reporter.report(Diagnostics.functionCallError(
                "Function '" + functionName + "' expects " + expected
                        + " argument(s) but got " + provided,
                call.getLine(),
                call.getColumn(),
                sourceFile));
    }
}
