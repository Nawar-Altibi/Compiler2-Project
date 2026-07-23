package compilers.flask.Visitor;

import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.expressions.access.*;
import compilers.flask.ast.nodes.expressions.atoms.*;
import compilers.flask.ast.nodes.expressions.operations.*;
import compilers.flask.ast.nodes.helpers.*;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.ast.nodes.statements.compound.*;
import compilers.flask.ast.nodes.statements.imports.*;
import compilers.flask.ast.nodes.statements.simple.*;

import java.util.List;

public abstract class ASTBaseVisitor<T> implements ASTVisitor<T> {

    // ========================================
    // Helper Methods
    // ========================================

    /**
     * Default return value (can be overridden)
     * By default returns null
     */
    protected T defaultResult() {
        return null;
    }

    /**
     * Combine results from multiple visits
     * Useful for aggregating results from child nodes
     * By default returns the next result
     */
    protected T aggregateResult(T aggregate, T nextResult) {
        return nextResult;
    }

    // ========================================
    // Program
    // ========================================

    @Override
    public T visitProgram(ProgramNode node) {
        T result = defaultResult();
        for (Statement stmt : node.getStatements()) {
            T stmtResult = stmt.accept(this);
            result = aggregateResult(result, stmtResult);
        }
        return result;
    }

    // ========================================
    // Simple Statements
    // ========================================

    @Override
    public T visitAssignment(AssignmentNode node) {
        node.getTarget().accept(this);
        node.getValue().accept(this);
        return defaultResult();
    }

    @Override
    public T visitExpressionStatement(ExpressionStatementNode node) {
        node.getExpression().accept(this);
        return defaultResult();
    }

    @Override
    public T visitReturn(ReturnNode node) {
        if (node.hasValue()) {
            node.getValue().accept(this);
        }
        return defaultResult();
    }

    @Override
    public T visitPass(PassNode node) {
        return defaultResult();
    }

    @Override
    public T visitBreak(BreakNode node) {
        return defaultResult();
    }

    @Override
    public T visitContinue(ContinueNode node) {
        return defaultResult();
    }

    @Override
    public T visitDel(DelNode node) {
        for (Expression target : node.getTargets()) {
            target.accept(this);
        }
        return defaultResult();
    }

    @Override
    public T visitAssert(AssertNode node) {
        node.getTest().accept(this);
        if (node.hasMessage()) {
            node.getMessage().accept(this);
        }
        return defaultResult();
    }

    @Override
    public T visitGlobal(GlobalNode node) {
        // Global names are just strings, nothing to visit
        return defaultResult();
    }

    @Override
    public T visitRaise(RaiseNode node) {
        if (!node.isBareRaise()) {
            node.getException().accept(this);
            if (node.hasCause()) {
                node.getCause().accept(this);
            }
        }
        return defaultResult();
    }

    // ========================================
    // Import Statements
    // ========================================

    @Override
    public T visitImport(ImportNode node) {
        // Import names are strings, nothing to visit
        return defaultResult();
    }

    @Override
    public T visitFromImport(FromImportNode node) {
        // Import names are strings, nothing to visit
        return defaultResult();
    }

    // ========================================
    // Compound Statements
    // ========================================

    @Override
    public T visitFunctionDef(FunctionDefNode node) {
        // Visit decorators
        for (DecoratorNode decorator : node.getDecorators()) {
            decorator.getExpression().accept(this);
        }

        // Visit parameters (default values)
        for (Parameter param : node.getParameters()) {
            if (param.hasDefault()) {
                param.getDefaultValue().accept(this);
            }
            if (param.hasTypeHint()) {
                param.getTypeHint().accept(this);
            }
        }

        // Visit return type hint
        if (node.hasReturnType()) {
            node.getReturnType().accept(this);
        }

        // Visit body
        T result = defaultResult();
        for (Statement stmt : node.getBody()) {
            T stmtResult = stmt.accept(this);
            result = aggregateResult(result, stmtResult);
        }

        return result;
    }

    @Override
    public T visitIfStatement(IfStatementNode node) {
        // Visit main condition
        node.getCondition().accept(this);

        // Visit then body
        T result = defaultResult();
        for (Statement stmt : node.getThenBody()) {
            T stmtResult = stmt.accept(this);
            result = aggregateResult(result, stmtResult);
        }

        // Visit elif clauses
        for (IfStatementNode.ElifClause elifClause : node.getElifClauses()) {
            elifClause.getCondition().accept(this);
            for (Statement stmt : elifClause.getBody()) {
                T stmtResult = stmt.accept(this);
                result = aggregateResult(result, stmtResult);
            }
        }

        // Visit else body
        if (node.hasElse()) {
            for (Statement stmt : node.getElseBody()) {
                T stmtResult = stmt.accept(this);
                result = aggregateResult(result, stmtResult);
            }
        }

        return result;
    }

    @Override
    public T visitForStatement(ForStatementNode node) {
        // Visit target and iterable
        node.getTarget().accept(this);
        node.getIterable().accept(this);

        // Visit body
        T result = defaultResult();
        for (Statement stmt : node.getBody()) {
            T stmtResult = stmt.accept(this);
            result = aggregateResult(result, stmtResult);
        }

        // Visit else body
        if (node.hasElse()) {
            for (Statement stmt : node.getElseBody()) {
                T stmtResult = stmt.accept(this);
                result = aggregateResult(result, stmtResult);
            }
        }

        return result;
    }

    @Override
    public T visitWhileStatement(WhileStatementNode node) {
        // Visit condition
        node.getCondition().accept(this);

        // Visit body
        T result = defaultResult();
        for (Statement stmt : node.getBody()) {
            T stmtResult = stmt.accept(this);
            result = aggregateResult(result, stmtResult);
        }

        // Visit else body
        if (node.hasElse()) {
            for (Statement stmt : node.getElseBody()) {
                T stmtResult = stmt.accept(this);
                result = aggregateResult(result, stmtResult);
            }
        }

        return result;
    }

    @Override
    public T visitWithStatement(WithStatementNode node) {
        // Visit with items
        for (WithItem item : node.getItems()) {
            item.getContextExpr().accept(this);
            if (item.hasAsName()) {
                item.getAsName().accept(this);
            }
        }

        // Visit body
        T result = defaultResult();
        for (Statement stmt : node.getBody()) {
            T stmtResult = stmt.accept(this);
            result = aggregateResult(result, stmtResult);
        }

        return result;
    }

    @Override
    public T visitTryStatement(TryStatementNode node) {
        T result = defaultResult();

        // Visit try body
        for (Statement stmt : node.getTryBody()) {
            T stmtResult = stmt.accept(this);
            result = aggregateResult(result, stmtResult);
        }

        // Visit except clauses
        for (ExceptClause exceptClause : node.getExceptClauses()) {
            if (!exceptClause.isBareExcept()) {
                exceptClause.getExceptionType().accept(this);
            }
            for (Statement stmt : exceptClause.getBody()) {
                T stmtResult = stmt.accept(this);
                result = aggregateResult(result, stmtResult);
            }
        }

        // Visit else body
        if (node.hasElse()) {
            for (Statement stmt : node.getElseBody()) {
                T stmtResult = stmt.accept(this);
                result = aggregateResult(result, stmtResult);
            }
        }

        // Visit finally body
        if (node.hasFinally()) {
            for (Statement stmt : node.getFinallyBody()) {
                T stmtResult = stmt.accept(this);
                result = aggregateResult(result, stmtResult);
            }
        }

        return result;
    }

    @Override
    public T visitClassDef(ClassDefNode node) {
        // Visit decorators
        for (DecoratorNode decorator : node.getDecorators()) {
            decorator.getExpression().accept(this);
        }

        // Visit base classes
        for (Expression base : node.getBases()) {
            base.accept(this);
        }

        // Visit body
        T result = defaultResult();
        for (Statement stmt : node.getBody()) {
            T stmtResult = stmt.accept(this);
            result = aggregateResult(result, stmtResult);
        }

        return result;
    }

    // ========================================
    // Expression Operations
    // ========================================

    @Override
    public T visitBinaryOp(BinaryOpNode node) {
        node.getLeft().accept(this);
        node.getRight().accept(this);
        return defaultResult();
    }
    @Override
    public T visitCompare(CompareNode node) {
        // Visit left expression
        node.getLeft().accept(this);

        // Visit all comparators
        for (Expression comparator : node.getComparators()) {
            comparator.accept(this);
        }

        return defaultResult();
    }


    @Override
    public T visitUnaryOp(UnaryOpNode node) {
        node.getOperand().accept(this);
        return defaultResult();
    }

    // ========================================
    // Expression Atoms
    // ========================================

    @Override
    public T visitIdentifier(IdentifierNode node) {
        // Leaf node, nothing to visit
        return defaultResult();
    }

    @Override
    public T visitLiteral(LiteralNode node) {
        // Leaf node, nothing to visit
        return defaultResult();
    }

    @Override
    public T visitList(ListNode node) {
        for (Expression elem : node.getElements()) {
            elem.accept(this);
        }
        return defaultResult();
    }

    @Override
    public T visitDict(DictNode node) {
        for (DictNode.DictItem item : node.getItems()) {
            item.getKey().accept(this);
            item.getValue().accept(this);
        }
        return defaultResult();
    }

    @Override
    public T visitTuple(TupleNode node) {
        for (Expression elem : node.getElements()) {
            elem.accept(this);
        }
        return defaultResult();
    }

    @Override
    public T visitFString(FStringNode node) {
        for (FStringPart part : node.getParts()) {
            if (part instanceof FStringPart.ExpressionPart) {
                ((FStringPart.ExpressionPart) part).getExpression().accept(this);
            }
        }
        return defaultResult();
    }

    @Override
    public T visitSet(SetNode node) {
        for (Expression elem : node.getElements()) {
            elem.accept(this);
        }
        return defaultResult();
    }

    // ========================================
    // Expression Access
    // ========================================

    @Override
    public T visitAttributeAccess(AttributeAccessNode node) {
        node.getObject().accept(this);
        return defaultResult();
    }

    @Override
    public T visitFunctionCall(FunctionCallNode node) {
        node.getFunction().accept(this);

        for (CallArgument argument : node.getArguments()) {
            argument.getValue().accept(this);
        }

        return defaultResult();
    }

    @Override
    public T visitSubscript(SubscriptNode node) {
        node.getObject().accept(this);
        node.getIndex().accept(this);
        return defaultResult();
    }
}
