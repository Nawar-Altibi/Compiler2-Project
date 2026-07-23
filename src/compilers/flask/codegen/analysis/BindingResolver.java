package compilers.flask.codegen.analysis;

import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.ast.nodes.Statement;
import compilers.flask.ast.nodes.expressions.access.AttributeAccessNode;
import compilers.flask.ast.nodes.expressions.access.FunctionCallNode;
import compilers.flask.ast.nodes.expressions.access.SubscriptNode;
import compilers.flask.ast.nodes.expressions.atoms.DictNode;
import compilers.flask.ast.nodes.expressions.atoms.FStringNode;
import compilers.flask.ast.nodes.expressions.atoms.FStringPart;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.expressions.atoms.ListNode;
import compilers.flask.ast.nodes.expressions.atoms.LiteralNode;
import compilers.flask.ast.nodes.expressions.atoms.SetNode;
import compilers.flask.ast.nodes.expressions.atoms.TupleNode;
import compilers.flask.ast.nodes.expressions.operations.BinaryOpNode;
import compilers.flask.ast.nodes.expressions.operations.CompareNode;
import compilers.flask.ast.nodes.expressions.operations.UnaryOpNode;
import compilers.flask.ast.nodes.helpers.CallArgument;
import compilers.flask.ast.nodes.helpers.DecoratorNode;
import compilers.flask.ast.nodes.helpers.ExceptClause;
import compilers.flask.ast.nodes.helpers.Parameter;
import compilers.flask.ast.nodes.helpers.WithItem;
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
import compilers.flask.runtime.RuntimeSymbolManifest;
import compilers.flask.runtime.RuntimeSymbolSpec;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves every lexical name operation into NAME/FAST/GLOBAL/DEREF and
 * computes deterministic scope layouts for module, function, and class code.
 *
 * <p>This pass is deliberately read-only. It consumes scopes attached by
 * {@code SymbolTableBuilder}, but it does not mutate either the AST or symbol
 * tables.</p>
 */
public final class BindingResolver {

    public BindingAnalysisResult resolve(ProgramNode program) {
        Objects.requireNonNull(program, "program");
        SymbolTable root = program.getScope();
        if (root == null) {
            throw new IllegalStateException(
                    "Program has no attached scope; run SymbolTableBuilder before BindingResolver");
        }
        return resolve(program, root);
    }

    public BindingAnalysisResult resolve(ProgramNode program, SymbolTable rootScope) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(rootScope, "rootScope");
        return new Analysis(program, rootScope.getRootScope()).run();
    }

    private static final class Analysis {
        private final ProgramNode program;
        private final SymbolTable rootTable;
        private final List<Block> blocks = new ArrayList<>();
        private final List<DraftOccurrence> drafts = new ArrayList<>();
        private final IdentityHashMap<SymbolTable, Block> blockByTable = new IdentityHashMap<>();
        private final IdentityHashMap<ASTNode, EnumMap<NameOccurrence.Role, Integer>> nextOrdinals =
                new IdentityHashMap<>();

        private Analysis(ProgramNode program, SymbolTable rootTable) {
            this.program = program;
            this.rootTable = rootTable;
        }

        private BindingAnalysisResult run() {
            SymbolTable attached = program.getScope();
            if (attached != null && attached.getRootScope() != rootTable) {
                throw new IllegalArgumentException(
                        "Program scope and supplied root symbol table do not belong to the same tree");
            }

            Block root = createBlock(
                    ScopeLayout.ScopeKind.MODULE,
                    program,
                    rootTable,
                    null);
            collectStatements(program.getStatements(), root);
            normalizeLocalsAndLinkLexicalParents();
            resolveDraftsAndClosures();

            IdentityHashMap<Block, ScopeLayout> layoutsByBlock = buildLayouts();
            List<ScopeLayout> layouts = new ArrayList<>();
            for (Block block : blocks) {
                layouts.add(layoutsByBlock.get(block));
            }

            List<NameOccurrence> occurrences = buildOccurrences(layoutsByBlock);
            return new BindingAnalysisResult(
                    program,
                    layoutsByBlock.get(root),
                    layouts,
                    occurrences);
        }

        private Block createBlock(
                ScopeLayout.ScopeKind kind,
                ASTNode owner,
                SymbolTable table,
                Block structuralParent) {
            Objects.requireNonNull(table, "table");
            validateScopeKind(kind, table, owner);
            if (structuralParent != null && table.getParent() != structuralParent.table) {
                throw new IllegalStateException(
                        "Attached scope for " + owner.getNodeType()
                                + " does not have the defining scope as structural parent");
            }
            if (blockByTable.containsKey(table)) {
                throw new IllegalStateException(
                        "Symbol table is attached to multiple executable AST blocks: "
                                + table.getScopeName());
            }
            Block block = new Block(blocks.size(), kind, owner, table, structuralParent);
            blocks.add(block);
            blockByTable.put(table, block);
            return block;
        }

        private static void validateScopeKind(
                ScopeLayout.ScopeKind kind, SymbolTable table, ASTNode owner) {
            SymbolTable.ScopeType actual = table.getScopeType();
            boolean valid;
            switch (kind) {
                case MODULE:
                    valid = actual == SymbolTable.ScopeType.GLOBAL
                            || actual == SymbolTable.ScopeType.MODULE;
                    break;
                case FUNCTION:
                    valid = actual == SymbolTable.ScopeType.FUNCTION;
                    break;
                case CLASS:
                    valid = actual == SymbolTable.ScopeType.CLASS;
                    break;
                default:
                    valid = false;
            }
            if (!valid) {
                throw new IllegalStateException(
                        "AST block " + owner.getNodeType() + " expects " + kind
                                + " scope, got " + actual);
            }
        }

        private void normalizeLocalsAndLinkLexicalParents() {
            for (Block block : blocks) {
                if (block.kind != ScopeLayout.ScopeKind.MODULE) {
                    block.locals.removeAll(block.globals);
                }

                SymbolTable lexicalTable = block.table.getLexicalParent();
                if (lexicalTable != null) {
                    Block lexical = blockByTable.get(lexicalTable);
                    if (lexical == null) {
                        throw new IllegalStateException(
                                "Lexical parent has no executable layout: "
                                        + lexicalTable.getScopeName());
                    }
                    block.lexicalParent = lexical;
                }
            }
        }

        private void resolveDraftsAndClosures() {
            for (DraftOccurrence draft : drafts) {
                resolvePreliminary(draft);
                if (draft.preliminary == Preliminary.CAPTURE) {
                    promoteClosurePath(draft.block, draft.definingBlock, draft.name);
                }
            }
        }

        private void resolvePreliminary(DraftOccurrence draft) {
            Block block = draft.block;
            String name = draft.name;

            if (draft.role == NameOccurrence.Role.GLOBAL_DECLARATION) {
                if (block.kind == ScopeLayout.ScopeKind.MODULE) {
                    draft.preliminary = Preliminary.NAME;
                    draft.definingBlock = block;
                } else {
                    draft.preliminary = Preliminary.GLOBAL;
                    draft.definingBlock = rootBlock();
                }
                return;
            }

            if (block.kind != ScopeLayout.ScopeKind.MODULE && block.globals.contains(name)) {
                draft.preliminary = Preliminary.GLOBAL;
                draft.definingBlock = rootBlock();
                return;
            }

            if (block.kind == ScopeLayout.ScopeKind.MODULE) {
                draft.preliminary = Preliminary.NAME;
                draft.definingBlock = block;
                return;
            }

            if (block.kind == ScopeLayout.ScopeKind.FUNCTION && block.locals.contains(name)) {
                draft.preliminary = Preliminary.LOCAL;
                draft.definingBlock = block;
                return;
            }

            if (block.kind == ScopeLayout.ScopeKind.CLASS && block.locals.contains(name)) {
                draft.preliminary = Preliminary.NAME;
                draft.definingBlock = block;
                return;
            }

            // Stores/deletes have already contributed local bindings. Only a
            // load can reach this branch and capture an enclosing function.
            EnclosingResolution enclosing = findEnclosingFunctionBinding(block, name);
            if (enclosing.globalBarrier) {
                draft.preliminary = block.kind == ScopeLayout.ScopeKind.CLASS
                        ? Preliminary.NAME
                        : Preliminary.GLOBAL;
                draft.definingBlock = block.kind == ScopeLayout.ScopeKind.CLASS
                        ? block
                        : rootBlock();
            } else if (enclosing.definingFunction != null) {
                draft.preliminary = Preliminary.CAPTURE;
                draft.definingBlock = enclosing.definingFunction;
            } else {
                draft.preliminary = block.kind == ScopeLayout.ScopeKind.CLASS
                        ? Preliminary.NAME
                        : Preliminary.GLOBAL;
                draft.definingBlock = block.kind == ScopeLayout.ScopeKind.CLASS
                        ? block
                        : rootBlock();
            }
        }

        private EnclosingResolution findEnclosingFunctionBinding(Block block, String name) {
            Block current = block.lexicalParent;
            while (current != null) {
                if (current.kind == ScopeLayout.ScopeKind.FUNCTION) {
                    if (current.globals.contains(name)) {
                        return EnclosingResolution.globalBarrier();
                    }
                    if (current.locals.contains(name)) {
                        return EnclosingResolution.capture(current);
                    }
                }
                current = current.lexicalParent;
            }
            return EnclosingResolution.unresolved();
        }

        private void promoteClosurePath(Block consumer, Block definingFunction, String name) {
            if (definingFunction.kind != ScopeLayout.ScopeKind.FUNCTION
                    || !definingFunction.locals.contains(name)) {
                throw new IllegalStateException(
                        "Closure target is not a function local: " + name);
            }
            definingFunction.cellVars.add(name);

            Block current = consumer;
            while (current != definingFunction) {
                if (current == null || current.kind == ScopeLayout.ScopeKind.MODULE) {
                    throw new IllegalStateException(
                            "Closure creation path does not reach defining function for " + name);
                }
                current.freeVars.add(name);
                current = current.structuralParent;
            }
        }

        private IdentityHashMap<Block, ScopeLayout> buildLayouts() {
            IdentityHashMap<Block, ScopeLayout> result = new IdentityHashMap<>();
            for (Block block : blocks) {
                ScopeLayout structuralParent = result.get(block.structuralParent);
                ScopeLayout lexicalParent = result.get(block.lexicalParent);

                List<String> orderedCells = orderedSubset(block.locals, block.cellVars);
                ScopeLayout layout = new ScopeLayout(
                        block.ordinal,
                        block.kind,
                        block.owner,
                        block.table,
                        structuralParent,
                        lexicalParent,
                        new ArrayList<>(block.parameters),
                        new ArrayList<>(block.locals),
                        new ArrayList<>(block.globals),
                        orderedCells,
                        new ArrayList<>(block.freeVars),
                        0);
                result.put(block, layout);
            }
            return result;
        }

        private static List<String> orderedSubset(
                LinkedHashSet<String> order, LinkedHashSet<String> subset) {
            List<String> result = new ArrayList<>();
            for (String name : order) {
                if (subset.contains(name)) {
                    result.add(name);
                }
            }
            return result;
        }

        private List<NameOccurrence> buildOccurrences(
                IdentityHashMap<Block, ScopeLayout> layouts) {
            List<NameOccurrence> result = new ArrayList<>();
            ScopeLayout rootLayout = layouts.get(rootBlock());
            for (DraftOccurrence draft : drafts) {
                ScopeLayout useLayout = layouts.get(draft.block);
                ScopeLayout definingLayout = layouts.get(draft.definingBlock);
                BindingKind kind;
                int slot = -1;

                switch (draft.preliminary) {
                    case NAME:
                        kind = BindingKind.NAME;
                        break;
                    case GLOBAL:
                        kind = BindingKind.GLOBAL;
                        definingLayout = rootLayout;
                        break;
                    case LOCAL:
                        if (useLayout.isCellVar(draft.name)) {
                            kind = BindingKind.DEREF;
                            definingLayout = useLayout;
                            slot = useLayout.getDerefSlot(draft.name);
                        } else {
                            kind = BindingKind.FAST;
                            definingLayout = useLayout;
                            slot = useLayout.getFastSlot(draft.name);
                        }
                        break;
                    case CAPTURE:
                        kind = BindingKind.DEREF;
                        slot = useLayout.getDerefSlot(draft.name);
                        break;
                    default:
                        throw new IllegalStateException("Occurrence was not resolved: " + draft.name);
                }

                ResolvedName resolved = new ResolvedName(
                        draft.name,
                        kind,
                        useLayout,
                        definingLayout,
                        slot);
                RuntimeSymbolSpec runtimeSymbol = draft.runtimeSymbol;
                if (runtimeSymbol == null
                        && draft.role == NameOccurrence.Role.LOAD
                        && (kind == BindingKind.NAME || kind == BindingKind.GLOBAL)) {
                    runtimeSymbol = RuntimeSymbolManifest.lookup(
                            RuntimeSymbolManifest.BUILTINS_MODULE, draft.name);
                }
                result.add(new NameOccurrence(
                        draft.key,
                        draft.name,
                        draft.span,
                        useLayout,
                        resolved,
                        runtimeSymbol));
            }
            return result;
        }

        private Block rootBlock() {
            return blocks.get(0);
        }

        // -------------------------------------------------------------
        // AST collection
        // -------------------------------------------------------------

        private void collectStatements(List<Statement> statements, Block block) {
            for (Statement statement : statements) {
                collectStatement(statement, block);
            }
        }

        private void collectStatement(Statement statement, Block block) {
            if (statement instanceof FunctionDefNode) {
                collectFunctionDefinition((FunctionDefNode) statement, block);
            } else if (statement instanceof ClassDefNode) {
                collectClassDefinition((ClassDefNode) statement, block);
            } else if (statement instanceof AssignmentNode) {
                collectAssignment((AssignmentNode) statement, block);
            } else if (statement instanceof ExpressionStatementNode) {
                collectExpression(((ExpressionStatementNode) statement).getExpression(), block);
            } else if (statement instanceof ReturnNode) {
                collectNullableExpression(((ReturnNode) statement).getValue(), block);
            } else if (statement instanceof DelNode) {
                for (Expression target : ((DelNode) statement).getTargets()) {
                    collectDeleteTarget(target, block);
                }
            } else if (statement instanceof AssertNode) {
                AssertNode assertNode = (AssertNode) statement;
                collectExpression(assertNode.getTest(), block);
                collectNullableExpression(assertNode.getMessage(), block);
            } else if (statement instanceof GlobalNode) {
                collectGlobal((GlobalNode) statement, block);
            } else if (statement instanceof RaiseNode) {
                RaiseNode raise = (RaiseNode) statement;
                collectNullableExpression(raise.getException(), block);
                collectNullableExpression(raise.getCause(), block);
            } else if (statement instanceof ImportNode) {
                ImportNode importNode = (ImportNode) statement;
                addBindingOccurrence(
                        importNode,
                        NameOccurrence.Role.IMPORT_BINDING,
                        importNode.getEffectiveName(),
                        block,
                        importNode.getSourceSpan());
            } else if (statement instanceof FromImportNode) {
                collectFromImport((FromImportNode) statement, block);
            } else if (statement instanceof IfStatementNode) {
                collectIf((IfStatementNode) statement, block);
            } else if (statement instanceof ForStatementNode) {
                collectFor((ForStatementNode) statement, block);
            } else if (statement instanceof WhileStatementNode) {
                collectWhile((WhileStatementNode) statement, block);
            } else if (statement instanceof WithStatementNode) {
                collectWith((WithStatementNode) statement, block);
            } else if (statement instanceof TryStatementNode) {
                collectTry((TryStatementNode) statement, block);
            } else if (statement instanceof PassNode
                    || statement instanceof BreakNode
                    || statement instanceof ContinueNode) {
                // No lexical names.
            } else {
                throw new IllegalArgumentException(
                        "Unsupported statement in binding analysis: "
                                + statement.getClass().getName());
            }
        }

        private void collectAssignment(AssignmentNode node, Block block) {
            if (node.isAugmented()) {
                collectAugmentedTargetRead(node.getTarget(), block);
                collectExpression(node.getValue(), block);
                collectStoreWithoutAddress(node.getTarget(), block, NameOccurrence.Role.STORE);
            } else {
                collectExpression(node.getValue(), block);
                collectStoreTarget(node.getTarget(), block, NameOccurrence.Role.STORE);
            }
        }

        private void collectAugmentedTargetRead(Expression target, Block block) {
            if (target instanceof IdentifierNode) {
                addIdentifierOccurrence(
                        (IdentifierNode) target, NameOccurrence.Role.LOAD, block);
            } else if (target instanceof AttributeAccessNode) {
                collectExpression(((AttributeAccessNode) target).getObject(), block);
            } else if (target instanceof SubscriptNode) {
                SubscriptNode subscript = (SubscriptNode) target;
                collectExpression(subscript.getObject(), block);
                collectExpression(subscript.getIndex(), block);
            } else {
                throw new IllegalArgumentException(
                        "Invalid augmented-assignment target: " + target.getNodeType());
            }
        }

        private void collectStoreTarget(
                Expression target, Block block, NameOccurrence.Role role) {
            if (target instanceof IdentifierNode) {
                addIdentifierBinding((IdentifierNode) target, role, block);
            } else if (target instanceof AttributeAccessNode) {
                collectExpression(((AttributeAccessNode) target).getObject(), block);
            } else if (target instanceof SubscriptNode) {
                SubscriptNode subscript = (SubscriptNode) target;
                collectExpression(subscript.getObject(), block);
                collectExpression(subscript.getIndex(), block);
            } else if (target instanceof TupleNode) {
                for (Expression element : ((TupleNode) target).getElements()) {
                    collectStoreTarget(element, block, role);
                }
            } else if (target instanceof ListNode) {
                for (Expression element : ((ListNode) target).getElements()) {
                    collectStoreTarget(element, block, role);
                }
            } else {
                throw new IllegalArgumentException(
                        "Invalid store target in binding analysis: " + target.getNodeType());
            }
        }

        private void collectStoreWithoutAddress(
                Expression target, Block block, NameOccurrence.Role role) {
            if (target instanceof IdentifierNode) {
                addIdentifierBinding((IdentifierNode) target, role, block);
            } else if (!(target instanceof AttributeAccessNode)
                    && !(target instanceof SubscriptNode)) {
                throw new IllegalArgumentException(
                        "Invalid augmented store target: " + target.getNodeType());
            }
        }

        private void collectDeleteTarget(Expression target, Block block) {
            if (target instanceof IdentifierNode) {
                addIdentifierBinding(
                        (IdentifierNode) target, NameOccurrence.Role.DELETE, block);
            } else if (target instanceof AttributeAccessNode) {
                collectExpression(((AttributeAccessNode) target).getObject(), block);
            } else if (target instanceof SubscriptNode) {
                SubscriptNode subscript = (SubscriptNode) target;
                collectExpression(subscript.getObject(), block);
                collectExpression(subscript.getIndex(), block);
            } else if (target instanceof TupleNode) {
                for (Expression element : ((TupleNode) target).getElements()) {
                    collectDeleteTarget(element, block);
                }
            } else if (target instanceof ListNode) {
                for (Expression element : ((ListNode) target).getElements()) {
                    collectDeleteTarget(element, block);
                }
            } else {
                throw new IllegalArgumentException(
                        "Invalid delete target in binding analysis: " + target.getNodeType());
            }
        }

        private void collectGlobal(GlobalNode node, Block block) {
            for (String name : node.getNames()) {
                block.globals.add(name);
                addOccurrence(
                        node,
                        NameOccurrence.Role.GLOBAL_DECLARATION,
                        name,
                        block,
                        node.getSourceSpan());
            }
        }

        private void collectFromImport(FromImportNode node, Block block) {
            if (node.isImportAll()) {
                return;
            }
            for (FromImportNode.ImportItem item : node.getItems()) {
                RuntimeSymbolSpec runtimeSymbol = RuntimeSymbolManifest.lookup(
                        node.getModuleName(), item.getName());
                addBindingOccurrence(
                        node,
                        NameOccurrence.Role.IMPORT_BINDING,
                        item.getEffectiveName(),
                        block,
                        locatedOrOwner(
                                item.hasAlias() ? item.getAliasSpan() : item.getNameSpan(),
                                node),
                        runtimeSymbol);
            }
        }

        private void collectIf(IfStatementNode node, Block block) {
            collectExpression(node.getCondition(), block);
            collectStatements(node.getThenBody(), block);
            for (IfStatementNode.ElifClause clause : node.getElifClauses()) {
                collectExpression(clause.getCondition(), block);
                collectStatements(clause.getBody(), block);
            }
            if (node.getElseBody() != null) {
                collectStatements(node.getElseBody(), block);
            }
        }

        private void collectFor(ForStatementNode node, Block block) {
            collectExpression(node.getIterable(), block);
            collectStoreTarget(node.getTarget(), block, NameOccurrence.Role.STORE);
            collectStatements(node.getBody(), block);
            if (node.getElseBody() != null) {
                collectStatements(node.getElseBody(), block);
            }
        }

        private void collectWhile(WhileStatementNode node, Block block) {
            collectExpression(node.getCondition(), block);
            collectStatements(node.getBody(), block);
            if (node.getElseBody() != null) {
                collectStatements(node.getElseBody(), block);
            }
        }

        private void collectWith(WithStatementNode node, Block block) {
            for (WithItem item : node.getItems()) {
                collectExpression(item.getContextExpr(), block);
                if (item.getAsName() != null) {
                    collectStoreTarget(
                            item.getAsName(), block, NameOccurrence.Role.STORE);
                }
            }
            collectStatements(node.getBody(), block);
        }

        private void collectTry(TryStatementNode node, Block block) {
            collectStatements(node.getTryBody(), block);
            for (ExceptClause clause : node.getExceptClauses()) {
                collectNullableExpression(clause.getExceptionType(), block);
                if (clause.hasAsName()) {
                    addBindingOccurrence(
                            node,
                            NameOccurrence.Role.EXCEPT_ALIAS,
                            clause.getAsName(),
                            block,
                            locatedOrOwner(clause.getAliasSpan(), node));
                }
                collectStatements(clause.getBody(), block);
            }
            if (node.getElseBody() != null) {
                collectStatements(node.getElseBody(), block);
            }
            if (node.getFinallyBody() != null) {
                collectStatements(node.getFinallyBody(), block);
            }
        }

        private void collectFunctionDefinition(FunctionDefNode node, Block definingBlock) {
            for (DecoratorNode decorator : node.getDecorators()) {
                collectExpression(decorator.getExpression(), definingBlock);
            }
            for (Parameter parameter : node.getParameters()) {
                collectNullableExpression(parameter.getDefaultValue(), definingBlock);
            }
            for (Parameter parameter : node.getParameters()) {
                collectNullableExpression(parameter.getTypeHint(), definingBlock);
            }
            collectNullableExpression(node.getReturnType(), definingBlock);

            addBindingOccurrence(
                    node,
                    NameOccurrence.Role.FUNCTION_DEFINITION,
                    node.getName(),
                    definingBlock,
                    locatedOrOwner(node.getNameSpan(), node));

            SymbolTable functionScope = requireAttachedScope(
                    node, SymbolTable.ScopeType.FUNCTION);
            Block functionBlock = createBlock(
                    ScopeLayout.ScopeKind.FUNCTION,
                    node,
                    functionScope,
                    definingBlock);
            for (Parameter parameter : node.getParameters()) {
                functionBlock.parameters.add(parameter.getName());
                addBindingOccurrence(
                        node,
                        NameOccurrence.Role.PARAMETER,
                        parameter.getName(),
                        functionBlock,
                        locatedOrOwner(parameter.getNameSpan(), node));
            }
            collectStatements(node.getBody(), functionBlock);
        }

        private void collectClassDefinition(ClassDefNode node, Block definingBlock) {
            for (DecoratorNode decorator : node.getDecorators()) {
                collectExpression(decorator.getExpression(), definingBlock);
            }
            for (Expression base : node.getBases()) {
                collectExpression(base, definingBlock);
            }

            addBindingOccurrence(
                    node,
                    NameOccurrence.Role.CLASS_DEFINITION,
                    node.getName(),
                    definingBlock,
                    locatedOrOwner(node.getNameSpan(), node));

            SymbolTable classScope = requireAttachedScope(
                    node, SymbolTable.ScopeType.CLASS);
            Block classBlock = createBlock(
                    ScopeLayout.ScopeKind.CLASS,
                    node,
                    classScope,
                    definingBlock);
            collectStatements(node.getBody(), classBlock);
        }

        private static SymbolTable requireAttachedScope(
                ASTNode node, SymbolTable.ScopeType expected) {
            SymbolTable scope = node.getScope();
            if (scope == null) {
                throw new IllegalStateException(
                        node.getNodeType()
                                + " has no attached scope; run SymbolTableBuilder first");
            }
            if (scope.getScopeType() != expected) {
                throw new IllegalStateException(
                        node.getNodeType() + " has " + scope.getScopeType()
                                + " scope, expected " + expected);
            }
            return scope;
        }

        private void collectNullableExpression(Expression expression, Block block) {
            if (expression != null) {
                collectExpression(expression, block);
            }
        }

        private void collectExpression(Expression expression, Block block) {
            if (expression instanceof IdentifierNode) {
                addIdentifierOccurrence(
                        (IdentifierNode) expression, NameOccurrence.Role.LOAD, block);
            } else if (expression instanceof LiteralNode) {
                // No lexical names.
            } else if (expression instanceof BinaryOpNode) {
                BinaryOpNode binary = (BinaryOpNode) expression;
                collectExpression(binary.getLeft(), block);
                collectExpression(binary.getRight(), block);
            } else if (expression instanceof CompareNode) {
                CompareNode compare = (CompareNode) expression;
                collectExpression(compare.getLeft(), block);
                for (Expression comparator : compare.getComparators()) {
                    collectExpression(comparator, block);
                }
            } else if (expression instanceof UnaryOpNode) {
                collectExpression(((UnaryOpNode) expression).getOperand(), block);
            } else if (expression instanceof ListNode) {
                for (Expression element : ((ListNode) expression).getElements()) {
                    collectExpression(element, block);
                }
            } else if (expression instanceof TupleNode) {
                for (Expression element : ((TupleNode) expression).getElements()) {
                    collectExpression(element, block);
                }
            } else if (expression instanceof SetNode) {
                for (Expression element : ((SetNode) expression).getElements()) {
                    collectExpression(element, block);
                }
            } else if (expression instanceof DictNode) {
                for (DictNode.DictItem item : ((DictNode) expression).getItems()) {
                    collectExpression(item.getKey(), block);
                    collectExpression(item.getValue(), block);
                }
            } else if (expression instanceof FStringNode) {
                for (FStringPart part : ((FStringNode) expression).getParts()) {
                    if (part instanceof FStringPart.ExpressionPart) {
                        collectNullableExpression(
                                ((FStringPart.ExpressionPart) part).getExpression(), block);
                    }
                }
            } else if (expression instanceof AttributeAccessNode) {
                collectExpression(((AttributeAccessNode) expression).getObject(), block);
            } else if (expression instanceof SubscriptNode) {
                SubscriptNode subscript = (SubscriptNode) expression;
                collectExpression(subscript.getObject(), block);
                collectExpression(subscript.getIndex(), block);
            } else if (expression instanceof FunctionCallNode) {
                FunctionCallNode call = (FunctionCallNode) expression;
                collectExpression(call.getFunction(), block);
                for (CallArgument argument : call.getArguments()) {
                    collectExpression(argument.getValue(), block);
                }
            } else {
                throw new IllegalArgumentException(
                        "Unsupported expression in binding analysis: "
                                + expression.getClass().getName());
            }
        }

        private void addIdentifierOccurrence(
                IdentifierNode identifier, NameOccurrence.Role role, Block block) {
            addOccurrence(
                    identifier,
                    role,
                    identifier.getName(),
                    block,
                    identifier.getSourceSpan());
        }

        private void addIdentifierBinding(
                IdentifierNode identifier, NameOccurrence.Role role, Block block) {
            block.locals.add(identifier.getName());
            addIdentifierOccurrence(identifier, role, block);
        }

        private void addBindingOccurrence(
                ASTNode owner,
                NameOccurrence.Role role,
                String name,
                Block block,
                SourceSpan span) {
            addBindingOccurrence(owner, role, name, block, span, null);
        }

        private void addBindingOccurrence(
                ASTNode owner,
                NameOccurrence.Role role,
                String name,
                Block block,
                SourceSpan span,
                RuntimeSymbolSpec runtimeSymbol) {
            block.locals.add(name);
            addOccurrence(owner, role, name, block, span, runtimeSymbol);
        }

        private void addOccurrence(
                ASTNode owner,
                NameOccurrence.Role role,
                String name,
                Block block,
                SourceSpan span) {
            addOccurrence(owner, role, name, block, span, null);
        }

        private void addOccurrence(
                ASTNode owner,
                NameOccurrence.Role role,
                String name,
                Block block,
                SourceSpan span,
                RuntimeSymbolSpec runtimeSymbol) {
            EnumMap<NameOccurrence.Role, Integer> roleOrdinals = nextOrdinals.get(owner);
            if (roleOrdinals == null) {
                roleOrdinals = new EnumMap<>(NameOccurrence.Role.class);
                nextOrdinals.put(owner, roleOrdinals);
            }
            Integer next = roleOrdinals.get(role);
            int ordinal = next == null ? 0 : next;
            roleOrdinals.put(role, ordinal + 1);
            drafts.add(new DraftOccurrence(
                    new NameOccurrence.Key(owner, role, ordinal),
                    name,
                    span == null ? SourceSpan.UNKNOWN : span,
                    role,
                    block,
                    runtimeSymbol));
        }

        private static SourceSpan locatedOrOwner(SourceSpan helperSpan, ASTNode owner) {
            return helperSpan != null && helperSpan.isKnown()
                    ? helperSpan
                    : owner.getSourceSpan();
        }
    }

    private enum Preliminary {
        UNRESOLVED,
        NAME,
        LOCAL,
        GLOBAL,
        CAPTURE
    }

    private static final class DraftOccurrence {
        private final NameOccurrence.Key key;
        private final String name;
        private final SourceSpan span;
        private final NameOccurrence.Role role;
        private final Block block;
        private final RuntimeSymbolSpec runtimeSymbol;
        private Preliminary preliminary = Preliminary.UNRESOLVED;
        private Block definingBlock;

        private DraftOccurrence(
                NameOccurrence.Key key,
                String name,
                SourceSpan span,
                NameOccurrence.Role role,
                Block block,
                RuntimeSymbolSpec runtimeSymbol) {
            this.key = key;
            this.name = name;
            this.span = span;
            this.role = role;
            this.block = block;
            this.runtimeSymbol = runtimeSymbol;
        }
    }

    private static final class Block {
        private final int ordinal;
        private final ScopeLayout.ScopeKind kind;
        private final ASTNode owner;
        private final SymbolTable table;
        private final Block structuralParent;
        private Block lexicalParent;
        private final LinkedHashSet<String> parameters = new LinkedHashSet<>();
        private final LinkedHashSet<String> locals = new LinkedHashSet<>();
        private final LinkedHashSet<String> globals = new LinkedHashSet<>();
        private final LinkedHashSet<String> cellVars = new LinkedHashSet<>();
        private final LinkedHashSet<String> freeVars = new LinkedHashSet<>();

        private Block(
                int ordinal,
                ScopeLayout.ScopeKind kind,
                ASTNode owner,
                SymbolTable table,
                Block structuralParent) {
            this.ordinal = ordinal;
            this.kind = kind;
            this.owner = owner;
            this.table = table;
            this.structuralParent = structuralParent;
        }
    }

    private static final class EnclosingResolution {
        private final Block definingFunction;
        private final boolean globalBarrier;

        private EnclosingResolution(Block definingFunction, boolean globalBarrier) {
            this.definingFunction = definingFunction;
            this.globalBarrier = globalBarrier;
        }

        private static EnclosingResolution capture(Block function) {
            return new EnclosingResolution(function, false);
        }

        private static EnclosingResolution globalBarrier() {
            return new EnclosingResolution(null, true);
        }

        private static EnclosingResolution unresolved() {
            return new EnclosingResolution(null, false);
        }
    }
}
