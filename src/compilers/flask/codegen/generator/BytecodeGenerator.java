package compilers.flask.codegen.generator;

import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
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
import compilers.flask.codegen.analysis.BindingAnalysisResult;
import compilers.flask.codegen.analysis.BindingKind;
import compilers.flask.codegen.analysis.NameOccurrence;
import compilers.flask.codegen.analysis.ResolvedName;
import compilers.flask.codegen.analysis.ScopeLayout;
import compilers.flask.codegen.bytecode.CleanupRegion;
import compilers.flask.codegen.bytecode.CodeKind;
import compilers.flask.codegen.bytecode.CodeObjectBuilder;
import compilers.flask.codegen.bytecode.FunctionSignature;
import compilers.flask.codegen.bytecode.Label;
import compilers.flask.codegen.bytecode.OpCode;
import compilers.flask.codegen.bytecode.Operand;
import compilers.flask.runtime.RuntimeSymbolSpec;
import compilers.flask.vm.values.PyBool;
import compilers.flask.vm.values.PyBaseException;
import compilers.flask.vm.values.PyFloat;
import compilers.flask.vm.values.PyInt;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyValue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Lowers the completed Part-3 AST subset into symbolic nested code objects. */
public final class BytecodeGenerator {
    private final ProgramNode program;
    private final BindingAnalysisResult bindings;
    private final DiagnosticReporter reporter;
    private final String sourceFile;
    private final String moduleName;
    private CodeGenerationContext context;

    public BytecodeGenerator(
            ProgramNode program,
            BindingAnalysisResult bindings,
            DiagnosticReporter reporter,
            String sourceFile,
            String moduleName) {
        this.program = Objects.requireNonNull(program, "program");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.sourceFile = sourceFile == null ? "" : sourceFile;
        this.moduleName = moduleName == null || moduleName.isEmpty()
                ? "__main__"
                : moduleName;
    }

    /** Returns null after any code-generation diagnostic. */
    public CodeObjectBuilder generate() {
        if (reporter.hasErrors()) {
            return null;
        }
        if (bindings.getProgram() != program) {
            invalid(program, "Binding analysis belongs to a different AST instance");
            return null;
        }
        ScopeLayout scope = bindings.getRootScope();
        if (scope.getOwner() != program || scope.getKind() != ScopeLayout.ScopeKind.MODULE) {
            invalid(program, "Root binding layout is not the program module scope");
            return null;
        }

        CodeObjectBuilder builder = new CodeObjectBuilder(
                scope.getOrdinal(),
                CodeKind.MODULE,
                "<module>",
                moduleName,
                sourceFile,
                FunctionSignature.EMPTY,
                scope.getFastLocalNames(),
                scope.getCellVars(),
                scope.getFreeVars());
        context = new CodeGenerationContext(
                builder, bindings, scope, reporter, sourceFile);

        boolean completed = emitStatements(program.getStatements());
        if (completed && !reporter.hasErrors()) {
            int none = builder.addConstant(PyNone.INSTANCE);
            builder.emit(
                    OpCode.LOAD_CONST,
                    new Operand.ConstOperand(none),
                    span(program),
                    true);
            builder.emit(OpCode.RETURN_VALUE, span(program), true);
        }

        if (context.hasLoops() || context.getTemporaries().hasActiveSlots()) {
            invalid(program, "Code generation leaked loop or temporary state");
        }
        builder.setTemporarySlotCount(context.getTemporaries().getSlotCount());
        if (reporter.hasErrors()) {
            return null;
        }
        builder.seal();
        return builder;
    }

    private boolean emitStatements(List<Statement> statements) {
        for (Statement statement : statements) {
            if (!emitStatement(statement)) {
                return false;
            }
            // Region entries in unreachable code are deliberately invalid to
            // the verifier.  Stop lowering once this statement closes every
            // normal path in the current statement list; binding/structural
            // passes have already inspected the complete AST.
            if (terminatesCurrentStatementList(statement)) {
                break;
            }
        }
        return true;
    }

    private boolean terminatesCurrentStatementList(Statement statement) {
        if (statement instanceof BreakNode
                || statement instanceof ContinueNode
                || statement instanceof ReturnNode
                || statement instanceof RaiseNode) {
            return true;
        }
        if (!(statement instanceof IfStatementNode)) {
            return false;
        }

        IfStatementNode conditional = (IfStatementNode) statement;
        if (conditional.getElseBody() == null
                || !statementListTerminates(conditional.getThenBody())
                || !statementListTerminates(conditional.getElseBody())) {
            return false;
        }
        for (IfStatementNode.ElifClause clause : conditional.getElifClauses()) {
            if (!statementListTerminates(clause.getBody())) {
                return false;
            }
        }
        return true;
    }

    private boolean statementListTerminates(List<Statement> statements) {
        for (Statement statement : statements) {
            if (terminatesCurrentStatementList(statement)) {
                return true;
            }
        }
        return false;
    }

    private boolean emitStatement(Statement statement) {
        if (statement instanceof AssignmentNode) {
            return emitAssignment((AssignmentNode) statement);
        }
        if (statement instanceof ExpressionStatementNode) {
            Expression expression = ((ExpressionStatementNode) statement).getExpression();
            if (!emitExpression(expression)) return false;
            emit(OpCode.POP_TOP, statement);
            return true;
        }
        if (statement instanceof PassNode || statement instanceof GlobalNode) {
            emit(OpCode.NOP, statement);
            return true;
        }
        if (statement instanceof DelNode) {
            for (Expression target : ((DelNode) statement).getTargets()) {
                if (!emitDeleteTarget(target)) return false;
            }
            return true;
        }
        if (statement instanceof IfStatementNode) {
            return emitIf((IfStatementNode) statement);
        }
        if (statement instanceof WhileStatementNode) {
            return emitWhile((WhileStatementNode) statement);
        }
        if (statement instanceof ForStatementNode) {
            return emitFor((ForStatementNode) statement);
        }
        if (statement instanceof BreakNode) {
            return emitBreak((BreakNode) statement);
        }
        if (statement instanceof ContinueNode) {
            return emitContinue((ContinueNode) statement);
        }
        if (statement instanceof ReturnNode) {
            return emitReturn((ReturnNode) statement);
        }
        if (statement instanceof FunctionDefNode) {
            return emitFunctionDefinition((FunctionDefNode) statement);
        }
        if (statement instanceof ClassDefNode) {
            return emitClassDefinition((ClassDefNode) statement);
        }
        if (statement instanceof ImportNode) {
            return emitImport((ImportNode) statement);
        }
        if (statement instanceof FromImportNode) {
            return emitFromImport((FromImportNode) statement);
        }
        if (statement instanceof AssertNode) {
            return emitAssert((AssertNode) statement);
        }
        if (statement instanceof RaiseNode) {
            return emitRaise((RaiseNode) statement);
        }
        if (statement instanceof TryStatementNode) {
            return emitTry((TryStatementNode) statement);
        }
        if (statement instanceof WithStatementNode) {
            return emitWith((WithStatementNode) statement);
        }
        unsupported(statement);
        return false;
    }

    private boolean emitReturn(ReturnNode node) {
        if (context.getBuilder().getKind() != CodeKind.FUNCTION) {
            invalid(node, "'return' reached non-function bytecode generation");
            return false;
        }
        if (node.hasValue()) {
            if (!emitExpression(node.getValue())) {
                return false;
            }
        } else {
            emitConstant(PyNone.INSTANCE, node, true);
        }
        context.getBuilder().emit(
                OpCode.RETURN_VALUE, span(node), false);
        return true;
    }

    private boolean emitAssert(AssertNode node) {
        Label passed = label("assert_passed");
        if (!emitExpression(node.getTest())) return false;
        jump(OpCode.POP_JUMP_IF_TRUE, passed, node.getTest(), false);

        // The current instruction set has no dedicated assertion constructor;
        // use the mandatory builtin provider and preserve lazy message order.
        emitNameOperand(OpCode.LOAD_GLOBAL, "AssertionError", node);
        int argumentCount = 0;
        if (node.hasMessage()) {
            if (!emitExpression(node.getMessage())) return false;
            argumentCount = 1;
        }
        context.getBuilder().emit(
                OpCode.CALL,
                new Operand.CallSpec(
                        argumentCount, Collections.<String>emptyList()),
                span(node),
                false);
        emitCount(OpCode.RAISE, 1, node);
        mark(passed);
        return true;
    }

    private boolean emitRaise(RaiseNode node) {
        int count = 0;
        if (!node.isBareRaise()) {
            if (!emitExpression(node.getException())) return false;
            count = 1;
            if (node.hasCause()) {
                if (!emitExpression(node.getCause())) return false;
                count = 2;
            }
        }
        emitCount(OpCode.RAISE, count, node);
        return true;
    }

    private boolean emitTry(TryStatementNode node) {
        if (!node.hasFinally()) {
            return emitTryCore(node);
        }

        Label protectedStart = label("finally_protected_start");
        Label protectedEnd = label("finally_protected_end");
        Label cleanupHandler = label("finally_cleanup");
        Label continuation = label("finally_done");
        int cleanupId = context.getBuilder().addCleanupRegion(
                CleanupRegion.Kind.FINALLY,
                protectedStart,
                protectedEnd,
                cleanupHandler,
                continuation,
                -1);

        mark(protectedStart);
        if (!emitTryCore(node)) return false;
        context.getBuilder().emit(OpCode.NOP, span(node), true);
        context.getBuilder().emit(
                OpCode.ENTER_CLEANUP,
                new Operand.CleanupSpec(cleanupId),
                span(node),
                true);
        mark(protectedEnd);

        mark(cleanupHandler);
        if (!emitStatements(node.getFinallyBody())) return false;
        context.getBuilder().emit(
                OpCode.END_CLEANUP,
                new Operand.CleanupSpec(cleanupId),
                span(node),
                true);
        mark(continuation);
        return true;
    }

    /** Emits try/except/else; an enclosing finally, when present, is external. */
    private boolean emitTryCore(TryStatementNode node) {
        if (!node.hasExcept()) {
            return emitStatements(node.getTryBody());
        }

        Label tryStart = label("try_start");
        Label tryEnd = label("try_end");
        Label dispatch = label("except_dispatch");
        Label elseEntry = label("try_else");
        Label done = label("try_done");
        context.getBuilder().addExceptionRegion(tryStart, tryEnd, dispatch);

        mark(tryStart);
        if (!emitStatements(node.getTryBody())) return false;
        mark(tryEnd);
        jump(OpCode.JUMP, elseEntry, node, true);

        mark(dispatch);
        int aliasOrdinal = 0;
        List<ExceptClause> clauses = node.getExceptClauses();
        for (int index = 0; index < clauses.size(); index++) {
            ExceptClause clause = clauses.get(index);
            Label nextHandler = label("except_next_" + index);
            if (!clause.isBareExcept()) {
                emit(OpCode.LOAD_CURRENT_EXCEPTION, node);
                if (!emitExpression(clause.getExceptionType())) return false;
                emit(OpCode.EXCEPTION_MATCH, node);
                jump(OpCode.POP_JUMP_IF_FALSE,
                        nextHandler, clause.getExceptionType(), false);
            } else if (index + 1 != clauses.size()) {
                invalid(node, "A bare except handler must be last");
                return false;
            }

            ResolvedName alias = null;
            if (clause.hasAsName()) {
                final NameOccurrence occurrence;
                try {
                    occurrence = bindings.requireOccurrence(
                            node, NameOccurrence.Role.EXCEPT_ALIAS,
                            aliasOrdinal++);
                } catch (IllegalArgumentException missing) {
                    invalid(node, missing.getMessage());
                    return false;
                }
                if (occurrence.getScope() != context.getScope()
                        || !clause.getAsName().equals(occurrence.getName())) {
                    invalid(node,
                            "Except alias binding does not match its handler");
                    return false;
                }
                alias = occurrence.getResolvedName();
            }

            int handlerId = context.nextHandlerId();
            Label handlerStart = label("except_handler_" + handlerId);
            Label handlerEnd = label("except_handler_end_" + handlerId);
            int cleanupId = context.getBuilder().addCleanupRegion(
                    -1,
                    0,
                    CleanupRegion.Kind.EXCEPT_HANDLER,
                    handlerStart,
                    handlerEnd,
                    null,
                    null,
                    Collections.<Integer>emptyList(),
                    handlerId,
                    alias);

            mark(handlerStart);
            context.getBuilder().emit(
                    OpCode.BEGIN_EXCEPT,
                    new Operand.HandlerSpec(handlerId, cleanupId, alias),
                    span(node),
                    true);
            if (!emitStatements(clause.getBody())) return false;
            context.getBuilder().emit(
                    OpCode.END_EXCEPT,
                    new Operand.CleanupSpec(handlerId),
                    span(node),
                    true);
            mark(handlerEnd);
            jump(OpCode.JUMP, done, node, true);

            if (!clause.isBareExcept()) {
                mark(nextHandler);
            }
        }

        // No handler claimed the frame's dispatch candidate.  Bare raise
        // resumes the same visited transfer, so this region cannot re-enter.
        emitCount(OpCode.RAISE, 0, node);

        mark(elseEntry);
        if (node.hasElse() && !emitStatements(node.getElseBody())) {
            return false;
        }
        mark(done);
        return true;
    }

    private boolean emitWith(WithStatementNode node) {
        if (node.getItems().isEmpty()) {
            invalid(node, "With statement has no context managers");
            return false;
        }
        return emitWithItem(node, 0);
    }

    private boolean emitWithItem(WithStatementNode owner, int itemIndex) {
        WithItem item = owner.getItems().get(itemIndex);
        int exitSlot = context.getTemporaries().allocate();
        int enteredSlot = item.hasAsName()
                ? context.getTemporaries().allocate()
                : -1;
        try {
            Label protectedStart = label("with_protected_" + itemIndex);
            Label protectedEnd = label("with_protected_end_" + itemIndex);
            Label cleanupHandler = label("with_exit_" + itemIndex);
            Label continuation = label("with_done_" + itemIndex);
            int cleanupId = context.getBuilder().addCleanupRegion(
                    CleanupRegion.Kind.WITH,
                    protectedStart,
                    protectedEnd,
                    cleanupHandler,
                    continuation,
                    exitSlot);

            Label enteredLifetimeEnd = null;
            if (enteredSlot >= 0) {
                enteredLifetimeEnd = label("with_target_bound_" + itemIndex);
                context.getBuilder().addCleanupRegion(
                        CleanupRegion.Kind.TEMP_CLEAR,
                        protectedStart,
                        enteredLifetimeEnd,
                        null,
                        null,
                        enteredSlot);
            }

            if (!emitExpression(item.getContextExpr())) return false;
            context.getBuilder().emit(
                    OpCode.WITH_ENTER,
                    new Operand.WithSpec(cleanupId, exitSlot),
                    span(owner),
                    false);
            if (enteredSlot >= 0) {
                emitTemp(OpCode.STORE_TEMP, enteredSlot, owner, true);
            } else {
                emit(OpCode.POP_TOP, owner);
            }

            mark(protectedStart);
            if (enteredSlot >= 0) {
                emitTemp(OpCode.LOAD_TEMP, enteredSlot, owner, true);
                if (!emitStoreTarget(item.getAsName())) return false;
                emitTemp(OpCode.CLEAR_TEMP, enteredSlot, owner, true);
                mark(enteredLifetimeEnd);
            }

            if (itemIndex + 1 < owner.getItems().size()) {
                if (!emitWithItem(owner, itemIndex + 1)) return false;
            } else if (!emitStatements(owner.getBody())) {
                return false;
            }

            context.getBuilder().emit(OpCode.NOP, span(owner), true);
            context.getBuilder().emit(
                    OpCode.ENTER_CLEANUP,
                    new Operand.CleanupSpec(cleanupId),
                    span(owner),
                    true);
            mark(protectedEnd);
            mark(cleanupHandler);
            context.getBuilder().emit(
                    OpCode.WITH_EXIT,
                    new Operand.WithSpec(cleanupId, exitSlot),
                    span(owner),
                    true);
            context.getBuilder().emit(
                    OpCode.END_CLEANUP,
                    new Operand.CleanupSpec(cleanupId),
                    span(owner),
                    true);
            mark(continuation);
            return true;
        } finally {
            if (enteredSlot >= 0) {
                context.getTemporaries().release(enteredSlot);
            }
            context.getTemporaries().release(exitSlot);
        }
    }

    private boolean emitFunctionDefinition(FunctionDefNode node) {
        for (DecoratorNode decorator : node.getDecorators()) {
            if (!emitExpression(decorator.getExpression())) {
                return false;
            }
        }

        List<String> parameterNames = new java.util.ArrayList<>();
        List<String> defaultNames = new java.util.ArrayList<>();
        List<String> annotationNames = new java.util.ArrayList<>();
        for (Parameter parameter : node.getParameters()) {
            parameterNames.add(parameter.getName());
            if (parameter.hasDefault()) {
                defaultNames.add(parameter.getName());
                if (!emitExpression(parameter.getDefaultValue())) {
                    return false;
                }
            }
        }
        for (Parameter parameter : node.getParameters()) {
            if (parameter.hasTypeHint()) {
                annotationNames.add(parameter.getName());
                if (!emitExpression(parameter.getTypeHint())) {
                    return false;
                }
            }
        }
        if (node.hasReturnType()) {
            annotationNames.add("return");
            if (!emitExpression(node.getReturnType())) {
                return false;
            }
        }

        final ScopeLayout functionScope;
        try {
            functionScope = bindings.requireScope(node);
        } catch (IllegalArgumentException missing) {
            invalid(node, missing.getMessage());
            return false;
        }
        final FunctionSignature signature;
        try {
            signature = new FunctionSignature(parameterNames, defaultNames);
        } catch (IllegalArgumentException invalidSignature) {
            invalid(node, invalidSignature.getMessage());
            return false;
        }

        CodeObjectBuilder child = buildNestedCode(
                node,
                CodeKind.FUNCTION,
                node.getName(),
                nestedQualifiedName(node.getName()),
                signature,
                functionScope,
                node.getBody());
        if (child == null) {
            return false;
        }

        for (String freeName : functionScope.getFreeVars()) {
            if (!emitClosureCell(freeName, node)) {
                return false;
            }
        }
        int codeIndex = context.getBuilder().addCodeObjectBuilder(child);
        context.getBuilder().emit(
                OpCode.LOAD_CONST,
                new Operand.ConstOperand(codeIndex),
                span(node),
                true);
        context.getBuilder().emit(
                OpCode.MAKE_FUNCTION,
                new Operand.MakeFunctionSpec(
                        defaultNames,
                        annotationNames,
                        functionScope.getFreeVars()),
                span(node),
                false);

        for (int index = node.getDecorators().size() - 1;
                index >= 0; index--) {
            context.getBuilder().emit(
                    OpCode.CALL,
                    new Operand.CallSpec(1, Collections.<String>emptyList()),
                    span(node.getDecorators().get(index).getExpression()),
                    true);
        }
        return emitBindingOccurrence(
                node, NameOccurrence.Role.FUNCTION_DEFINITION, 0, node);
    }

    private boolean emitClassDefinition(ClassDefNode node) {
        // Python evaluates decorator expressions top-to-bottom before bases.
        for (DecoratorNode decorator : node.getDecorators()) {
            if (!emitExpression(decorator.getExpression())) {
                return false;
            }
        }
        for (Expression base : node.getBases()) {
            if (!emitExpression(base)) {
                return false;
            }
        }

        final ScopeLayout classScope;
        try {
            classScope = bindings.requireScope(node);
        } catch (IllegalArgumentException missing) {
            invalid(node, missing.getMessage());
            return false;
        }
        CodeObjectBuilder child = buildNestedCode(
                node,
                CodeKind.CLASS_BODY,
                node.getName(),
                nestedQualifiedName(node.getName()),
                FunctionSignature.EMPTY,
                classScope,
                node.getBody());
        if (child == null) {
            return false;
        }

        for (String freeName : classScope.getFreeVars()) {
            if (!emitClosureCell(freeName, node)) {
                return false;
            }
        }
        int codeIndex = context.getBuilder().addCodeObjectBuilder(child);
        context.getBuilder().emit(
                OpCode.LOAD_CONST,
                new Operand.ConstOperand(codeIndex),
                span(node),
                true);
        context.getBuilder().emit(
                OpCode.BUILD_CLASS,
                new Operand.BuildClassSpec(
                        node.getName(), node.getBaseCount(), classScope.getFreeVars()),
                span(node),
                false);

        for (int index = node.getDecorators().size() - 1;
                index >= 0; index--) {
            context.getBuilder().emit(
                    OpCode.CALL,
                    new Operand.CallSpec(1, Collections.<String>emptyList()),
                    span(node.getDecorators().get(index).getExpression()),
                    true);
        }
        return emitBindingOccurrence(
                node, NameOccurrence.Role.CLASS_DEFINITION, 0, node);
    }

    private boolean emitImport(ImportNode node) {
        Operand.ImportSpec.ResultMode mode = node.hasAlias()
                ? Operand.ImportSpec.ResultMode.LEAF
                : Operand.ImportSpec.ResultMode.TOP_LEVEL;
        context.getBuilder().emit(
                OpCode.IMPORT_NAME,
                new Operand.ImportSpec(node.getModuleName(), mode),
                span(node),
                false);
        return emitBindingOccurrence(
                node, NameOccurrence.Role.IMPORT_BINDING, 0, node);
    }

    private boolean emitFromImport(FromImportNode node) {
        context.getBuilder().emit(
                OpCode.IMPORT_NAME,
                new Operand.ImportSpec(
                        node.getModuleName(), Operand.ImportSpec.ResultMode.LEAF),
                span(node),
                false);
        if (node.isImportAll()) {
            context.getBuilder().emit(
                    OpCode.IMPORT_STAR, span(node), false);
            return true;
        }
        for (int index = 0; index < node.getItems().size(); index++) {
            FromImportNode.ImportItem item = node.getItems().get(index);
            emitNameOperand(OpCode.IMPORT_FROM, item.getName(), node);
            if (!emitBindingOccurrence(
                    node, NameOccurrence.Role.IMPORT_BINDING, index, node)) {
                return false;
            }
        }
        emit(OpCode.POP_TOP, node);
        return true;
    }

    private CodeObjectBuilder buildNestedCode(
            ASTNode owner,
            CodeKind kind,
            String name,
            String qualifiedName,
            FunctionSignature signature,
            ScopeLayout scope,
            List<Statement> body) {
        CodeObjectBuilder child = new CodeObjectBuilder(
                scope.getOrdinal(), kind, name, qualifiedName, sourceFile,
                signature, scope.getFastLocalNames(), scope.getCellVars(),
                scope.getFreeVars());
        CodeGenerationContext parentContext = context;
        context = new CodeGenerationContext(
                child, bindings, scope, reporter, sourceFile);
        try {
            if (!emitStatements(body) || reporter.hasErrors()) {
                return null;
            }
            if (!statementListTerminates(body)) {
                emitConstant(PyNone.INSTANCE, owner, true);
                child.emit(OpCode.RETURN_VALUE, span(owner), true);
            }
            if (context.hasLoops()
                    || context.getTemporaries().hasActiveSlots()) {
                invalid(owner,
                        "Nested code generation leaked loop or temporary state");
                return null;
            }
            child.setTemporarySlotCount(
                    context.getTemporaries().getSlotCount());
            child.seal();
            return child;
        } finally {
            context = parentContext;
        }
    }

    private boolean emitClosureCell(String name, ASTNode owner) {
        final int slot;
        try {
            slot = context.getScope().getDerefSlot(name);
        } catch (IllegalArgumentException missing) {
            invalid(owner, "No closure cell named '" + name
                    + "' exists in the defining scope");
            return false;
        }
        context.getBuilder().emit(
                OpCode.LOAD_CLOSURE,
                new Operand.DerefSlotOperand(slot),
                span(owner),
                true);
        return true;
    }

    private boolean emitBindingOccurrence(
            ASTNode occurrenceOwner,
            NameOccurrence.Role occurrenceRole,
            int ordinal,
            ASTNode locationOwner) {
        final NameOccurrence occurrence;
        try {
            occurrence = bindings.requireOccurrence(
                    occurrenceOwner, occurrenceRole, ordinal);
        } catch (IllegalArgumentException missing) {
            invalid(locationOwner, missing.getMessage());
            return false;
        }
        if (occurrence.getScope() != context.getScope()) {
            invalid(locationOwner,
                    "Binding occurrence belongs to a different executable scope");
            return false;
        }
        return emitResolvedName(
                occurrence.getResolvedName(),
                NameOccurrence.Role.STORE,
                locationOwner);
    }

    private String nestedQualifiedName(String name) {
        String parent = context.getBuilder().getQualifiedName();
        return context.getBuilder().getKind() == CodeKind.FUNCTION
                ? parent + ".<locals>." + name
                : parent + "." + name;
    }

    private boolean emitAssignment(AssignmentNode node) {
        if (!node.isAugmented()) {
            return emitExpression(node.getValue()) && emitStoreTarget(node.getTarget());
        }
        Operand.BinaryOperator operation = augmentedOperator(node.getOperator(), node);
        if (operation == null) return false;
        Expression target = node.getTarget();
        if (target instanceof IdentifierNode) {
            if (!emitName((IdentifierNode) target, NameOccurrence.Role.LOAD)) return false;
            if (!emitExpression(node.getValue())) return false;
            emitBinary(operation, node);
            return emitName((IdentifierNode) target, NameOccurrence.Role.STORE);
        }
        if (target instanceof AttributeAccessNode) {
            AttributeAccessNode attribute = (AttributeAccessNode) target;
            if (!emitExpression(attribute.getObject())) return false;
            emitCount(OpCode.COPY, 1, target);
            emitNameOperand(OpCode.LOAD_ATTR, attribute.getAttribute(), target);
            if (!emitExpression(node.getValue())) return false;
            emitBinary(operation, node);
            emitCount(OpCode.SWAP, 2, target);
            emitNameOperand(OpCode.STORE_ATTR, attribute.getAttribute(), target);
            return true;
        }
        if (target instanceof SubscriptNode) {
            SubscriptNode subscript = (SubscriptNode) target;
            if (!emitExpression(subscript.getObject())
                    || !emitExpression(subscript.getIndex())) return false;
            emitCount(OpCode.COPY, 2, target);
            emitCount(OpCode.COPY, 2, target);
            emit(OpCode.BINARY_SUBSCR, target);
            if (!emitExpression(node.getValue())) return false;
            emitBinary(operation, node);
            emitCount(OpCode.SWAP, 3, target);
            emitCount(OpCode.SWAP, 2, target);
            emit(OpCode.STORE_SUBSCR, target);
            return true;
        }
        invalid(target, "Invalid augmented-assignment target during code generation");
        return false;
    }

    private boolean emitStoreTarget(Expression target) {
        if (target instanceof IdentifierNode) {
            return emitName((IdentifierNode) target, NameOccurrence.Role.STORE);
        }
        if (target instanceof AttributeAccessNode) {
            AttributeAccessNode attribute = (AttributeAccessNode) target;
            if (!emitExpression(attribute.getObject())) return false;
            emitNameOperand(OpCode.STORE_ATTR, attribute.getAttribute(), target);
            return true;
        }
        if (target instanceof SubscriptNode) {
            SubscriptNode subscript = (SubscriptNode) target;
            if (!emitExpression(subscript.getObject())
                    || !emitExpression(subscript.getIndex())) return false;
            emit(OpCode.STORE_SUBSCR, target);
            return true;
        }
        List<Expression> elements = targetElements(target);
        if (elements != null) {
            emitCount(OpCode.UNPACK_SEQUENCE, elements.size(), target);
            for (Expression element : elements) {
                if (!emitStoreTarget(element)) return false;
            }
            return true;
        }
        invalid(target, "Invalid assignment target during code generation");
        return false;
    }

    private boolean emitDeleteTarget(Expression target) {
        if (target instanceof IdentifierNode) {
            return emitName((IdentifierNode) target, NameOccurrence.Role.DELETE);
        }
        if (target instanceof AttributeAccessNode) {
            AttributeAccessNode attribute = (AttributeAccessNode) target;
            if (!emitExpression(attribute.getObject())) return false;
            emitNameOperand(OpCode.DELETE_ATTR, attribute.getAttribute(), target);
            return true;
        }
        if (target instanceof SubscriptNode) {
            SubscriptNode subscript = (SubscriptNode) target;
            if (!emitExpression(subscript.getObject())
                    || !emitExpression(subscript.getIndex())) return false;
            emit(OpCode.DELETE_SUBSCR, target);
            return true;
        }
        List<Expression> elements = targetElements(target);
        if (elements != null) {
            for (Expression element : elements) {
                if (!emitDeleteTarget(element)) return false;
            }
            return true;
        }
        invalid(target, "Invalid delete target during code generation");
        return false;
    }

    private boolean emitIf(IfStatementNode node) {
        Label end = label("if_end");
        Label next = label("if_next");
        if (!emitExpression(node.getCondition())) return false;
        jump(OpCode.POP_JUMP_IF_FALSE, next, node.getCondition(), false);
        if (!emitStatements(node.getThenBody())) return false;
        // Only branch to the merge point when this arm can fall through.  A
        // terminating arm (return/raise/break/continue) would make this JUMP
        // unreachable dead code, and when every arm terminates the merge label
        // resolves one past the code with no landing instruction.
        if (!statementListTerminates(node.getThenBody())) {
            jump(OpCode.JUMP, end, node, true);
        }
        mark(next);

        for (int i = 0; i < node.getElifClauses().size(); i++) {
            IfStatementNode.ElifClause clause = node.getElifClauses().get(i);
            Label following = label("elif_next_" + i);
            if (!emitExpression(clause.getCondition())) return false;
            jump(OpCode.POP_JUMP_IF_FALSE, following, clause.getCondition(), false);
            if (!emitStatements(clause.getBody())) return false;
            if (!statementListTerminates(clause.getBody())) {
                jump(OpCode.JUMP, end, node, true);
            }
            mark(following);
        }
        if (node.getElseBody() != null && !emitStatements(node.getElseBody())) {
            return false;
        }
        mark(end);
        return true;
    }

    private boolean emitWhile(WhileStatementNode node) {
        Label top = label("while_top");
        Label elseEntry = label("while_else");
        Label end = label("while_end");
        mark(top);
        if (!emitExpression(node.getCondition())) return false;
        jump(OpCode.POP_JUMP_IF_FALSE, elseEntry, node.getCondition(), false);
        LoopContext loop = new LoopContext(top, end, true);
        context.pushLoop(loop);
        boolean bodyOkay;
        try {
            bodyOkay = emitStatements(node.getBody());
        } finally {
            context.popLoop(loop);
        }
        if (!bodyOkay) return false;
        jump(OpCode.JUMP, top, node, true);
        mark(elseEntry);
        if (node.getElseBody() != null && !emitStatements(node.getElseBody())) {
            return false;
        }
        mark(end);
        return true;
    }

    private boolean emitFor(ForStatementNode node) {
        int iteratorSlot = context.getTemporaries().allocate();
        Label top = label("for_top");
        Label elseEntry = label("for_else");
        Label end = label("for_end");
        try {
            if (!emitExpression(node.getIterable())) return false;
            emit(OpCode.GET_ITER, node.getIterable());
            emitTemp(OpCode.STORE_TEMP, iteratorSlot, node, true);
            mark(top);
            emitTemp(OpCode.LOAD_TEMP, iteratorSlot, node, true);
            jump(OpCode.FOR_ITER, elseEntry, node, true);
            if (!emitStoreTarget(node.getTarget())) return false;

            LoopContext loop = new LoopContext(top, end, true);
            context.pushLoop(loop);
            boolean bodyOkay;
            try {
                bodyOkay = emitStatements(node.getBody());
            } finally {
                context.popLoop(loop);
            }
            if (!bodyOkay) return false;
            jump(OpCode.JUMP, top, node, true);
            mark(elseEntry);
            emitTemp(OpCode.CLEAR_TEMP, iteratorSlot, node, true);
            if (node.getElseBody() != null && !emitStatements(node.getElseBody())) {
                return false;
            }
            mark(end);
            context.getBuilder().addCleanupRegion(
                    CleanupRegion.Kind.TEMP_CLEAR,
                    top,
                    elseEntry,
                    null,
                    null,
                    iteratorSlot);
            return true;
        } finally {
            context.getTemporaries().release(iteratorSlot);
        }
    }

    private boolean emitBreak(BreakNode node) {
        LoopContext loop = context.currentLoop();
        if (loop == null) {
            invalid(node, "'break' has no loop in this code object");
            return false;
        }
        jump(OpCode.UNWIND_JUMP, loop.getBreakTarget(), node, false);
        return true;
    }

    private boolean emitContinue(ContinueNode node) {
        LoopContext loop = context.currentLoop();
        if (loop == null) {
            invalid(node, "'continue' has no loop in this code object");
            return false;
        }
        jump(OpCode.UNWIND_JUMP, loop.getContinueTarget(), node, false);
        return true;
    }

    private boolean emitExpression(Expression expression) {
        if (expression instanceof LiteralNode) {
            PyValue value = literalValue((LiteralNode) expression);
            if (value == null) return false;
            int index = context.getBuilder().addConstant(value);
            context.getBuilder().emit(
                    OpCode.LOAD_CONST,
                    new Operand.ConstOperand(index),
                    span(expression),
                    false);
            return true;
        }
        if (expression instanceof IdentifierNode) {
            return emitName((IdentifierNode) expression, NameOccurrence.Role.LOAD);
        }
        if (expression instanceof FunctionCallNode) {
            return emitFunctionCall((FunctionCallNode) expression);
        }
        if (expression instanceof ListNode) {
            return emitCollection(
                    ((ListNode) expression).getElements(), OpCode.BUILD_LIST, expression);
        }
        if (expression instanceof TupleNode) {
            return emitCollection(
                    ((TupleNode) expression).getElements(), OpCode.BUILD_TUPLE, expression);
        }
        if (expression instanceof SetNode) {
            return emitCollection(
                    ((SetNode) expression).getElements(), OpCode.BUILD_SET, expression);
        }
        if (expression instanceof DictNode) {
            DictNode dict = (DictNode) expression;
            for (DictNode.DictItem item : dict.getItems()) {
                if (!emitExpression(item.getKey()) || !emitExpression(item.getValue())) {
                    return false;
                }
            }
            emitCount(OpCode.BUILD_MAP, dict.getItems().size(), expression);
            return true;
        }
        if (expression instanceof FStringNode) {
            return emitFString((FStringNode) expression);
        }
        if (expression instanceof UnaryOpNode) {
            UnaryOpNode unary = (UnaryOpNode) expression;
            Operand.UnaryOperator operation = unaryOperator(unary.getOperator(), unary);
            if (operation == null || !emitExpression(unary.getOperand())) return false;
            context.getBuilder().emit(
                    OpCode.UNARY_OP,
                    new Operand.UnaryOperatorOperand(operation),
                    span(unary),
                    false);
            return true;
        }
        if (expression instanceof BinaryOpNode) {
            return emitBinaryExpression((BinaryOpNode) expression);
        }
        if (expression instanceof CompareNode) {
            return emitCompare((CompareNode) expression);
        }
        if (expression instanceof AttributeAccessNode) {
            AttributeAccessNode attribute = (AttributeAccessNode) expression;
            if (!emitExpression(attribute.getObject())) return false;
            emitNameOperand(OpCode.LOAD_ATTR, attribute.getAttribute(), expression);
            return true;
        }
        if (expression instanceof SubscriptNode) {
            SubscriptNode subscript = (SubscriptNode) expression;
            if (!emitExpression(subscript.getObject())
                    || !emitExpression(subscript.getIndex())) return false;
            emit(OpCode.BINARY_SUBSCR, expression);
            return true;
        }
        unsupported(expression);
        return false;
    }

    private boolean emitFunctionCall(FunctionCallNode node) {
        if (!emitExpression(node.getFunction())) {
            return false;
        }
        int positionalCount = 0;
        List<String> keywordNames = new java.util.ArrayList<>();
        for (CallArgument argument : node.getArguments()) {
            if (!emitExpression(argument.getValue())) {
                return false;
            }
            if (argument.isPositional()) {
                positionalCount++;
            } else {
                keywordNames.add(argument.getKeywordName());
            }
        }
        context.getBuilder().emit(
                OpCode.CALL,
                new Operand.CallSpec(positionalCount, keywordNames),
                span(node),
                false);
        return true;
    }

    private void emitConstant(
            PyValue value, ASTNode owner, boolean synthetic) {
        int index = context.getBuilder().addConstant(value);
        context.getBuilder().emit(
                OpCode.LOAD_CONST,
                new Operand.ConstOperand(index),
                span(owner),
                synthetic);
    }

    private boolean emitCollection(
            List<Expression> elements, OpCode build, ASTNode owner) {
        for (Expression element : elements) {
            if (!emitExpression(element)) return false;
        }
        emitCount(build, elements.size(), owner);
        return true;
    }

    private boolean emitFString(FStringNode node) {
        for (FStringPart part : node.getParts()) {
            if (part instanceof FStringPart.StringPart) {
                int index = context.getBuilder().addConstant(
                        new PyString(((FStringPart.StringPart) part).getValue()));
                context.getBuilder().emit(
                        OpCode.LOAD_CONST,
                        new Operand.ConstOperand(index),
                        part.getSpan().isKnown() ? part.getSpan() : span(node),
                        false);
            } else if (part instanceof FStringPart.ExpressionPart) {
                Expression value = ((FStringPart.ExpressionPart) part).getExpression();
                if (value == null || !emitExpression(value)) return false;
                context.getBuilder().emit(
                        OpCode.FORMAT_VALUE,
                        part.getSpan().isKnown() ? part.getSpan() : span(value),
                        false);
            } else {
                invalid(node, "Unknown f-string part during code generation");
                return false;
            }
        }
        emitCount(OpCode.BUILD_STRING, node.getParts().size(), node);
        return true;
    }

    private boolean emitBinaryExpression(BinaryOpNode node) {
        if ("and".equals(node.getOperator()) || "or".equals(node.getOperator())) {
            Label end = label("logical_end");
            if (!emitExpression(node.getLeft())) return false;
            jump("and".equals(node.getOperator())
                            ? OpCode.JUMP_IF_FALSE_OR_POP
                            : OpCode.JUMP_IF_TRUE_OR_POP,
                    end,
                    node,
                    true);
            if (!emitExpression(node.getRight())) return false;
            mark(end);
            return true;
        }
        Operand.BinaryOperator operation = binaryOperator(node.getOperator(), node);
        if (operation == null || !emitExpression(node.getLeft())
                || !emitExpression(node.getRight())) return false;
        emitBinary(operation, node);
        return true;
    }

    private boolean emitCompare(CompareNode node) {
        if (node.getComparisonCount() < 1) {
            invalid(node, "Comparison has no operators");
            return false;
        }
        if (!emitExpression(node.getLeft())) return false;
        if (node.getComparisonCount() == 1) {
            if (!emitExpression(node.getComparator(0))) return false;
            return emitCompareOperator(node.getOperator(0), node);
        }

        Label falseCleanup = label("compare_false");
        Label end = label("compare_end");
        for (int i = 0; i < node.getComparisonCount(); i++) {
            if (!emitExpression(node.getComparator(i))) return false;
            if (i + 1 < node.getComparisonCount()) {
                emitCount(OpCode.SWAP, 2, node);
                emitCount(OpCode.COPY, 2, node);
            }
            if (!emitCompareOperator(node.getOperator(i), node)) return false;
            if (i + 1 < node.getComparisonCount()) {
                jump(OpCode.JUMP_IF_FALSE_OR_POP, falseCleanup, node, true);
            }
        }
        jump(OpCode.JUMP, end, node, true);
        mark(falseCleanup);
        emitCount(OpCode.SWAP, 2, node);
        emit(OpCode.POP_TOP, node);
        mark(end);
        return true;
    }

    private boolean emitCompareOperator(CompareNode.CompareOp operation, ASTNode owner) {
        Operand.CompareOperator mapped;
        switch (operation) {
            case EQ: mapped = Operand.CompareOperator.EQ; break;
            case NEQ: mapped = Operand.CompareOperator.NE; break;
            case LT: mapped = Operand.CompareOperator.LT; break;
            case LTE: mapped = Operand.CompareOperator.LE; break;
            case GT: mapped = Operand.CompareOperator.GT; break;
            case GTE: mapped = Operand.CompareOperator.GE; break;
            case IN: mapped = Operand.CompareOperator.IN; break;
            case IS: mapped = Operand.CompareOperator.IS; break;
            default:
                invalid(owner, "Unknown comparison operator: " + operation);
                return false;
        }
        context.getBuilder().emit(
                OpCode.COMPARE_OP,
                new Operand.CompareOperatorOperand(mapped),
                span(owner),
                false);
        return true;
    }

    private boolean emitName(IdentifierNode node, NameOccurrence.Role role) {
        final NameOccurrence occurrence;
        try {
            occurrence = bindings.requireOccurrence(node, role, 0);
        } catch (IllegalArgumentException missing) {
            invalid(node, missing.getMessage());
            return false;
        }
        if (occurrence.getScope() != context.getScope()) {
            invalid(node, "Name occurrence belongs to a different executable scope");
            return false;
        }
        if (role == NameOccurrence.Role.LOAD && occurrence.hasRuntimeSymbol()) {
            RuntimeSymbolSpec runtimeSymbol = occurrence.getRuntimeSymbol();
            if (!runtimeSymbol.isExecutable()) {
                SourceSpan location = occurrence.getSpan();
                reporter.report(Diagnostics.runtimeSymbolUnavailable(
                        runtimeSymbol.qualifiedName(),
                        location.getStartLine(),
                        location.getStartColumn(),
                        diagnosticSource(location)));
                return false;
            }
        }
        return emitResolvedName(occurrence.getResolvedName(), role, node);
    }

    private boolean emitResolvedName(
            ResolvedName resolved, NameOccurrence.Role role, ASTNode owner) {
        OpCode opCode;
        BindingKind kind = resolved.getKind();
        switch (kind) {
            case NAME:
                opCode = nameOp(role, OpCode.LOAD_NAME, OpCode.STORE_NAME, OpCode.DELETE_NAME);
                emitNameOperand(opCode, resolved.getName(), owner);
                return true;
            case GLOBAL:
                opCode = nameOp(role, OpCode.LOAD_GLOBAL, OpCode.STORE_GLOBAL, OpCode.DELETE_GLOBAL);
                emitNameOperand(opCode, resolved.getName(), owner);
                return true;
            case FAST:
                opCode = nameOp(role, OpCode.LOAD_FAST, OpCode.STORE_FAST, OpCode.DELETE_FAST);
                context.getBuilder().emit(
                        opCode,
                        new Operand.LocalSlotOperand(resolved.getSlotIndex()),
                        span(owner),
                        false);
                return true;
            case DEREF:
                opCode = nameOp(role, OpCode.LOAD_DEREF, OpCode.STORE_DEREF, OpCode.DELETE_DEREF);
                context.getBuilder().emit(
                        opCode,
                        new Operand.DerefSlotOperand(resolved.getSlotIndex()),
                        span(owner),
                        false);
                return true;
            default:
                invalid(owner, "Unknown resolved binding kind: " + kind);
                return false;
        }
    }

    private OpCode nameOp(
            NameOccurrence.Role role, OpCode load, OpCode store, OpCode delete) {
        if (role == NameOccurrence.Role.LOAD) return load;
        if (role == NameOccurrence.Role.STORE) return store;
        if (role == NameOccurrence.Role.DELETE) return delete;
        throw new IllegalArgumentException("Unsupported emitted name role: " + role);
    }

    private PyValue literalValue(LiteralNode literal) {
        Object value = literal.getValue();
        switch (literal.getLiteralType()) {
            case NONE:
                return PyNone.INSTANCE;
            case BOOLEAN:
                return PyBool.valueOf((Boolean) value);
            case STRING:
                return new PyString((String) value);
            case FLOAT:
                return new PyFloat(((Number) value).doubleValue());
            case INTEGER:
                if (value instanceof BigInteger) return new PyInt((BigInteger) value);
                if (value instanceof Number) return new PyInt(
                        BigInteger.valueOf(((Number) value).longValue()));
                invalid(literal, "Invalid integer-literal payload");
                return null;
            default:
                invalid(literal, "Unknown literal kind: " + literal.getLiteralType());
                return null;
        }
    }

    private Operand.UnaryOperator unaryOperator(String operator, ASTNode owner) {
        if ("+".equals(operator)) return Operand.UnaryOperator.POSITIVE;
        if ("-".equals(operator)) return Operand.UnaryOperator.NEGATIVE;
        if ("not".equals(operator)) return Operand.UnaryOperator.NOT;
        invalid(owner, "Unknown unary operator: " + operator);
        return null;
    }

    private Operand.BinaryOperator binaryOperator(String operator, ASTNode owner) {
        if ("+".equals(operator)) return Operand.BinaryOperator.ADD;
        if ("-".equals(operator)) return Operand.BinaryOperator.SUBTRACT;
        if ("*".equals(operator)) return Operand.BinaryOperator.MULTIPLY;
        if ("/".equals(operator)) return Operand.BinaryOperator.TRUE_DIVIDE;
        if ("//".equals(operator)) return Operand.BinaryOperator.FLOOR_DIVIDE;
        if ("%".equals(operator)) return Operand.BinaryOperator.MODULO;
        if ("**".equals(operator)) return Operand.BinaryOperator.POWER;
        invalid(owner, "Unknown binary operator: " + operator);
        return null;
    }

    private Operand.BinaryOperator augmentedOperator(String operator, ASTNode owner) {
        if ("+=".equals(operator)) return Operand.BinaryOperator.INPLACE_ADD;
        if ("-=".equals(operator)) return Operand.BinaryOperator.INPLACE_SUBTRACT;
        if ("*=".equals(operator)) return Operand.BinaryOperator.INPLACE_MULTIPLY;
        if ("/=".equals(operator)) return Operand.BinaryOperator.INPLACE_TRUE_DIVIDE;
        invalid(owner, "Unknown augmented-assignment operator: " + operator);
        return null;
    }

    private List<Expression> targetElements(Expression target) {
        if (target instanceof TupleNode) return ((TupleNode) target).getElements();
        if (target instanceof ListNode) return ((ListNode) target).getElements();
        return null;
    }

    private void emitBinary(Operand.BinaryOperator operation, ASTNode owner) {
        context.getBuilder().emit(
                OpCode.BINARY_OP,
                new Operand.BinaryOperatorOperand(operation),
                span(owner),
                false);
    }

    private void emitNameOperand(OpCode opCode, String name, ASTNode owner) {
        int index = context.getBuilder().addName(name);
        context.getBuilder().emit(
                opCode, new Operand.NameOperand(index), span(owner), false);
    }

    private void emitCount(OpCode opCode, int count, ASTNode owner) {
        context.getBuilder().emit(
                opCode, new Operand.CountOperand(count), span(owner), false);
    }

    private void emitTemp(
            OpCode opCode, int slot, ASTNode owner, boolean synthetic) {
        context.getBuilder().emit(
                opCode, new Operand.TempSlotOperand(slot), span(owner), synthetic);
    }

    private void emit(OpCode opCode, ASTNode owner) {
        context.getBuilder().emit(opCode, span(owner), false);
    }

    private Label label(String hint) {
        return context.getBuilder().newLabel(hint);
    }

    private void mark(Label label) {
        context.getBuilder().mark(label);
    }

    private void jump(
            OpCode opCode, Label target, ASTNode owner, boolean synthetic) {
        context.getBuilder().emitJump(opCode, target, span(owner), synthetic);
    }

    private void unsupported(ASTNode node) {
        SourceSpan location = span(node);
        reporter.report(Diagnostics.unsupportedAstNode(
                node.getNodeType(),
                location.getStartLine(),
                location.getStartColumn(),
                diagnosticSource(location)));
    }

    private void invalid(ASTNode node, String message) {
        SourceSpan location = span(node);
        reporter.report(Diagnostics.invalidCodegenContext(
                message == null ? "Invalid code-generation context" : message,
                location.getStartLine(),
                location.getStartColumn(),
                diagnosticSource(location)));
    }

    private SourceSpan span(ASTNode node) {
        return node == null || node.getSourceSpan() == null
                ? SourceSpan.UNKNOWN
                : node.getSourceSpan();
    }

    private String diagnosticSource(SourceSpan span) {
        return span != null && span.isKnown()
                ? span.getSourceFile()
                : sourceFile;
    }
}
