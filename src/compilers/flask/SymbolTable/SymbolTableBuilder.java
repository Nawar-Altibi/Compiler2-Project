package compilers.flask.SymbolTable;

import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.Visitor.ASTBaseVisitor;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.Statement;
import compilers.flask.ast.nodes.expressions.access.AttributeAccessNode;
import compilers.flask.ast.nodes.expressions.access.FunctionCallNode;
import compilers.flask.ast.nodes.expressions.access.SubscriptNode;
import compilers.flask.ast.nodes.expressions.atoms.DictNode;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.expressions.atoms.ListNode;
import compilers.flask.ast.nodes.expressions.atoms.LiteralNode;
import compilers.flask.ast.nodes.expressions.atoms.SetNode;
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
import compilers.flask.ast.nodes.statements.compound.IfStatementNode;
import compilers.flask.ast.nodes.statements.compound.TryStatementNode;
import compilers.flask.ast.nodes.statements.compound.WhileStatementNode;
import compilers.flask.ast.nodes.statements.compound.WithStatementNode;
import compilers.flask.ast.nodes.statements.imports.FromImportNode;
import compilers.flask.ast.nodes.statements.imports.ImportNode;
import compilers.flask.ast.nodes.statements.simple.AssignmentNode;
import compilers.flask.ast.nodes.statements.simple.GlobalNode;
import compilers.flask.semantic.FlaskFrameworkSymbols;
import compilers.flask.semantic.PythonBuiltins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pass 1 of semantic processing: builds Python scopes and declarations.
 *
 * <p>This remains an {@link ASTBaseVisitor}, so the existing Main integration
 * ({@code ast.accept(builder)}) is unchanged.  Definitions of functions and
 * classes are predeclared per code block before expressions are visited.  It
 * gives mutual recursion and references from an earlier function to a later
 * definition the expected static-symbol behaviour. Use the visitors in
 * {@code compilers.flask.semantic} for all use/type/call checks.</p>
 */
public class SymbolTableBuilder extends ASTBaseVisitor<Void> {

    private static final String DEFINITION_NODE = "definition_node";
    public static final String FIRST_BINDING_NODE = "first_binding_node";

    private SymbolTable currentScope;
    private final DiagnosticReporter reporter;
    private final String sourceFile;
    private final List<SymbolTable> allScopes;
    private final Set<ASTNode> duplicateDefinitionNodes;

    public SymbolTableBuilder() {
        this(new DiagnosticReporter(), "<unknown>");
    }

    public SymbolTableBuilder(DiagnosticReporter reporter, String sourceFile) {
        this.currentScope = new SymbolTable();
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.sourceFile = sourceFile == null || sourceFile.trim().isEmpty()
                ? "<unknown>"
                : sourceFile;
        this.allScopes = new ArrayList<>();
        this.allScopes.add(currentScope);
        this.duplicateDefinitionNodes = Collections.newSetFromMap(new IdentityHashMap<ASTNode, Boolean>());

        initializeImplicitModuleNames();
        initializeBuiltins();
    }

    private void initializeImplicitModuleNames() {
        initializeImplicitModuleName("__name__");
        initializeImplicitModuleName("__package__");
    }

    private void initializeImplicitModuleName(String name) {
        SymbolEntry entry = currentScope.insert(name, SymbolEntry.SymbolKind.VARIABLE);
        if (entry != null) {
            entry.setType(SymbolType.STRING);
            entry.setAttribute("implicit", true);
        }
    }

    private void initializeBuiltins() {
        for (Map.Entry<String, PythonBuiltins.SymbolInfo> builtin
                : PythonBuiltins.symbols().entrySet()) {
            PythonBuiltins.SymbolInfo info = builtin.getValue();
            SymbolEntry entry = currentScope.insertBuiltin(builtin.getKey(), info.getKind());
            entry.setType(info.getType());
        }
    }

    // ========================================
    // Public result API (kept compatible)
    // ========================================

    public SymbolTable getSymbolTable() {
        return currentScope.getRootScope();
    }

    public List<SymbolTable> getAllScopes() {
        return new ArrayList<>(allScopes);
    }

    public DiagnosticReporter getReporter() {
        return reporter;
    }

    // ========================================
    // Program and declarations
    // ========================================

    @Override
    public Void visitProgram(ProgramNode node) {
        currentScope = currentScope.getRootScope();
        node.setScope(currentScope);
        prepareBlock(node.getStatements());
        visitStatements(node.getStatements());
        return null;
    }

    @Override
    public Void visitGlobal(GlobalNode node) {
        for (String name : node.getNames()) {
            currentScope.declareGlobal(name);
        }
        return null;
    }

    @Override
    public Void visitImport(ImportNode node) {
        String moduleName = node.getModuleName();
        String bindingName = node.getEffectiveName();

        SymbolEntry entry = bindName(bindingName,
                                     SymbolEntry.SymbolKind.MODULE,
                                     SymbolType.MODULE,
                                     node);
        entry.setAttribute("original_module", moduleName);
        entry.setAttribute("imported_module", moduleName);
        return null;
    }

    @Override
    public Void visitFromImport(FromImportNode node) {
        if (node.isImportAll()) {
            currentScope.markWildcardImport();
            return null;
        }

        for (FromImportNode.ImportItem item : node.getItems()) {
            String originalName = item.getName();
            PythonBuiltins.SymbolInfo known = FlaskFrameworkSymbols.lookup(
                    node.getModuleName(), originalName);
            SymbolEntry.SymbolKind kind = known == null
                    ? SymbolEntry.SymbolKind.VARIABLE
                    : known.getKind();
            SymbolType type = known == null ? SymbolType.UNKNOWN : known.getType();

            SymbolEntry entry = bindName(item.getEffectiveName(), kind, type, node);
            entry.setAttribute("imported_from", node.getModuleName());
            entry.setAttribute("original_name", originalName);
        }
        return null;
    }

    // ========================================
    // Assignments and binding targets
    // ========================================

    @Override
    public Void visitAssignment(AssignmentNode node) {
        Expression target = node.getTarget();
        SymbolType valueType;

        if (node.isAugmented()) {
            // Augmented assignment reads the old target before evaluating the
            // right-hand side and writing the result.
            target.accept(this);
            node.getValue().accept(this);
            valueType = inferType(node.getValue());
            bindStoreTarget(target, node, valueType, false, null);
            markBoundTargetUsed(target);
        } else {
            // Python evaluates the RHS before evaluating a subscript/attribute
            // store target.
            node.getValue().accept(this);
            valueType = inferType(node.getValue());
            bindStoreTarget(target, node, valueType, true, null);
        }
        return null;
    }

    private void bindStoreTarget(Expression target,
                                 ASTNode source,
                                 SymbolType inferredType,
                                 boolean evaluateAddress,
                                 String markerAttribute) {
        if (target instanceof IdentifierNode) {
            String name = ((IdentifierNode) target).getName();
            SymbolEntry entry = bindName(name,
                                         SymbolEntry.SymbolKind.VARIABLE,
                                         inferredType,
                                         source);
            if (markerAttribute != null) {
                entry.setAttribute(markerAttribute, true);
            }
            return;
        }

        if (target instanceof TupleNode) {
            for (Expression element : ((TupleNode) target).getElements()) {
                bindStoreTarget(element, source, SymbolType.UNKNOWN, evaluateAddress, markerAttribute);
            }
            return;
        }

        if (target instanceof ListNode) {
            for (Expression element : ((ListNode) target).getElements()) {
                bindStoreTarget(element, source, SymbolType.UNKNOWN, evaluateAddress, markerAttribute);
            }
            return;
        }

        // Attribute and subscript stores do not create lexical symbols.  Their
        // receiver/index expressions are still reads and must be visited once.
        if (evaluateAddress && (target instanceof AttributeAccessNode || target instanceof SubscriptNode)) {
            target.accept(this);
        } else if (evaluateAddress) {
            target.accept(this);
        }
    }

    private void markBoundTargetUsed(Expression target) {
        if (target instanceof IdentifierNode) {
            String name = ((IdentifierNode) target).getName();
            SymbolEntry entry = currentScope.getBindingScope(name).lookupLocal(name);
            if (entry != null) {
                entry.setUsed(true);
            }
        } else if (target instanceof TupleNode) {
            for (Expression element : ((TupleNode) target).getElements()) {
                markBoundTargetUsed(element);
            }
        } else if (target instanceof ListNode) {
            for (Expression element : ((ListNode) target).getElements()) {
                markBoundTargetUsed(element);
            }
        }
    }

    private SymbolEntry bindName(String name,
                                 SymbolEntry.SymbolKind kind,
                                 SymbolType type,
                                 ASTNode node) {
        SymbolTable bindingScope = currentScope.getBindingScope(name);
        SymbolEntry entry = bindingScope.lookupLocal(name);

        boolean created = entry == null;
        if (created) {
            entry = bindingScope.insert(name, kind);
            if (node != null) {
                entry.setAttribute(FIRST_BINDING_NODE, node);
            }
        }

        // Metadata describes the current binding.  A re-import will set the
        // appropriate values again immediately after this method returns;
        // every other kind of binding must not inherit stale import facts.
        entry.removeAttribute("original_module");
        entry.removeAttribute("imported_module");
        entry.removeAttribute("imported_from");
        entry.removeAttribute("original_name");

        // Assigning to a parameter keeps the useful PARAMETER classification.
        if (!(entry.getKind() == SymbolEntry.SymbolKind.PARAMETER
                && kind == SymbolEntry.SymbolKind.VARIABLE)) {
            entry.setKind(kind);
        }

        if (!(node instanceof FunctionDefNode)) {
            entry.clearFunctionNode();
        }
        if (!(node instanceof ClassDefNode)) {
            entry.clearClassNode();
        }

        // A new assignment replaces the old runtime value.  UNKNOWN is a
        // meaningful conservative result and must not leave a stale old type.
        entry.setType(type);
        if (node != null) {
            entry.setNode(node);
        }
        return entry;
    }

    // ========================================
    // Function and class scopes
    // ========================================

    @Override
    public Void visitFunctionDef(FunctionDefNode node) {
        ensureDefinitionPredeclared(node);

        // Decorators, defaults and annotations are evaluated in the defining
        // scope, not in the new function scope.
        visitDecorators(node.getDecorators());
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

        boolean method = currentScope.getScopeType() == SymbolTable.ScopeType.CLASS;
        SymbolEntry.SymbolKind kind = method
                ? SymbolEntry.SymbolKind.METHOD
                : SymbolEntry.SymbolKind.FUNCTION;
        SymbolType type = method ? SymbolType.METHOD : SymbolType.FUNCTION;
        SymbolEntry functionEntry = bindName(node.getName(), kind, type, node);
        functionEntry.setFunctionNode(node);

        SymbolTable previousScope = currentScope;
        SymbolTable functionScope = previousScope.enterScope(node.getName(), SymbolTable.ScopeType.FUNCTION);
        node.setScope(functionScope);
        allScopes.add(functionScope);
        currentScope = functionScope;

        prepareBlock(node.getBody());
        bindParameters(node);
        visitStatements(node.getBody());

        currentScope = previousScope;
        return null;
    }

    private void bindParameters(FunctionDefNode node) {
        for (Parameter parameter : node.getParameters()) {
            String name = parameter.getName();
            SymbolEntry entry = currentScope.lookupLocal(name);
            if (entry == null) {
                entry = currentScope.insert(name, SymbolEntry.SymbolKind.PARAMETER);
                entry.setAttribute(FIRST_BINDING_NODE, node);
            } else if (entry.getKind() == SymbolEntry.SymbolKind.PARAMETER) {
                reporter.report(Diagnostics.duplicateSymbol(
                        "Parameter", name, node.getLine(), node.getColumn(), sourceFile));
            }
            entry.setKind(SymbolEntry.SymbolKind.PARAMETER);
            entry.setType(SymbolType.UNKNOWN);
            entry.setLine(node.getLine());
            entry.setColumn(node.getColumn());
            if (parameter.hasDefault()) {
                entry.setAttribute("has_default", true);
            }
        }
    }

    @Override
    public Void visitClassDef(ClassDefNode node) {
        ensureDefinitionPredeclared(node);

        // Class decorators and base expressions are resolved outside the new
        // class namespace.
        visitDecorators(node.getDecorators());
        for (Expression base : node.getBases()) {
            base.accept(this);
        }

        SymbolEntry classEntry = bindName(node.getName(),
                                          SymbolEntry.SymbolKind.CLASS,
                                          SymbolType.CLASS,
                                          node);
        classEntry.setClassNode(node);

        SymbolTable previousScope = currentScope;
        SymbolTable classScope = previousScope.enterScope(node.getName(), SymbolTable.ScopeType.CLASS);
        node.setScope(classScope);
        allScopes.add(classScope);
        currentScope = classScope;

        prepareBlock(node.getBody());
        visitStatements(node.getBody());

        currentScope = previousScope;
        return null;
    }

    private void visitDecorators(List<DecoratorNode> decorators) {
        for (DecoratorNode decorator : decorators) {
            decorator.getExpression().accept(this);
        }
    }

    // ========================================
    // Expressions
    // ========================================

    @Override
    public Void visitIdentifier(IdentifierNode node) {
        SymbolEntry entry = currentScope.lookup(node.getName());
        if (entry != null) {
            entry.setUsed(true);
        }
        return null;
    }

    @Override
    public Void visitFunctionCall(FunctionCallNode node) {
        Expression function = node.getFunction();

        if (function instanceof IdentifierNode) {
            String name = ((IdentifierNode) function).getName();
            SymbolEntry entry = currentScope.lookup(name);
            if (entry != null) {
                entry.setUsed(true);
            }
        } else {
            function.accept(this);
        }

        // Do not call super here: it would visit and mark the function
        // expression as used a second time.
        for (CallArgument argument : node.getArguments()) {
            argument.getValue().accept(this);
        }
        return null;
    }

    @Override
    public Void visitAttributeAccess(AttributeAccessNode node) {
        node.getObject().accept(this);
        return null;
    }

    // ========================================
    // Compound statements with bindings
    // ========================================

    @Override
    public Void visitForStatement(ForStatementNode node) {
        // The iterable is evaluated before the target is assigned.  This is
        // observable for `for x in x` and is important for diagnostics.
        node.getIterable().accept(this);
        bindStoreTarget(node.getTarget(), node, SymbolType.UNKNOWN, true, "loop_variable");

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
                bindStoreTarget(item.getAsName(), node, SymbolType.UNKNOWN, true, "with_variable");
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
                SymbolEntry entry = bindName(clause.getAsName(),
                                             SymbolEntry.SymbolKind.VARIABLE,
                                             SymbolType.UNKNOWN,
                                             node);
                entry.setAttribute("exception_variable", true);
                if (!clause.isBareExcept()) {
                    entry.setAttribute("exception_type", clause.getExceptionType());
                }
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

    // ========================================
    // Block preparation: globals and forward declarations
    // ========================================

    private void prepareBlock(List<Statement> statements) {
        collectGlobalDeclarations(statements);
        predeclareDefinitions(statements);
    }

    private void collectGlobalDeclarations(List<Statement> statements) {
        for (Statement statement : statements) {
            if (statement instanceof GlobalNode) {
                for (String name : ((GlobalNode) statement).getNames()) {
                    currentScope.declareGlobal(name);
                }
            } else if (!(statement instanceof FunctionDefNode) && !(statement instanceof ClassDefNode)) {
                for (List<Statement> nestedBlock : currentScopeBlocks(statement)) {
                    collectGlobalDeclarations(nestedBlock);
                }
            }
        }
    }

    private void predeclareDefinitions(List<Statement> statements) {
        for (Statement statement : statements) {
            if (statement instanceof FunctionDefNode || statement instanceof ClassDefNode) {
                predeclareDefinition(statement);
            } else {
                for (List<Statement> nestedBlock : currentScopeBlocks(statement)) {
                    predeclareDefinitions(nestedBlock);
                }
            }
        }
    }

    private void ensureDefinitionPredeclared(Statement definition) {
        String name = definitionName(definition);
        SymbolTable bindingScope = currentScope.getBindingScope(name);
        SymbolEntry existing = bindingScope.lookupLocal(name);
        ASTNode firstDefinition = existing == null
                ? null
                : existing.getAttribute(DEFINITION_NODE, ASTNode.class);
        if (firstDefinition != definition) {
            predeclareDefinition(definition);
        }
    }

    private void predeclareDefinition(Statement definition) {
        String name = definitionName(definition);
        SymbolTable bindingScope = currentScope.getBindingScope(name);
        SymbolEntry existing = bindingScope.lookupLocal(name);
        ASTNode firstDefinition = existing == null
                ? null
                : existing.getAttribute(DEFINITION_NODE, ASTNode.class);

        if (existing == null) {
            SymbolEntry.SymbolKind kind = definitionKind(definition);
            existing = bindingScope.insert(name, kind);
            configureDefinitionEntry(existing, definition, kind);
            existing.setAttribute(DEFINITION_NODE, definition);
            existing.setAttribute(FIRST_BINDING_NODE, definition);
            return;
        }

        if (firstDefinition == null) {
            SymbolEntry.SymbolKind kind = definitionKind(definition);
            configureDefinitionEntry(existing, definition, kind);
            existing.setAttribute(DEFINITION_NODE, definition);
            return;
        }

        if (firstDefinition != definition && duplicateDefinitionNodes.add(definition)) {
            String label = definition instanceof FunctionDefNode ? "Function" : "Class";
            reporter.report(Diagnostics.duplicateSymbol(
                    label,
                    name,
                    definition.getLine(),
                    definition.getColumn(),
                    sourceFile));
        }
    }

    private void configureDefinitionEntry(SymbolEntry entry,
                                          Statement definition,
                                          SymbolEntry.SymbolKind kind) {
        entry.setKind(kind);
        if (definition instanceof FunctionDefNode) {
            entry.setType(kind == SymbolEntry.SymbolKind.METHOD
                    ? SymbolType.METHOD
                    : SymbolType.FUNCTION);
            entry.setFunctionNode((FunctionDefNode) definition);
        } else {
            entry.setType(SymbolType.CLASS);
            entry.setClassNode((ClassDefNode) definition);
        }
    }

    private SymbolEntry.SymbolKind definitionKind(Statement definition) {
        if (definition instanceof ClassDefNode) {
            return SymbolEntry.SymbolKind.CLASS;
        }
        return currentScope.getScopeType() == SymbolTable.ScopeType.CLASS
                ? SymbolEntry.SymbolKind.METHOD
                : SymbolEntry.SymbolKind.FUNCTION;
    }

    private String definitionName(Statement definition) {
        if (definition instanceof FunctionDefNode) {
            return ((FunctionDefNode) definition).getName();
        }
        return ((ClassDefNode) definition).getName();
    }

    /**
     * Returns child statement lists that execute in the same Python code
     * block.  Function and class bodies are intentionally excluded by the
     * caller because each owns a new scope.
     */
    private List<List<Statement>> currentScopeBlocks(Statement statement) {
        List<List<Statement>> blocks = new ArrayList<>();

        if (statement instanceof IfStatementNode) {
            IfStatementNode ifNode = (IfStatementNode) statement;
            blocks.add(ifNode.getThenBody());
            for (IfStatementNode.ElifClause clause : ifNode.getElifClauses()) {
                blocks.add(clause.getBody());
            }
            if (ifNode.hasElse()) {
                blocks.add(ifNode.getElseBody());
            }
        } else if (statement instanceof ForStatementNode) {
            ForStatementNode forNode = (ForStatementNode) statement;
            blocks.add(forNode.getBody());
            if (forNode.hasElse()) {
                blocks.add(forNode.getElseBody());
            }
        } else if (statement instanceof WhileStatementNode) {
            WhileStatementNode whileNode = (WhileStatementNode) statement;
            blocks.add(whileNode.getBody());
            if (whileNode.hasElse()) {
                blocks.add(whileNode.getElseBody());
            }
        } else if (statement instanceof WithStatementNode) {
            blocks.add(((WithStatementNode) statement).getBody());
        } else if (statement instanceof TryStatementNode) {
            TryStatementNode tryNode = (TryStatementNode) statement;
            blocks.add(tryNode.getTryBody());
            for (ExceptClause clause : tryNode.getExceptClauses()) {
                blocks.add(clause.getBody());
            }
            if (tryNode.hasElse()) {
                blocks.add(tryNode.getElseBody());
            }
            if (tryNode.hasFinally()) {
                blocks.add(tryNode.getFinallyBody());
            }
        }
        return blocks;
    }

    // ========================================
    // General helpers
    // ========================================

    private void visitStatements(List<Statement> statements) {
        for (Statement statement : statements) {
            statement.accept(this);
        }
    }

    private SymbolType inferType(Expression expression) {
        if (expression instanceof LiteralNode) {
            return SymbolType.fromLiteralType(((LiteralNode) expression).getLiteralType());
        }
        if (expression instanceof IdentifierNode) {
            SymbolEntry entry = currentScope.lookup(((IdentifierNode) expression).getName());
            return entry == null ? SymbolType.UNKNOWN : entry.getType();
        }
        if (expression instanceof ListNode) {
            return SymbolType.LIST;
        }
        if (expression instanceof DictNode) {
            return SymbolType.DICT;
        }
        if (expression instanceof SetNode) {
            return SymbolType.SET;
        }
        if (expression instanceof TupleNode) {
            return SymbolType.TUPLE;
        }
        if (expression instanceof FunctionCallNode) {
            Expression function = ((FunctionCallNode) expression).getFunction();
            if (function instanceof IdentifierNode) {
                SymbolEntry entry = currentScope.lookup(((IdentifierNode) function).getName());
                if (entry != null && entry.getKind() == SymbolEntry.SymbolKind.CLASS) {
                    return SymbolType.UNKNOWN;
                }
            }
        }
        return SymbolType.UNKNOWN;
    }
}
