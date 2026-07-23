package compilers.flask.semantic.validation;

import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.Visitor.ASTBaseVisitor;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.Statement;
import compilers.flask.ast.nodes.expressions.access.AttributeAccessNode;
import compilers.flask.ast.nodes.expressions.access.FunctionCallNode;
import compilers.flask.ast.nodes.expressions.access.SubscriptNode;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.expressions.atoms.ListNode;
import compilers.flask.ast.nodes.expressions.atoms.TupleNode;
import compilers.flask.ast.nodes.helpers.CallArgument;
import compilers.flask.ast.nodes.helpers.DecoratorNode;
import compilers.flask.ast.nodes.helpers.ExceptClause;
import compilers.flask.ast.nodes.helpers.Parameter;
import compilers.flask.ast.nodes.helpers.WithItem;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.ast.nodes.statements.compound.ClassDefNode;
import compilers.flask.ast.nodes.statements.compound.ForStatementNode;
import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;
import compilers.flask.ast.nodes.statements.compound.TryStatementNode;
import compilers.flask.ast.nodes.statements.compound.WithStatementNode;
import compilers.flask.ast.nodes.statements.imports.FromImportNode;
import compilers.flask.ast.nodes.statements.imports.ImportNode;
import compilers.flask.ast.nodes.statements.simple.AssignmentNode;
import compilers.flask.ast.nodes.statements.simple.DelNode;
import compilers.flask.ast.nodes.statements.simple.GlobalNode;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Checks source-order scope rules that require the completed symbol-table
 * pass.  Binding layout remains a separate later phase.
 */
public final class ScopeRuleChecker extends ASTBaseVisitor<Void> {
    private final SymbolTable rootScope;
    private final DiagnosticReporter reporter;
    private final String sourceFile;
    private final Deque<ScopeContext> scopes = new ArrayDeque<>();
    private boolean checking;

    public ScopeRuleChecker(
            SymbolTable rootScope,
            DiagnosticReporter reporter,
            String sourceFile) {
        this.rootScope = Objects.requireNonNull(rootScope, "rootScope");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.sourceFile = normalizeSourceFile(sourceFile);
    }

    /** Runs one isolated scope-rule pass and returns whether it added no errors. */
    public boolean check(ProgramNode program) {
        Objects.requireNonNull(program, "program");
        if (checking) {
            throw new IllegalStateException("ScopeRuleChecker is already running");
        }

        int errorsBefore = reporter.errors().size();
        checking = true;
        scopes.clear();
        try {
            program.accept(this);
        } finally {
            scopes.clear();
            checking = false;
        }
        return reporter.errors().size() == errorsBefore;
    }

    public DiagnosticReporter getReporter() {
        return reporter;
    }

    @Override
    public Void visitProgram(ProgramNode node) {
        validateAttachedScope(node, rootScope, SymbolTable.ScopeType.GLOBAL);
        return inScope(
                ExecutableBlockKind.MODULE,
                rootScope,
                Collections.<String>emptySet(),
                node.getStatements());
    }

    @Override
    public Void visitGlobal(GlobalNode node) {
        ScopeContext context = currentScope();
        for (String name : node.getNames()) {
            if (context.declaredGlobals.contains(name)) {
                continue;
            }

            if (context.parameterNames.contains(name)) {
                reportGlobalConflict(name, "is a parameter declared before its", node);
            } else {
                EnumSet<OccurrenceKind> occurrences = context.priorOccurrences.get(name);
                if (occurrences != null && occurrences.contains(OccurrenceKind.USE)) {
                    reportGlobalConflict(name, "is used before its", node);
                } else if (occurrences != null
                        && (occurrences.contains(OccurrenceKind.STORE)
                        || occurrences.contains(OccurrenceKind.DELETE))) {
                    reportGlobalConflict(name, "is assigned before its", node);
                }
            }

            if (!context.symbols.isGlobalDeclared(name)) {
                reporter.report(Diagnostics.scopeError(
                        "Symbol-table scope '" + context.symbols.getScopeName()
                                + "' did not record global declaration '" + name + "'",
                        node.getLine(),
                        node.getColumn(),
                        sourceFile));
            }
            context.declaredGlobals.add(name);
        }
        return null;
    }

    @Override
    public Void visitIdentifier(IdentifierNode node) {
        record(node.getName(), OccurrenceKind.USE);
        return null;
    }

    @Override
    public Void visitFunctionCall(FunctionCallNode node) {
        node.getFunction().accept(this);
        for (CallArgument argument : node.getArguments()) {
            argument.getValue().accept(this);
        }
        return null;
    }

    @Override
    public Void visitAssignment(AssignmentNode node) {
        if (node.isAugmented()) {
            visitTargetLoad(node.getTarget());
            node.getValue().accept(this);
            visitStoreTarget(node.getTarget());
        } else {
            node.getValue().accept(this);
            visitStoreTarget(node.getTarget());
        }
        return null;
    }

    @Override
    public Void visitDel(DelNode node) {
        for (Expression target : node.getTargets()) {
            visitDeleteTarget(target);
        }
        return null;
    }

    @Override
    public Void visitImport(ImportNode node) {
        record(node.getEffectiveName(), OccurrenceKind.STORE);
        return null;
    }

    @Override
    public Void visitFromImport(FromImportNode node) {
        if (!node.isImportAll()) {
            for (FromImportNode.ImportItem item : node.getItems()) {
                record(item.getEffectiveName(), OccurrenceKind.STORE);
            }
        }
        return null;
    }

    @Override
    public Void visitFunctionDef(FunctionDefNode node) {
        for (DecoratorNode decorator : node.getDecorators()) {
            decorator.getExpression().accept(this);
        }
        for (Parameter parameter : node.getParameters()) {
            if (parameter.hasDefault()) {
                parameter.getDefaultValue().accept(this);
            }
            if (parameter.hasTypeHint()) {
                parameter.getTypeHint().accept(this);
            }
        }
        if (node.hasReturnType()) {
            node.getReturnType().accept(this);
        }

        record(node.getName(), OccurrenceKind.STORE);

        Set<String> parameterNames = new LinkedHashSet<>();
        for (Parameter parameter : node.getParameters()) {
            parameterNames.add(parameter.getName());
        }
        SymbolTable functionScope = validateChildScope(
                node, node.getScope(), SymbolTable.ScopeType.FUNCTION);
        return inScope(
                ExecutableBlockKind.FUNCTION,
                functionScope,
                parameterNames,
                node.getBody());
    }

    @Override
    public Void visitClassDef(ClassDefNode node) {
        for (DecoratorNode decorator : node.getDecorators()) {
            decorator.getExpression().accept(this);
        }
        for (Expression base : node.getBases()) {
            base.accept(this);
        }

        record(node.getName(), OccurrenceKind.STORE);

        SymbolTable classScope = validateChildScope(
                node, node.getScope(), SymbolTable.ScopeType.CLASS);
        return inScope(
                ExecutableBlockKind.CLASS,
                classScope,
                Collections.<String>emptySet(),
                node.getBody());
    }

    @Override
    public Void visitForStatement(ForStatementNode node) {
        node.getIterable().accept(this);
        visitStoreTarget(node.getTarget());
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
                visitStoreTarget(item.getAsName());
            }
        }
        visitStatements(node.getBody());
        return null;
    }

    @Override
    public Void visitTryStatement(TryStatementNode node) {
        visitStatements(node.getTryBody());
        for (ExceptClause clause : node.getExceptClauses()) {
            if (!clause.isBareExcept()) {
                clause.getExceptionType().accept(this);
            }
            if (clause.hasAsName()) {
                record(clause.getAsName(), OccurrenceKind.STORE);
            }
            visitStatements(clause.getBody());
        }
        if (node.hasElse()) {
            visitStatements(node.getElseBody());
        }
        if (node.hasFinally()) {
            visitStatements(node.getFinallyBody());
        }
        return null;
    }

    private Void inScope(
            ExecutableBlockKind kind,
            SymbolTable symbols,
            Set<String> parameterNames,
            List<Statement> statements) {
        scopes.push(new ScopeContext(kind, symbols, parameterNames));
        try {
            visitStatements(statements);
        } finally {
            scopes.pop();
        }
        return null;
    }

    private void visitStatements(List<Statement> statements) {
        for (Statement statement : statements) {
            if (statement != null) {
                statement.accept(this);
            }
        }
    }

    private void visitTargetLoad(Expression target) {
        if (target instanceof IdentifierNode) {
            record(((IdentifierNode) target).getName(), OccurrenceKind.USE);
        } else if (target instanceof AttributeAccessNode) {
            ((AttributeAccessNode) target).getObject().accept(this);
        } else if (target instanceof SubscriptNode) {
            SubscriptNode subscript = (SubscriptNode) target;
            subscript.getObject().accept(this);
            subscript.getIndex().accept(this);
        } else if (target != null) {
            target.accept(this);
        }
    }

    private void visitStoreTarget(Expression target) {
        if (target instanceof IdentifierNode) {
            record(((IdentifierNode) target).getName(), OccurrenceKind.STORE);
        } else if (target instanceof AttributeAccessNode) {
            ((AttributeAccessNode) target).getObject().accept(this);
        } else if (target instanceof SubscriptNode) {
            SubscriptNode subscript = (SubscriptNode) target;
            subscript.getObject().accept(this);
            subscript.getIndex().accept(this);
        } else if (target instanceof TupleNode) {
            for (Expression element : ((TupleNode) target).getElements()) {
                visitStoreTarget(element);
            }
        } else if (target instanceof ListNode) {
            for (Expression element : ((ListNode) target).getElements()) {
                visitStoreTarget(element);
            }
        } else if (target != null) {
            target.accept(this);
        }
    }

    private void visitDeleteTarget(Expression target) {
        if (target instanceof IdentifierNode) {
            record(((IdentifierNode) target).getName(), OccurrenceKind.DELETE);
        } else if (target instanceof AttributeAccessNode) {
            ((AttributeAccessNode) target).getObject().accept(this);
        } else if (target instanceof SubscriptNode) {
            SubscriptNode subscript = (SubscriptNode) target;
            subscript.getObject().accept(this);
            subscript.getIndex().accept(this);
        } else if (target instanceof TupleNode) {
            for (Expression element : ((TupleNode) target).getElements()) {
                visitDeleteTarget(element);
            }
        } else if (target instanceof ListNode) {
            for (Expression element : ((ListNode) target).getElements()) {
                visitDeleteTarget(element);
            }
        } else if (target != null) {
            target.accept(this);
        }
    }

    private void record(String name, OccurrenceKind occurrence) {
        ScopeContext context = currentScope();
        if (name == null || context.declaredGlobals.contains(name)) {
            return;
        }
        EnumSet<OccurrenceKind> occurrences = context.priorOccurrences.get(name);
        if (occurrences == null) {
            occurrences = EnumSet.noneOf(OccurrenceKind.class);
            context.priorOccurrences.put(name, occurrences);
        }
        occurrences.add(occurrence);
    }

    private void reportGlobalConflict(String name, String reason, ASTNode node) {
        reporter.report(Diagnostics.globalDeclarationConflict(
                name,
                reason,
                node.getLine(),
                node.getColumn(),
                sourceFile));
    }

    private SymbolTable validateChildScope(
            ASTNode owner,
            SymbolTable attached,
            SymbolTable.ScopeType expectedType) {
        if (attached == null) {
            reporter.report(Diagnostics.scopeError(
                    owner.getNodeType() + " has no attached symbol-table scope",
                    owner.getLine(),
                    owner.getColumn(),
                    sourceFile));
            return currentScope().symbols;
        }
        if (attached.getScopeType() != expectedType) {
            reporter.report(Diagnostics.scopeError(
                    owner.getNodeType() + " has scope type "
                            + attached.getScopeType() + " instead of " + expectedType,
                    owner.getLine(),
                    owner.getColumn(),
                    sourceFile));
        }
        return attached;
    }

    private void validateAttachedScope(
            ASTNode owner,
            SymbolTable attached,
            SymbolTable.ScopeType expectedType) {
        if (owner.getScope() != attached || attached.getScopeType() != expectedType) {
            reporter.report(Diagnostics.scopeError(
                    "Program root is not attached to the expected global scope",
                    owner.getLine(),
                    owner.getColumn(),
                    sourceFile));
        }
    }

    private ScopeContext currentScope() {
        ScopeContext context = scopes.peek();
        if (context == null) {
            throw new IllegalStateException(
                    "Scope-rule checking requires an executable block context");
        }
        return context;
    }

    private static String normalizeSourceFile(String sourceFile) {
        return sourceFile == null || sourceFile.trim().isEmpty()
                ? "<unknown>"
                : sourceFile;
    }

    private enum OccurrenceKind {
        USE,
        STORE,
        DELETE
    }

    private static final class ScopeContext {
        private final ExecutableBlockKind kind;
        private final SymbolTable symbols;
        private final Set<String> parameterNames;
        private final Set<String> declaredGlobals = new LinkedHashSet<>();
        private final Map<String, EnumSet<OccurrenceKind>> priorOccurrences =
                new LinkedHashMap<>();

        private ScopeContext(
                ExecutableBlockKind kind,
                SymbolTable symbols,
                Set<String> parameterNames) {
            this.kind = kind;
            this.symbols = Objects.requireNonNull(symbols, "symbols");
            this.parameterNames = new LinkedHashSet<>(parameterNames);
        }
    }
}
