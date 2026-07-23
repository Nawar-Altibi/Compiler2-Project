package compilers.flask.semantic.validation;

import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.Visitor.ASTBaseVisitor;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.SourceSpan;
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
import compilers.flask.ast.nodes.statements.compound.WhileStatementNode;
import compilers.flask.ast.nodes.statements.compound.WithStatementNode;
import compilers.flask.ast.nodes.statements.imports.FromImportNode;
import compilers.flask.ast.nodes.statements.simple.AssignmentNode;
import compilers.flask.ast.nodes.statements.simple.BreakNode;
import compilers.flask.ast.nodes.statements.simple.ContinueNode;
import compilers.flask.ast.nodes.statements.simple.DelNode;
import compilers.flask.ast.nodes.statements.simple.ReturnNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validates source-level AST invariants before the symbol-table pass sees the
 * tree.  It deliberately owns no binding logic.
 */
public final class AstStructuralValidator extends ASTBaseVisitor<Void> {
    private final DiagnosticReporter reporter;
    private final String sourceFile;
    private final Deque<BlockContext> blocks = new ArrayDeque<>();
    private boolean validating;

    public AstStructuralValidator(DiagnosticReporter reporter, String sourceFile) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.sourceFile = normalizeSourceFile(sourceFile);
    }

    /** Runs one isolated validation pass and returns whether it added no errors. */
    public boolean validate(ProgramNode program) {
        Objects.requireNonNull(program, "program");
        if (validating) {
            throw new IllegalStateException("AstStructuralValidator is already running");
        }

        int errorsBefore = reporter.errors().size();
        validating = true;
        blocks.clear();
        try {
            program.accept(this);
        } finally {
            blocks.clear();
            validating = false;
        }
        return reporter.errors().size() == errorsBefore;
    }

    public DiagnosticReporter getReporter() {
        return reporter;
    }

    @Override
    public Void visitProgram(ProgramNode node) {
        return inBlock(ExecutableBlockKind.MODULE, node.getStatements());
    }

    @Override
    public Void visitReturn(ReturnNode node) {
        if (currentBlock().kind != ExecutableBlockKind.FUNCTION) {
            reportControlFlow("return", node);
        }
        if (node.hasValue()) {
            node.getValue().accept(this);
        }
        return null;
    }

    @Override
    public Void visitBreak(BreakNode node) {
        if (currentBlock().loopDepth == 0) {
            reportControlFlow("break", node);
        }
        return null;
    }

    @Override
    public Void visitContinue(ContinueNode node) {
        if (currentBlock().loopDepth == 0) {
            reportControlFlow("continue", node);
        }
        return null;
    }

    @Override
    public Void visitFunctionDef(FunctionDefNode node) {
        for (DecoratorNode decorator : node.getDecorators()) {
            decorator.getExpression().accept(this);
        }

        boolean sawDefault = false;
        for (Parameter parameter : node.getParameters()) {
            if (parameter.hasDefault()) {
                sawDefault = true;
                parameter.getDefaultValue().accept(this);
            } else if (sawDefault) {
                SourceSpan span = bestSpan(parameter.getNameSpan(), parameter.getSpan());
                reporter.report(Diagnostics.invalidParameterOrder(
                        node.getName(),
                        parameter.getName(),
                        line(span, node),
                        column(span, node),
                        sourceFile));
            }

            if (parameter.hasTypeHint()) {
                parameter.getTypeHint().accept(this);
            }
        }
        if (node.hasReturnType()) {
            node.getReturnType().accept(this);
        }

        return inBlock(ExecutableBlockKind.FUNCTION, node.getBody());
    }

    @Override
    public Void visitClassDef(ClassDefNode node) {
        for (DecoratorNode decorator : node.getDecorators()) {
            decorator.getExpression().accept(this);
        }
        for (Expression base : node.getBases()) {
            base.accept(this);
        }
        return inBlock(ExecutableBlockKind.CLASS, node.getBody());
    }

    @Override
    public Void visitWhileStatement(WhileStatementNode node) {
        node.getCondition().accept(this);
        visitLoopBody(node.getBody());
        if (node.hasElse()) {
            visitStatements(node.getElseBody());
        }
        return null;
    }

    @Override
    public Void visitForStatement(ForStatementNode node) {
        validateTarget(node.getTarget(), TargetUse.STORE, "for-loop");
        node.getIterable().accept(this);
        visitLoopBody(node.getBody());
        if (node.hasElse()) {
            visitStatements(node.getElseBody());
        }
        return null;
    }

    @Override
    public Void visitAssignment(AssignmentNode node) {
        TargetUse use = node.isAugmented() ? TargetUse.AUGMENTED : TargetUse.STORE;
        validateTarget(
                node.getTarget(),
                use,
                node.isAugmented() ? "augmented assignment" : "assignment");
        node.getValue().accept(this);
        return null;
    }

    @Override
    public Void visitDel(DelNode node) {
        for (Expression target : node.getTargets()) {
            validateTarget(target, TargetUse.DELETE, "delete");
        }
        return null;
    }

    @Override
    public Void visitWithStatement(WithStatementNode node) {
        for (WithItem item : node.getItems()) {
            item.getContextExpr().accept(this);
            if (item.hasAsName()) {
                validateTarget(item.getAsName(), TargetUse.STORE, "with-as");
            }
        }
        visitStatements(node.getBody());
        return null;
    }

    @Override
    public Void visitTryStatement(TryStatementNode node) {
        visitStatements(node.getTryBody());

        List<ExceptClause> clauses = node.getExceptClauses();
        for (int index = 0; index < clauses.size(); index++) {
            ExceptClause clause = clauses.get(index);
            if (clause.isBareExcept() && index != clauses.size() - 1) {
                SourceSpan span = clause.getSpan();
                reporter.report(Diagnostics.invalidExceptionHandlerOrder(
                        line(span, node), column(span, node), sourceFile));
            }
            if (!clause.isBareExcept()) {
                clause.getExceptionType().accept(this);
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

    @Override
    public Void visitFromImport(FromImportNode node) {
        if (node.isImportAll() && currentBlock().kind != ExecutableBlockKind.MODULE) {
            reporter.report(Diagnostics.invalidImportScope(
                    currentBlock().kind.displayName(),
                    node.getLine(),
                    node.getColumn(),
                    sourceFile));
        }
        return null;
    }

    @Override
    public Void visitFunctionCall(FunctionCallNode node) {
        node.getFunction().accept(this);

        boolean sawKeyword = false;
        Set<String> keywordNames = new HashSet<>();
        for (CallArgument argument : node.getArguments()) {
            SourceSpan span = argument.getSpan();
            if (argument.isPositional()) {
                if (sawKeyword) {
                    reporter.report(Diagnostics.invalidCallArguments(
                            "A positional argument cannot follow a keyword argument",
                            line(span, node),
                            column(span, node),
                            sourceFile));
                }
            } else {
                sawKeyword = true;
                if (!keywordNames.add(argument.getKeywordName())) {
                    reporter.report(Diagnostics.invalidCallArguments(
                            "Duplicate keyword argument '"
                                    + argument.getKeywordName() + "'",
                            line(span, node),
                            column(span, node),
                            sourceFile));
                }
            }
            argument.getValue().accept(this);
        }
        return null;
    }

    private Void inBlock(ExecutableBlockKind kind, List<Statement> statements) {
        blocks.push(new BlockContext(kind));
        try {
            visitStatements(statements);
        } finally {
            blocks.pop();
        }
        return null;
    }

    private void visitLoopBody(List<Statement> statements) {
        BlockContext block = currentBlock();
        block.loopDepth++;
        try {
            visitStatements(statements);
        } finally {
            block.loopDepth--;
        }
    }

    private void visitStatements(List<Statement> statements) {
        for (Statement statement : statements) {
            if (statement == null) {
                reporter.report(Diagnostics.invalidAstStructure(
                        "Executable block contains a null statement",
                        0,
                        0,
                        sourceFile));
            } else {
                statement.accept(this);
            }
        }
    }

    private void validateTarget(Expression target, TargetUse use, String operation) {
        if (target == null) {
            reporter.report(Diagnostics.invalidAstStructure(
                    "A " + operation + " statement has no target",
                    0,
                    0,
                    sourceFile));
            return;
        }

        if (target instanceof IdentifierNode) {
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

        if (use != TargetUse.AUGMENTED && target instanceof TupleNode) {
            for (Expression element : ((TupleNode) target).getElements()) {
                validateTarget(element, use, operation);
            }
            return;
        }
        if (use != TargetUse.AUGMENTED && target instanceof ListNode) {
            for (Expression element : ((ListNode) target).getElements()) {
                validateTarget(element, use, operation);
            }
            return;
        }

        reporter.report(Diagnostics.invalidAssignmentTarget(
                operation,
                target.getNodeType(),
                target.getLine(),
                target.getColumn(),
                sourceFile));
        target.accept(this);
    }

    private void reportControlFlow(String statement, ASTNode node) {
        reporter.report(Diagnostics.invalidControlFlow(
                statement,
                currentBlock().kind.displayName(),
                node.getLine(),
                node.getColumn(),
                sourceFile));
    }

    private BlockContext currentBlock() {
        BlockContext context = blocks.peek();
        if (context == null) {
            throw new IllegalStateException(
                    "Structural validation requires an executable block context");
        }
        return context;
    }

    private static SourceSpan bestSpan(SourceSpan preferred, SourceSpan fallback) {
        if (preferred != null && preferred.isKnown()) {
            return preferred;
        }
        return fallback == null ? SourceSpan.UNKNOWN : fallback;
    }

    private static int line(SourceSpan span, ASTNode fallback) {
        return span != null && span.isKnown() ? span.getStartLine() : fallback.getLine();
    }

    private static int column(SourceSpan span, ASTNode fallback) {
        return span != null && span.isKnown() ? span.getStartColumn() : fallback.getColumn();
    }

    private static String normalizeSourceFile(String sourceFile) {
        return sourceFile == null || sourceFile.trim().isEmpty()
                ? "<unknown>"
                : sourceFile;
    }

    private enum TargetUse {
        STORE,
        AUGMENTED,
        DELETE
    }

    private static final class BlockContext {
        private final ExecutableBlockKind kind;
        private int loopDepth;

        private BlockContext(ExecutableBlockKind kind) {
            this.kind = kind;
        }
    }
}
