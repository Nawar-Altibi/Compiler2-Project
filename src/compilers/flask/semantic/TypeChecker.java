package compilers.flask.semantic;

import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.SymbolTable.SymbolEntry;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.SymbolTable.SymbolType;
import compilers.flask.ast.nodes.Expression;
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
import compilers.flask.ast.nodes.statements.compound.ForStatementNode;
import compilers.flask.ast.nodes.statements.simple.AssignmentNode;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Lightweight Python-aware type checks over the fully-built symbol table. */
public final class TypeChecker extends ScopedSemanticVisitor<SymbolType> {
    private final Map<SymbolEntry, SymbolType> observedTypes = new IdentityHashMap<>();

    public TypeChecker(
            SymbolTable symbolTable,
            DiagnosticReporter reporter,
            String sourceFile) {
        super(symbolTable, reporter, sourceFile);
    }

    @Override
    protected SymbolType defaultResult() {
        return SymbolType.UNKNOWN;
    }

    @Override
    protected SymbolType functionDefinitionResult(SymbolType bodyResult) {
        return SymbolType.FUNCTION;
    }

    @Override
    protected SymbolType classDefinitionResult(SymbolType bodyResult) {
        return SymbolType.CLASS;
    }

    @Override
    public SymbolType visitAssignment(AssignmentNode node) {
        if (node.isAugmented()) {
            SymbolType targetType = safeType(node.getTarget().accept(this));
            SymbolType valueType = safeType(node.getValue().accept(this));
            SymbolType resultType = evaluateBinary(
                    targetType,
                    node.getOperator().substring(0, node.getOperator().length() - 1),
                    valueType,
                    node.getLine(),
                    node.getColumn());
            rememberTargetType(node.getTarget(), resultType);
            return SymbolType.UNKNOWN;
        }

        SymbolType valueType = safeType(node.getValue().accept(this));
        visitStoreAddress(node.getTarget());
        checkAndRememberAssignment(node, valueType);
        return SymbolType.UNKNOWN;
    }

    @Override
    public SymbolType visitForStatement(ForStatementNode node) {
        SymbolType iterableType = safeType(node.getIterable().accept(this));
        rememberTargetType(node.getTarget(), inferElementType(node.getIterable(), iterableType));
        visitStatements(node.getBody());
        if (node.hasElse()) {
            visitStatements(node.getElseBody());
        }
        return SymbolType.UNKNOWN;
    }

    private void checkAndRememberAssignment(AssignmentNode node, SymbolType valueType) {
        if (!(node.getTarget() instanceof IdentifierNode)) {
            return;
        }

        String name = ((IdentifierNode) node.getTarget()).getName();
        SymbolEntry entry = currentScope.lookup(name);
        if (entry == null) {
            return;
        }

        SymbolType expected = observedTypes.get(entry);
        if (isConcrete(expected)
                && isConcrete(valueType)
                && !isCompatible(expected, valueType)) {
            reporter.report(Diagnostics.typeMismatch(
                    name,
                    expected.toString(),
                    valueType.toString(),
                    node.getLine(),
                    node.getColumn(),
                    sourceFile));
        }

        // Track the latest concrete runtime binding.  Reassignment is only a
        // warning in Python; retaining an older type here would create
        // cascading false TYPE_ERROR diagnostics in later expressions.
        if (isConcrete(valueType)) {
            observedTypes.put(entry, valueType);
        }
    }

    @Override
    public SymbolType visitBinaryOp(BinaryOpNode node) {
        SymbolType leftType = safeType(node.getLeft().accept(this));
        SymbolType rightType = safeType(node.getRight().accept(this));
        if (isZeroDivisor(node.getOperator(), node.getRight(), leftType, rightType)) {
            reporter.report(Diagnostics.divisionByZero(
                    node.getOperator(),
                    node.getLine(),
                    node.getColumn(),
                    sourceFile));
            return SymbolType.UNKNOWN;
        }
        return evaluateBinary(
                leftType,
                node.getOperator(),
                rightType,
                node.getLine(),
                node.getColumn());
    }

    private boolean isZeroDivisor(
            String operator,
            Expression right,
            SymbolType leftType,
            SymbolType rightType) {
        if (!("/".equals(operator) || "//".equals(operator) || "%".equals(operator))
                || !isNumeric(leftType)
                || !isNumeric(rightType)
                || !(right instanceof LiteralNode)) {
            return false;
        }
        Object value = ((LiteralNode) right).getValue();
        if (value instanceof Number) {
            return Double.compare(((Number) value).doubleValue(), 0.0d) == 0;
        }
        return Boolean.FALSE.equals(value);
    }

    private SymbolType evaluateBinary(
            SymbolType leftType,
            String operator,
            SymbolType rightType,
            int line,
            int column) {
        if (leftType == SymbolType.UNKNOWN || rightType == SymbolType.UNKNOWN) {
            return SymbolType.UNKNOWN;
        }

        if ("and".equals(operator) || "or".equals(operator)) {
            return leftType == rightType ? leftType : SymbolType.UNKNOWN;
        }

        if (!isArithmetic(operator)) {
            return SymbolType.UNKNOWN;
        }

        if (isNumeric(leftType) && isNumeric(rightType)) {
            if ("/".equals(operator)
                    || leftType == SymbolType.FLOAT
                    || rightType == SymbolType.FLOAT) {
                return SymbolType.FLOAT;
            }
            return SymbolType.INTEGER;
        }

        if ("+".equals(operator) && leftType == rightType) {
            if (leftType == SymbolType.STRING
                    || leftType == SymbolType.LIST
                    || leftType == SymbolType.TUPLE) {
                return leftType;
            }
        }

        if ("*".equals(operator)) {
            if (isSequence(leftType) && isIntegerLike(rightType)) {
                return leftType;
            }
            if (isIntegerLike(leftType) && isSequence(rightType)) {
                return rightType;
            }
        }

        if ("%".equals(operator) && leftType == SymbolType.STRING) {
            return SymbolType.STRING;
        }

        reporter.report(Diagnostics.typeError(
                "Cannot apply operator '" + operator + "' to "
                        + leftType + " and " + rightType,
                line,
                column,
                sourceFile));
        return SymbolType.UNKNOWN;
    }

    @Override
    public SymbolType visitUnaryOp(UnaryOpNode node) {
        SymbolType operandType = safeType(node.getOperand().accept(this));
        String operator = node.getOperator();
        if ("not".equals(operator)) {
            return SymbolType.BOOLEAN;
        }
        if (("+".equals(operator) || "-".equals(operator))
                && operandType != SymbolType.UNKNOWN
                && !isNumeric(operandType)) {
            reporter.report(Diagnostics.typeError(
                    "Cannot apply unary operator '" + operator + "' to " + operandType,
                    node.getLine(),
                    node.getColumn(),
                    sourceFile));
            return SymbolType.UNKNOWN;
        }
        return operandType;
    }

    @Override
    public SymbolType visitCompare(CompareNode node) {
        node.getLeft().accept(this);
        for (Expression comparator : node.getComparators()) {
            comparator.accept(this);
        }
        return SymbolType.BOOLEAN;
    }

    @Override
    public SymbolType visitIdentifier(IdentifierNode node) {
        SymbolEntry entry = currentScope.lookup(node.getName());
        if (entry == null) {
            return SymbolType.UNKNOWN;
        }
        SymbolType observed = observedTypes.get(entry);
        return observed == null ? safeType(entry.getType()) : observed;
    }

    @Override
    public SymbolType visitLiteral(LiteralNode node) {
        return SymbolType.fromLiteralType(node.getLiteralType());
    }

    @Override
    public SymbolType visitList(ListNode node) {
        for (Expression element : node.getElements()) {
            element.accept(this);
        }
        return SymbolType.LIST;
    }

    @Override
    public SymbolType visitDict(DictNode node) {
        for (DictNode.DictItem item : node.getItems()) {
            item.getKey().accept(this);
            item.getValue().accept(this);
        }
        return SymbolType.DICT;
    }

    @Override
    public SymbolType visitTuple(TupleNode node) {
        for (Expression element : node.getElements()) {
            element.accept(this);
        }
        return SymbolType.TUPLE;
    }

    @Override
    public SymbolType visitSet(SetNode node) {
        for (Expression element : node.getElements()) {
            element.accept(this);
        }
        return SymbolType.SET;
    }

    @Override
    public SymbolType visitFString(FStringNode node) {
        for (FStringPart part : node.getParts()) {
            if (part instanceof FStringPart.ExpressionPart) {
                ((FStringPart.ExpressionPart) part).getExpression().accept(this);
            }
        }
        return SymbolType.STRING;
    }

    @Override
    public SymbolType visitFunctionCall(FunctionCallNode node) {
        node.getFunction().accept(this);
        for (CallArgument argument : node.getArguments()) {
            argument.getValue().accept(this);
        }

        if (node.getFunction() instanceof IdentifierNode) {
            return builtinCallResult(((IdentifierNode) node.getFunction()).getName());
        }
        return SymbolType.UNKNOWN;
    }

    @Override
    public SymbolType visitAttributeAccess(AttributeAccessNode node) {
        node.getObject().accept(this);
        return SymbolType.UNKNOWN;
    }

    @Override
    public SymbolType visitSubscript(SubscriptNode node) {
        SymbolType objectType = safeType(node.getObject().accept(this));
        node.getIndex().accept(this);
        return objectType == SymbolType.STRING ? SymbolType.STRING : SymbolType.UNKNOWN;
    }

    private SymbolType builtinCallResult(String name) {
        if ("str".equals(name)) {
            return SymbolType.STRING;
        }
        if ("int".equals(name)) {
            return SymbolType.INTEGER;
        }
        if ("float".equals(name)) {
            return SymbolType.FLOAT;
        }
        if ("bool".equals(name)) {
            return SymbolType.BOOLEAN;
        }
        if ("list".equals(name)) {
            return SymbolType.LIST;
        }
        if ("dict".equals(name)) {
            return SymbolType.DICT;
        }
        if ("set".equals(name)) {
            return SymbolType.SET;
        }
        if ("tuple".equals(name)) {
            return SymbolType.TUPLE;
        }
        return SymbolType.UNKNOWN;
    }

    private SymbolType inferElementType(Expression iterable, SymbolType iterableType) {
        List<Expression> elements = null;
        if (iterable instanceof ListNode) {
            elements = ((ListNode) iterable).getElements();
        } else if (iterable instanceof TupleNode) {
            elements = ((TupleNode) iterable).getElements();
        } else if (iterable instanceof SetNode) {
            elements = ((SetNode) iterable).getElements();
        }

        if (elements != null && !elements.isEmpty()) {
            SymbolType common = staticType(elements.get(0));
            for (int i = 1; i < elements.size(); i++) {
                if (common != staticType(elements.get(i))) {
                    return SymbolType.UNKNOWN;
                }
            }
            return common;
        }
        return iterableType == SymbolType.STRING ? SymbolType.STRING : SymbolType.UNKNOWN;
    }

    private SymbolType staticType(Expression expression) {
        if (expression instanceof LiteralNode) {
            return SymbolType.fromLiteralType(((LiteralNode) expression).getLiteralType());
        }
        if (expression instanceof IdentifierNode) {
            return visitIdentifier((IdentifierNode) expression);
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
        if (expression instanceof FStringNode) {
            return SymbolType.STRING;
        }
        return SymbolType.UNKNOWN;
    }

    private void rememberTargetType(Expression target, SymbolType type) {
        if (target instanceof IdentifierNode) {
            SymbolEntry entry = currentScope.lookup(((IdentifierNode) target).getName());
            if (entry != null && isConcrete(type) && !observedTypes.containsKey(entry)) {
                observedTypes.put(entry, type);
            }
            return;
        }
        if (target instanceof TupleNode) {
            for (Expression element : ((TupleNode) target).getElements()) {
                rememberTargetType(element, SymbolType.UNKNOWN);
            }
        } else if (target instanceof ListNode) {
            for (Expression element : ((ListNode) target).getElements()) {
                rememberTargetType(element, SymbolType.UNKNOWN);
            }
        }
    }

    private void visitStoreAddress(Expression target) {
        if (target instanceof AttributeAccessNode) {
            ((AttributeAccessNode) target).getObject().accept(this);
        } else if (target instanceof SubscriptNode) {
            SubscriptNode subscript = (SubscriptNode) target;
            subscript.getObject().accept(this);
            subscript.getIndex().accept(this);
        }
    }

    private SymbolType safeType(SymbolType type) {
        return type == null ? SymbolType.UNKNOWN : type;
    }

    private boolean isConcrete(SymbolType type) {
        return type != null
                && type != SymbolType.UNKNOWN
                && type != SymbolType.VARIABLE
                && type != SymbolType.FUNCTION
                && type != SymbolType.METHOD
                && type != SymbolType.CLASS
                && type != SymbolType.MODULE;
    }

    private boolean isCompatible(SymbolType expected, SymbolType received) {
        return expected == received || (isNumeric(expected) && isNumeric(received));
    }

    private boolean isNumeric(SymbolType type) {
        return type == SymbolType.INTEGER
                || type == SymbolType.FLOAT
                || type == SymbolType.BOOLEAN;
    }

    private boolean isIntegerLike(SymbolType type) {
        return type == SymbolType.INTEGER || type == SymbolType.BOOLEAN;
    }

    private boolean isSequence(SymbolType type) {
        return type == SymbolType.STRING
                || type == SymbolType.LIST
                || type == SymbolType.TUPLE;
    }

    private boolean isArithmetic(String operator) {
        return "+".equals(operator)
                || "-".equals(operator)
                || "*".equals(operator)
                || "/".equals(operator)
                || "//".equals(operator)
                || "%".equals(operator)
                || "**".equals(operator);
    }
}
