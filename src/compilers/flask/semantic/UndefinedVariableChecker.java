package compilers.flask.semantic;

import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.SymbolTable.SymbolEntry;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.SymbolTable.SymbolTableBuilder;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.expressions.access.AttributeAccessNode;
import compilers.flask.ast.nodes.expressions.access.SubscriptNode;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.expressions.atoms.ListNode;
import compilers.flask.ast.nodes.expressions.atoms.TupleNode;
import compilers.flask.ast.nodes.statements.compound.ForStatementNode;
import compilers.flask.ast.nodes.statements.compound.WithStatementNode;
import compilers.flask.ast.nodes.statements.simple.AssignmentNode;
import compilers.flask.ast.nodes.helpers.WithItem;

/** Pass 2 check for unresolved names and local reads before their first binding. */
public final class UndefinedVariableChecker extends ScopedSemanticVisitor<Void> {
    public UndefinedVariableChecker(
            SymbolTable symbolTable,
            DiagnosticReporter reporter,
            String sourceFile) {
        super(symbolTable, reporter, sourceFile);
    }

    @Override
    public Void visitIdentifier(IdentifierNode node) {
        String name = node.getName();
        SymbolEntry entry = currentScope.lookup(name);
        if (entry == null) {
            if (!PythonBuiltins.contains(name) && !currentScope.hasVisibleWildcardImport()) {
                reportUndefined(node);
            }
            return null;
        }

        if (isLocalReadBeforeBinding(node, entry)) {
            reportUndefined(node);
        }
        return null;
    }

    @Override
    public Void visitAssignment(AssignmentNode node) {
        if (node.isAugmented()) {
            // += reads the previous target before evaluating the RHS.
            node.getTarget().accept(this);
            node.getValue().accept(this);
        } else {
            node.getValue().accept(this);
            visitStoreAddress(node.getTarget());
        }
        return null;
    }

    @Override
    public Void visitForStatement(ForStatementNode node) {
        node.getIterable().accept(this);
        visitStoreAddress(node.getTarget());
        visitStatements(node.getBody());
        if (node.hasElse()) {
            visitStatements(node.getElseBody());
        }
        return null;
    }

    @Override
    public Void visitWithStatement(WithStatementNode node) {
        for (WithItem item : node.getItems()) {
            item.getContextExpr().accept(this);
            if (item.hasAsName()) {
                visitStoreAddress(item.getAsName());
            }
        }
        visitStatements(node.getBody());
        return null;
    }

    private void reportUndefined(IdentifierNode node) {
        reporter.report(Diagnostics.undefinedVariable(
                node.getName(), node.getLine(), node.getColumn(), sourceFile));
    }

    private boolean isLocalReadBeforeBinding(IdentifierNode use, SymbolEntry entry) {
        if (currentScope.lookupLocal(use.getName()) != entry) {
            return false;
        }

        ASTNode firstBinding = entry.getAttribute(
                SymbolTableBuilder.FIRST_BINDING_NODE, ASTNode.class);
        if (firstBinding == null
                || firstBinding instanceof compilers.flask.ast.nodes.statements.compound.FunctionDefNode
                || firstBinding instanceof compilers.flask.ast.nodes.statements.compound.ClassDefNode) {
            return false;
        }

        if (use.getLine() < firstBinding.getLine()) {
            return true;
        }
        return use.getLine() == firstBinding.getLine()
                && enclosingStatement(use) == firstBinding;
    }

    private ASTNode enclosingStatement(ASTNode node) {
        ASTNode current = node;
        while (current != null && !current.isStatement()) {
            current = current.getParent();
        }
        return current;
    }

    private void visitStoreAddress(Expression target) {
        if (target instanceof IdentifierNode) {
            return;
        }
        if (target instanceof TupleNode) {
            for (Expression element : ((TupleNode) target).getElements()) {
                visitStoreAddress(element);
            }
            return;
        }
        if (target instanceof ListNode) {
            for (Expression element : ((ListNode) target).getElements()) {
                visitStoreAddress(element);
            }
            return;
        }
        if (target instanceof AttributeAccessNode) {
            ((AttributeAccessNode) target).getObject().accept(this);
            return;
        }
        if (target instanceof SubscriptNode) {
            SubscriptNode subscript = (SubscriptNode) target;
            subscript.getObject().accept(this);
            subscript.getIndex().accept(this);
            return;
        }
        target.accept(this);
    }
}
