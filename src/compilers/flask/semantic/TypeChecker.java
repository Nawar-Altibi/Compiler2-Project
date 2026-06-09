package compilers.flask.semantic;

import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.SymbolTable.SymbolEntry;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.SymbolTable.SymbolType;
import compilers.flask.Visitor.ASTBaseVisitor;
import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.expressions.atoms.*;
import compilers.flask.ast.nodes.expressions.operations.*;
import compilers.flask.ast.nodes.expressions.access.*;
import compilers.flask.ast.nodes.statements.simple.AssignmentNode;
import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;
import compilers.flask.ast.nodes.statements.compound.ClassDefNode;
import compilers.flask.ast.nodes.statements.ProgramNode;

public class TypeChecker extends ASTBaseVisitor<SymbolType> {
    private final DiagnosticReporter reporter;
    private final String sourceFile;
    private SymbolTable currentScope;

    public TypeChecker(SymbolTable symbolTable, DiagnosticReporter reporter, String sourceFile) {
        this.reporter = reporter;
        this.sourceFile = sourceFile;
        this.currentScope = symbolTable;
    }

    @Override
    protected SymbolType defaultResult() {
        return SymbolType.UNKNOWN;
    }

    @Override
    public SymbolType visitProgram(ProgramNode node) {
        if (node.getScope() != null) {
            currentScope = node.getScope();
        }
        return super.visitProgram(node);
    }

    @Override
    public SymbolType visitFunctionDef(FunctionDefNode node) {
        SymbolTable previousScope = currentScope;
        if (node.getScope() != null) {
            currentScope = node.getScope();
        }
        super.visitFunctionDef(node);
        currentScope = previousScope;
        return SymbolType.FUNCTION;
    }

    @Override
    public SymbolType visitClassDef(ClassDefNode node) {
        SymbolTable previousScope = currentScope;
        if (node.getScope() != null) {
            currentScope = node.getScope();
        }
        super.visitClassDef(node);
        currentScope = previousScope;
        return SymbolType.CLASS;
    }

    @Override
    public SymbolType visitAssignment(AssignmentNode node) {
        SymbolType valueType = node.getValue().accept(this);
        if (valueType == null) valueType = SymbolType.UNKNOWN;

        if (node.getTarget() instanceof IdentifierNode && currentScope != null) {
            String name = ((IdentifierNode) node.getTarget()).getName();
            SymbolEntry entry = currentScope.lookup(name);
            if (entry != null) {
                SymbolType entryType = entry.getType();
                if (entryType != null && entryType != SymbolType.UNKNOWN && valueType != SymbolType.UNKNOWN) {
                    if (!isCompatible(entryType, valueType)) {
                        reporter.report(Diagnostics.typeMismatch(
                                name, entryType.toString(), valueType.toString(),
                                node.getLine(), node.getColumn(), sourceFile));
                    }
                }
            }
        }
        return SymbolType.UNKNOWN;
    }

    @Override
    public SymbolType visitBinaryOp(BinaryOpNode node) {
        SymbolType leftType = node.getLeft().accept(this);
        SymbolType rightType = node.getRight().accept(this);
        if (leftType == null) leftType = SymbolType.UNKNOWN;
        if (rightType == null) rightType = SymbolType.UNKNOWN;

        String op = node.getOperator();

        if (leftType == SymbolType.UNKNOWN || rightType == SymbolType.UNKNOWN) {
            return SymbolType.UNKNOWN;
        }

        if (isArithmetic(op)) {
            if (!isNumeric(leftType) || !isNumeric(rightType)) {
                if (op.equals("+") && leftType == SymbolType.STRING && rightType == SymbolType.STRING) {
                    return SymbolType.STRING;
                }
                if (op.equals("+") && leftType == SymbolType.LIST && rightType == SymbolType.LIST) {
                    return SymbolType.LIST;
                }

                reporter.report(Diagnostics.typeError(
                        "Cannot apply operator '" + op + "' to " + leftType + " and " + rightType,
                        node.getLine(), node.getColumn(), sourceFile));
                return SymbolType.UNKNOWN;
            }
            return (leftType == SymbolType.FLOAT || rightType == SymbolType.FLOAT) ? SymbolType.FLOAT : SymbolType.INTEGER;
        }

        if (isLogical(op)) {
            return SymbolType.BOOLEAN;
        }

        return SymbolType.UNKNOWN;
    }

    @Override
    public SymbolType visitCompare(CompareNode node) {
        node.getLeft().accept(this);
        for (Expression comp : node.getComparators()) {
            comp.accept(this);
        }
        return SymbolType.BOOLEAN;
    }

    @Override
    public SymbolType visitUnaryOp(UnaryOpNode node) {
        SymbolType type = node.getOperand().accept(this);
        if (type == null) type = SymbolType.UNKNOWN;

        String op = node.getOperator();
        if (op.equals("not")) return SymbolType.BOOLEAN;
        if ((op.equals("+") || op.equals("-")) && type != SymbolType.UNKNOWN && !isNumeric(type)) {
            reporter.report(Diagnostics.typeError(
                    "Cannot apply unary operator '" + op + "' to " + type,
                    node.getLine(), node.getColumn(), sourceFile));
        }
        return type;
    }

    @Override
    public SymbolType visitIdentifier(IdentifierNode node) {
        if (currentScope == null) return SymbolType.UNKNOWN;
        SymbolEntry entry = currentScope.lookup(node.getName());
        if (entry != null && entry.getType() != null) {
            return entry.getType();
        }
        return SymbolType.UNKNOWN;
    }

    @Override
    public SymbolType visitLiteral(LiteralNode node) {
        SymbolType type = SymbolType.fromLiteralType(node.getLiteralType());
        return type != null ? type : SymbolType.UNKNOWN;
    }

    @Override
    public SymbolType visitList(ListNode node) { return SymbolType.LIST; }

    @Override
    public SymbolType visitDict(DictNode node) { return SymbolType.DICT; }

    @Override
    public SymbolType visitTuple(TupleNode node) { return SymbolType.TUPLE; }

    @Override
    public SymbolType visitSet(SetNode node) { return SymbolType.SET; }

    private boolean isCompatible(SymbolType expected, SymbolType received) {
        if (expected == received) return true;
        if (isNumeric(expected) && isNumeric(received)) return true;
        return false;
    }

    private boolean isNumeric(SymbolType type) {
        return type == SymbolType.INTEGER || type == SymbolType.FLOAT;
    }

    private boolean isArithmetic(String op) {
        return op.equals("+") || op.equals("-") || op.equals("*") || op.equals("/") || op.equals("//") || op.equals("%") || op.equals("**");
    }

    private boolean isLogical(String op) {
        return op.equals("and") || op.equals("or");
    }
}
