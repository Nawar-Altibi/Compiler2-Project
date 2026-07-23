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

public class ASTPrinter extends ASTBaseVisitor<Void> {

    private final StringBuilder output = new StringBuilder();
    private int depth = 0;
    private final boolean[] lastAtDepth = new boolean[128];

    public ASTPrinter() {}

    public void reset() {
        output.setLength(0);
        depth = 0;
    }

    public String getOutput() {
        return output.toString();
    }

    private void printLine(String text) {
        for (int i = 1; i < depth; i++) {
            output.append(lastAtDepth[i] ? "    " : "│   ");
        }
        if (depth > 0) {
            output.append(lastAtDepth[depth] ? "└── " : "├── ");
        }
        output.append(text).append("\n");
    }

    private void enter(boolean isLast) {
        depth++;
        lastAtDepth[depth] = isLast;
    }

    private void leave() {
        depth--;
    }

    private void visitChild(ASTNode child, boolean isLast) {
        enter(isLast);
        child.accept(this);
        leave();
    }

    private void visitLabeledChild(String label, ASTNode child, boolean isLast) {
        enter(isLast);
        printLine(label);
        enter(true);
        child.accept(this);
        leave();
        leave();
    }

    private void visitBody(String label, List<? extends ASTNode> nodes, boolean isLast) {
        enter(isLast);
        printLine(label);
        for (int i = 0; i < nodes.size(); i++) {
            enter(i == nodes.size() - 1);
            nodes.get(i).accept(this);
            leave();
        }
        leave();
    }

    private String loc(ASTNode node) {
        return node.getLine() > 0 ? " (line " + node.getLine() + ")" : "";
    }

    // ========================================
    // Program
    // ========================================

    @Override
    public Void visitProgram(ProgramNode node) {
        printLine("Program" + loc(node));
        List<Statement> stmts = node.getStatements();
        for (int i = 0; i < stmts.size(); i++) {
            visitChild(stmts.get(i), i == stmts.size() - 1);
        }
        return null;
    }

    // ========================================
    // Simple Statements
    // ========================================

    @Override
    public Void visitAssignment(AssignmentNode node) {
        printLine("Assignment [" + node.getOperator() + "]" + loc(node));
        visitLabeledChild("Target", node.getTarget(), false);
        visitLabeledChild("Value", node.getValue(), true);
        return null;
    }

    @Override
    public Void visitExpressionStatement(ExpressionStatementNode node) {
        printLine("ExpressionStatement" + loc(node));
        visitChild(node.getExpression(), true);
        return null;
    }

    @Override
    public Void visitReturn(ReturnNode node) {
        printLine("Return" + loc(node));
        if (node.hasValue()) {
            visitChild(node.getValue(), true);
        }
        return null;
    }

    @Override
    public Void visitPass(PassNode node) {
        printLine("Pass" + loc(node));
        return null;
    }

    @Override
    public Void visitBreak(BreakNode node) {
        printLine("Break" + loc(node));
        return null;
    }

    @Override
    public Void visitContinue(ContinueNode node) {
        printLine("Continue" + loc(node));
        return null;
    }

    @Override
    public Void visitDel(DelNode node) {
        printLine("Del" + loc(node));
        List<Expression> targets = node.getTargets();
        for (int i = 0; i < targets.size(); i++) {
            visitChild(targets.get(i), i == targets.size() - 1);
        }
        return null;
    }

    @Override
    public Void visitAssert(AssertNode node) {
        boolean hasMsg = node.hasMessage();
        printLine("Assert" + loc(node));
        visitLabeledChild("Test", node.getTest(), !hasMsg);
        if (hasMsg) {
            visitLabeledChild("Message", node.getMessage(), true);
        }
        return null;
    }

    @Override
    public Void visitGlobal(GlobalNode node) {
        printLine("Global [" + String.join(", ", node.getNames()) + "]" + loc(node));
        return null;
    }

    @Override
    public Void visitRaise(RaiseNode node) {
        if (node.isBareRaise()) {
            printLine("Raise (bare)" + loc(node));
        } else {
            boolean hasCause = node.hasCause();
            printLine("Raise" + loc(node));
            visitLabeledChild("Exception", node.getException(), !hasCause);
            if (hasCause) {
                visitLabeledChild("Cause", node.getCause(), true);
            }
        }
        return null;
    }

    // ========================================
    // Import Statements
    // ========================================

    @Override
    public Void visitImport(ImportNode node) {
        String attrs = "Import: " + node.getModuleName();
        if (node.hasAlias()) {
            attrs += " as " + node.getAsName();
        }
        printLine(attrs + loc(node));
        return null;
    }

    @Override
    public Void visitFromImport(FromImportNode node) {
        StringBuilder line = new StringBuilder("FromImport: from " + node.getModuleName() + " import ");
        if (node.isImportAll()) {
            line.append("*");
        } else {
            for (int i = 0; i < node.getItems().size(); i++) {
                if (i > 0) line.append(", ");
                FromImportNode.ImportItem item = node.getItems().get(i);
                line.append(item.getName());
                if (item.hasAlias()) {
                    line.append(" as ").append(item.getAsName());
                }
            }
        }
        printLine(line.toString() + loc(node));
        return null;
    }

    // ========================================
    // Compound Statements
    // ========================================

    @Override
    public Void visitFunctionDef(FunctionDefNode node) {
        StringBuilder header = new StringBuilder("FunctionDef: " + node.getName() + "(");
        List<Parameter> params = node.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) header.append(", ");
            header.append(params.get(i).getName());
            if (params.get(i).hasDefault()) header.append("=...");
        }
        header.append(")");
        printLine(header.toString() + loc(node));

        if (node.hasDecorators()) {
            List<DecoratorNode> decorators = node.getDecorators();
            enter(false);
            printLine("Decorators");
            for (int i = 0; i < decorators.size(); i++) {
                DecoratorNode dec = decorators.get(i);
                boolean isLastDec = i == decorators.size() - 1;
                enter(isLastDec);
                printLine("@");
                visitChild(dec.getExpression(), true);
                leave();
            }
            leave();
        }

        if (node.hasParameters()) {
            enter(false);
            printLine("Parameters");
            for (int i = 0; i < params.size(); i++) {
                Parameter p = params.get(i);
                boolean isLastParam = i == params.size() - 1;
                enter(isLastParam);
                String paramStr = p.getName();
                if (p.hasTypeHint()) paramStr += ": <type>";
                if (p.hasDefault()) paramStr += " = ...";
                printLine("Param: " + paramStr);
                if (p.hasDefault()) {
                    visitLabeledChild("Default", p.getDefaultValue(), true);
                }
                leave();
            }
            leave();
        }

        if (node.hasReturnType()) {
            visitLabeledChild("ReturnType", node.getReturnType(), false);
        }

        visitBody("Body", node.getBody(), true);
        return null;
    }

    @Override
    public Void visitIfStatement(IfStatementNode node) {
        boolean hasElif = node.hasElif();
        boolean hasElse = node.hasElse();
        printLine("If" + loc(node));

        visitLabeledChild("Condition", node.getCondition(), false);
        visitBody("Then", node.getThenBody(), !hasElif && !hasElse);

        if (hasElif) {
            List<IfStatementNode.ElifClause> elifs = node.getElifClauses();
            for (int i = 0; i < elifs.size(); i++) {
                IfStatementNode.ElifClause elif = elifs.get(i);
                boolean isLastBlock = !hasElse && i == elifs.size() - 1;
                enter(isLastBlock);
                printLine("Elif");
                visitLabeledChild("Condition", elif.getCondition(), false);
                visitBody("Body", elif.getBody(), true);
                leave();
            }
        }

        if (hasElse) {
            visitBody("Else", node.getElseBody(), true);
        }

        return null;
    }

    @Override
    public Void visitForStatement(ForStatementNode node) {
        boolean hasElse = node.hasElse();
        printLine("For" + loc(node));
        visitLabeledChild("Target", node.getTarget(), false);
        visitLabeledChild("Iterable", node.getIterable(), false);
        visitBody("Body", node.getBody(), !hasElse);
        if (hasElse) {
            visitBody("Else", node.getElseBody(), true);
        }
        return null;
    }

    @Override
    public Void visitWhileStatement(WhileStatementNode node) {
        boolean hasElse = node.hasElse();
        printLine("While" + loc(node));
        visitLabeledChild("Condition", node.getCondition(), false);
        visitBody("Body", node.getBody(), !hasElse);
        if (hasElse) {
            visitBody("Else", node.getElseBody(), true);
        }
        return null;
    }

    @Override
    public Void visitWithStatement(WithStatementNode node) {
        printLine("With" + loc(node));

        List<WithItem> items = node.getItems();
        enter(false);
        printLine("Items");
        for (int i = 0; i < items.size(); i++) {
            WithItem item = items.get(i);
            boolean isLastItem = i == items.size() - 1;
            enter(isLastItem);
            printLine("WithItem");
            boolean hasAs = item.getAsName() != null;
            visitLabeledChild("Context", item.getContextExpr(), !hasAs);
            if (hasAs) {
                visitLabeledChild("As", item.getAsName(), true);
            }
            leave();
        }
        leave();

        visitBody("Body", node.getBody(), true);
        return null;
    }

    @Override
    public Void visitTryStatement(TryStatementNode node) {
        boolean hasExcept = node.hasExcept();
        boolean hasElse = node.hasElse();
        boolean hasFinally = node.hasFinally();
        printLine("Try" + loc(node));

        visitBody("TryBody", node.getTryBody(), !hasExcept && !hasElse && !hasFinally);

        if (hasExcept) {
            List<ExceptClause> excepts = node.getExceptClauses();
            for (int i = 0; i < excepts.size(); i++) {
                ExceptClause exc = excepts.get(i);
                boolean isLastBlock = !hasElse && !hasFinally && i == excepts.size() - 1;
                enter(isLastBlock);
                if (exc.getExceptionType() != null) {
                    String excLabel = "Except";
                    if (exc.hasAsName()) excLabel += " as " + exc.getAsName();
                    printLine(excLabel);
                    visitLabeledChild("Type", exc.getExceptionType(), false);
                } else {
                    printLine("Except (bare)");
                }
                visitBody("Body", exc.getBody(), true);
                leave();
            }
        }

        if (hasElse) {
            visitBody("Else", node.getElseBody(), !hasFinally);
        }

        if (hasFinally) {
            visitBody("Finally", node.getFinallyBody(), true);
        }

        return null;
    }

    @Override
    public Void visitClassDef(ClassDefNode node) {
        StringBuilder header = new StringBuilder("ClassDef: " + node.getName());
        if (node.hasBases()) {
            header.append(" (bases: ").append(node.getBaseCount()).append(")");
        }
        printLine(header.toString() + loc(node));

        if (node.hasDecorators()) {
            List<DecoratorNode> decorators = node.getDecorators();
            enter(false);
            printLine("Decorators");
            for (int i = 0; i < decorators.size(); i++) {
                DecoratorNode dec = decorators.get(i);
                enter(i == decorators.size() - 1);
                printLine("@");
                visitChild(dec.getExpression(), true);
                leave();
            }
            leave();
        }

        if (node.hasBases()) {
            List<Expression> bases = node.getBases();
            enter(false);
            printLine("Bases");
            for (int i = 0; i < bases.size(); i++) {
                visitChild(bases.get(i), i == bases.size() - 1);
            }
            leave();
        }

        visitBody("Body", node.getBody(), true);
        return null;
    }

    // ========================================
    // Expressions - Operations
    // ========================================

    @Override
    public Void visitBinaryOp(BinaryOpNode node) {
        printLine("BinaryOp [" + node.getOperator() + "]" + loc(node));
        visitLabeledChild("Left", node.getLeft(), false);
        visitLabeledChild("Right", node.getRight(), true);
        return null;
    }

    @Override
    public Void visitUnaryOp(UnaryOpNode node) {
        printLine("UnaryOp [" + node.getOperator() + "]" + loc(node));
        visitChild(node.getOperand(), true);
        return null;
    }

    @Override
    public Void visitCompare(CompareNode node) {
        List<CompareNode.CompareOp> ops = node.getOperators();
        List<Expression> comparators = node.getComparators();
        StringBuilder opsStr = new StringBuilder();
        for (int i = 0; i < ops.size(); i++) {
            if (i > 0) opsStr.append(", ");
            opsStr.append(formatCompareOp(ops.get(i)));
        }
        printLine("Compare [" + opsStr + "]" + loc(node));
        visitLabeledChild("Left", node.getLeft(), false);
        for (int i = 0; i < comparators.size(); i++) {
            visitLabeledChild(formatCompareOp(ops.get(i)) + " Comparator",
                    comparators.get(i), i == comparators.size() - 1);
        }
        return null;
    }

    private String formatCompareOp(CompareNode.CompareOp op) {
        switch (op) {
            case LT:  return "<";
            case LTE: return "<=";
            case GT:  return ">";
            case GTE: return ">=";
            case EQ:  return "==";
            case NEQ: return "!=";
            case IN:  return "in";
            case IS:  return "is";
            default:  return op.toString();
        }
    }

    // ========================================
    // Expressions - Access
    // ========================================

    @Override
    public Void visitFunctionCall(FunctionCallNode node) {
        printLine("FunctionCall" + loc(node));
        List<CallArgument> arguments = node.getArguments();
        visitLabeledChild("Function", node.getFunction(), arguments.isEmpty());

        if (!arguments.isEmpty()) {
            enter(true);
            printLine("Arguments");
            for (int i = 0; i < arguments.size(); i++) {
                CallArgument argument = arguments.get(i);
                String label = argument.isKeyword()
                        ? "Keyword[" + i + "]: " + argument.getKeywordName()
                        : "Positional[" + i + "]";
                visitLabeledChild(
                        label,
                        argument.getValue(),
                        i == arguments.size() - 1);
            }
            leave();
        }

        return null;
    }

    @Override
    public Void visitAttributeAccess(AttributeAccessNode node) {
        printLine("AttributeAccess: ." + node.getAttribute() + loc(node));
        visitChild(node.getObject(), true);
        return null;
    }

    @Override
    public Void visitSubscript(SubscriptNode node) {
        printLine("Subscript" + loc(node));
        visitLabeledChild("Object", node.getObject(), false);
        visitLabeledChild("Index", node.getIndex(), true);
        return null;
    }

    // ========================================
    // Expressions - Atoms
    // ========================================

    @Override
    public Void visitIdentifier(IdentifierNode node) {
        printLine("Identifier: " + node.getName() + loc(node));
        return null;
    }

    @Override
    public Void visitLiteral(LiteralNode node) {
        String valueStr = String.valueOf(node.getValue());
        if (node.getLiteralType() == LiteralNode.LiteralType.STRING) {
            valueStr = "\"" + valueStr + "\"";
        }
        printLine("Literal: " + valueStr + " (" + node.getLiteralType() + ")" + loc(node));
        return null;
    }

    @Override
    public Void visitList(ListNode node) {
        printLine("List [size=" + node.size() + "]" + loc(node));
        List<Expression> elements = node.getElements();
        for (int i = 0; i < elements.size(); i++) {
            visitChild(elements.get(i), i == elements.size() - 1);
        }
        return null;
    }

    @Override
    public Void visitDict(DictNode node) {
        printLine("Dict [size=" + node.size() + "]" + loc(node));
        List<DictNode.DictItem> items = node.getItems();
        for (int i = 0; i < items.size(); i++) {
            DictNode.DictItem item = items.get(i);
            boolean isLastItem = i == items.size() - 1;
            enter(isLastItem);
            printLine("Entry[" + i + "]");
            visitLabeledChild("Key", item.getKey(), false);
            visitLabeledChild("Value", item.getValue(), true);
            leave();
        }
        return null;
    }

    @Override
    public Void visitSet(SetNode node) {
        printLine("Set [size=" + node.size() + "]" + loc(node));
        List<Expression> elements = node.getElements();
        for (int i = 0; i < elements.size(); i++) {
            visitChild(elements.get(i), i == elements.size() - 1);
        }
        return null;
    }

    @Override
    public Void visitTuple(TupleNode node) {
        String implicit = node.hasParentheses() ? "" : ", implicit";
        printLine("Tuple [size=" + node.size() + implicit + "]" + loc(node));
        List<Expression> elements = node.getElements();
        for (int i = 0; i < elements.size(); i++) {
            visitChild(elements.get(i), i == elements.size() - 1);
        }
        return null;
    }

    @Override
    public Void visitFString(FStringNode node) {
        printLine("FString" + loc(node));
        List<FStringPart> parts = node.getParts();
        for (int i = 0; i < parts.size(); i++) {
            FStringPart part = parts.get(i);
            boolean isLastPart = i == parts.size() - 1;
            enter(isLastPart);
            if (part instanceof FStringPart.StringPart) {
                printLine("StringPart: \"" + ((FStringPart.StringPart) part).getValue() + "\"");
            } else if (part instanceof FStringPart.ExpressionPart) {
                printLine("ExpressionPart");
                enter(true);
                ((FStringPart.ExpressionPart) part).getExpression().accept(this);
                leave();
            }
            leave();
        }
        return null;
    }
}
