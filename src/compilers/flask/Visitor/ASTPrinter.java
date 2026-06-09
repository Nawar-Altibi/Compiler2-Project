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

public class ASTPrinter extends ASTBaseVisitor<Void> {

    private int indent = 0;

    private void ind() {
        System.out.print("  ".repeat(Math.max(0, indent)));
    }

    private void printNode(String nodeName, int line, int col, String extra) {
        ind();
        System.out.print(nodeName);
        String loc = formatLocation(line, col);
        if (!loc.isEmpty()) {
            System.out.print(" " + loc);
        }
        if (extra != null && !extra.isEmpty()) {
            System.out.print(" " + extra);
        }
        System.out.println();
    }

    private void printNode(String nodeName, int line, int col) {
        printNode(nodeName, line, col, null);
    }

    private void printLabel(String label) {
        ind();
        System.out.println(label);
    }

    private String formatLocation(int line, int col) {
        if (line <= 0) {
            return "";
        }
        if (col > 0) {
            return "(line=" + line + ", col=" + col + ")";
        }
        return "(line=" + line + ")";
    }

    // ========================================
    // Program
    // ========================================

    @Override
    public Void visitProgram(ProgramNode node) {
        printNode("ProgramNode", node.getLine(), node.getColumn());
        indent++;
        super.visitProgram(node);
        indent--;
        return null;
    }

    // ========================================
    // Simple Statements
    // ========================================

    @Override
    public Void visitAssignment(AssignmentNode node) {
        printNode("AssignmentNode", node.getLine(), node.getColumn(), "operator=" + node.getOperator());
        indent++;
        printLabel("Target:");
        indent++;
        node.getTarget().accept(this);
        indent--;
        printLabel("Value:");
        indent++;
        node.getValue().accept(this);
        indent--;
        indent--;
        return null;
    }

    @Override
    public Void visitExpressionStatement(ExpressionStatementNode node) {
        printNode("ExpressionStatementNode", node.getLine(), node.getColumn());
        indent++;
        super.visitExpressionStatement(node);
        indent--;
        return null;
    }

    @Override
    public Void visitReturn(ReturnNode node) {
        printNode("ReturnNode", node.getLine(), node.getColumn());
        indent++;
        super.visitReturn(node);
        indent--;
        return null;
    }

    @Override
    public Void visitPass(PassNode node) {
        printNode("PassNode", node.getLine(), node.getColumn());
        return null;
    }

    @Override
    public Void visitBreak(BreakNode node) {
        printNode("BreakNode", node.getLine(), node.getColumn());
        return null;
    }

    @Override
    public Void visitContinue(ContinueNode node) {
        printNode("ContinueNode", node.getLine(), node.getColumn());
        return null;
    }

    @Override
    public Void visitAssert(AssertNode node) {
        printNode("AssertNode", node.getLine(), node.getColumn());
        indent++;
        printLabel("Test:");
        indent++;
        node.getTest().accept(this);
        indent--;
        if (node.getMessage() != null) {
            printLabel("Message:");
            indent++;
            node.getMessage().accept(this);
            indent--;
        }
        indent--;
        return null;
    }

    @Override
    public Void visitDel(DelNode node) {
        printNode("DelNode", node.getLine(), node.getColumn());
        indent++;
        for (int i = 0; i < node.getTargets().size(); i++) {
            printLabel("Target[" + i + "]:");
            indent++;
            node.getTargets().get(i).accept(this);
            indent--;
        }
        indent--;
        return null;
    }

    @Override
    public Void visitGlobal(GlobalNode node) {
        printNode("GlobalNode", node.getLine(), node.getColumn(), "names=" + String.join(", ", node.getNames()));
        return null;
    }

    @Override
    public Void visitRaise(RaiseNode node) {
        printNode("RaiseNode", node.getLine(), node.getColumn());
        indent++;
        if (node.getException() != null) {
            printLabel("Exception:");
            indent++;
            node.getException().accept(this);
            indent--;
        }
        if (node.getCause() != null) {
            printLabel("Cause:");
            indent++;
            node.getCause().accept(this);
            indent--;
        }
        indent--;
        return null;
    }

    // ========================================
    // Import Statements
    // ========================================

    @Override
    public Void visitImport(ImportNode node) {
        String attrs = "module=" + node.getModuleName();
        if (node.hasAlias()) {
            attrs += ", as=" + node.getAsName();
        }
        printNode("ImportNode", node.getLine(), node.getColumn(), attrs);
        return null;
    }

    @Override
    public Void visitFromImport(FromImportNode node) {
        StringBuilder attrs = new StringBuilder("from=" + node.getModuleName());
        if (node.isImportAll()) {
            attrs.append(", import=*");
        } else {
            attrs.append(", items=").append(node.getItemCount());
        }
        printNode("FromImportNode", node.getLine(), node.getColumn(), attrs.toString());
        
        // Print individual import items if not import all
        if (!node.isImportAll() && !node.getItems().isEmpty()) {
            indent++;
            printLabel("Items:");
            indent++;
            for (int i = 0; i < node.getItems().size(); i++) {
                FromImportNode.ImportItem item = node.getItems().get(i);
                String itemStr = "name=" + item.getName();
                if (item.hasAlias()) {
                    itemStr += ", as=" + item.getAsName();
                }
                printNode("Item[" + i + "]", 0, 0, itemStr);
            }
            indent--;
            indent--;
        }
        
        return null;
    }

    // ========================================
    // Compound Statements
    // ========================================

    @Override
    public Void visitFunctionDef(FunctionDefNode node) {
        StringBuilder attrs = new StringBuilder("name=" + node.getName());
        attrs.append(", params=").append(node.getParameterCount());
        if (node.hasDecorators()) {
            attrs.append(", decorators=").append(node.getDecorators().size());
        }
        printNode("FunctionDefNode", node.getLine(), node.getColumn(), attrs.toString());
        indent++;

        if (node.hasDecorators()) {
            printLabel("Decorators:");
            indent++;
            for (Decorator decorator : node.getDecorators()) {
                printLabel("Decorator:");
                indent++;
                printLabel("Name:");
                indent++;
                decorator.getName().accept(this);
                indent--;
                
                if (decorator.hasArgs()) {
                    printLabel("Args:");
                    indent++;
                    for (int i = 0; i < decorator.getArgs().size(); i++) {
                        printLabel("Arg[" + i + "]:");
                        indent++;
                        decorator.getArgs().get(i).accept(this);
                        indent--;
                    }
                    indent--;
                }
                
                if (decorator.hasKwargs()) {
                    printLabel("Kwargs:");
                    indent++;
                    for (String key : decorator.getKwargs().keySet()) {
                        printLabel("Kwarg " + key + ":");
                        indent++;
                        decorator.getKwargs().get(key).accept(this);
                        indent--;
                    }
                    indent--;
                }
                indent--;
            }
            indent--;
        }

        if (node.hasParameters()) {
            printLabel("Parameters:");
            indent++;
            for (Parameter param : node.getParameters()) {
                String paramStr = "name=" + param.getName();
                if (param.hasTypeHint()) {
                    paramStr += ", type=" + param.getTypeHint();
                }
                if (param.hasDefault()) {
                    paramStr += ", default=...";
                }
                printNode("Parameter", 0, 0, paramStr);
            }
            indent--;
        }

        if (node.getReturnType() != null) {
            printLabel("ReturnType:");
            indent++;
            node.getReturnType().accept(this);
            indent--;
        }

        printLabel("Body:");
        indent++;
        for (Statement stmt : node.getBody()) {
            stmt.accept(this);
        }
        indent--;
        indent--;
        return null;
    }

    @Override
    public Void visitIfStatement(IfStatementNode node) {
        printNode("IfStatementNode", node.getLine(), node.getColumn());
        indent++;

        printLabel("Condition:");
        indent++;
        node.getCondition().accept(this);
        indent--;

        printLabel("Then:");
        indent++;
        for (Statement stmt : node.getThenBody()) {
            stmt.accept(this);
        }
        indent--;

        if (node.hasElif()) {
            printLabel("ElifClauses:");
            indent++;
            for (IfStatementNode.ElifClause elif : node.getElifClauses()) {
                printLabel("ElifClause:");
                indent++;
                printLabel("Condition:");
                indent++;
                elif.getCondition().accept(this);
                indent--;
                printLabel("Body:");
                indent++;
                for (Statement stmt : elif.getBody()) {
                    stmt.accept(this);
                }
                indent--;
                indent--;
            }
            indent--;
        }

        if (node.hasElse()) {
            printLabel("Else:");
            indent++;
            for (Statement stmt : node.getElseBody()) {
                stmt.accept(this);
            }
            indent--;
        }

        indent--;
        return null;
    }

    @Override
    public Void visitForStatement(ForStatementNode node) {
        printNode("ForStatementNode", node.getLine(), node.getColumn());
        indent++;

        printLabel("Target:");
        indent++;
        node.getTarget().accept(this);
        indent--;

        printLabel("Iterable:");
        indent++;
        node.getIterable().accept(this);
        indent--;

        printLabel("Body:");
        indent++;
        for (Statement stmt : node.getBody()) {
            stmt.accept(this);
        }
        indent--;

        if (node.hasElse()) {
            printLabel("Else:");
            indent++;
            for (Statement stmt : node.getElseBody()) {
                stmt.accept(this);
            }
            indent--;
        }

        indent--;
        return null;
    }

    @Override
    public Void visitWhileStatement(WhileStatementNode node) {
        printNode("WhileStatementNode", node.getLine(), node.getColumn());
        indent++;

        printLabel("Condition:");
        indent++;
        node.getCondition().accept(this);
        indent--;

        printLabel("Body:");
        indent++;
        for (Statement stmt : node.getBody()) {
            stmt.accept(this);
        }
        indent--;

        if (node.hasElse()) {
            printLabel("Else:");
            indent++;
            for (Statement stmt : node.getElseBody()) {
                stmt.accept(this);
            }
            indent--;
        }

        indent--;
        return null;
    }

    @Override
    public Void visitWithStatement(WithStatementNode node) {
        printNode("WithStatementNode", node.getLine(), node.getColumn());
        indent++;

        printLabel("Items:");
        indent++;
        for (WithItem item : node.getItems()) {
            printLabel("WithItem:");
            indent++;
            printLabel("Context:");
            indent++;
            item.getContextExpr().accept(this);
            indent--;
            if (item.getAsName() != null) {
                printLabel("As:");
                indent++;
                item.getAsName().accept(this);
                indent--;
            }
            indent--;
        }
        indent--;

        printLabel("Body:");
        indent++;
        for (Statement stmt : node.getBody()) {
            stmt.accept(this);
        }
        indent--;
        indent--;
        return null;
    }

    @Override
    public Void visitTryStatement(TryStatementNode node) {
        printNode("TryStatementNode", node.getLine(), node.getColumn());
        indent++;

        printLabel("Try:");
        indent++;
        for (Statement stmt : node.getTryBody()) {
            stmt.accept(this);
        }
        indent--;

        if (node.hasExcept()) {
            printLabel("ExceptClauses:");
            indent++;
            for (ExceptClause except : node.getExceptClauses()) {
                printLabel("ExceptClause:");
                indent++;
                if (except.getExceptionType() != null) {
                    printLabel("ExceptionType:");
                    indent++;
                    except.getExceptionType().accept(this);
                    indent--;
                }
                if (except.hasAsName()) {
                    printLabel("As:");
                    indent++;
                    printNode("IdentifierNode", 0, 0, "name=" + except.getAsName());
                    indent--;
                }
                printLabel("Body:");
                indent++;
                for (Statement stmt : except.getBody()) {
                    stmt.accept(this);
                }
                indent--;
                indent--;
            }
            indent--;
        }

        if (node.hasElse()) {
            printLabel("Else:");
            indent++;
            for (Statement stmt : node.getElseBody()) {
                stmt.accept(this);
            }
            indent--;
        }

        if (node.hasFinally()) {
            printLabel("Finally:");
            indent++;
            for (Statement stmt : node.getFinallyBody()) {
                stmt.accept(this);
            }
            indent--;
        }

        indent--;
        return null;
    }

    @Override
    public Void visitClassDef(ClassDefNode node) {
        StringBuilder attrs = new StringBuilder("name=" + node.getName());
        if (node.hasBases()) {
            attrs.append(", bases=").append(node.getBaseCount());
        }
        if (node.hasDecorators()) {
            attrs.append(", decorators=").append(node.getDecorators().size());
        }
        printNode("ClassDefNode", node.getLine(), node.getColumn(), attrs.toString());
        indent++;

        if (node.hasDecorators()) {
            printLabel("Decorators:");
            indent++;
            for (Decorator decorator : node.getDecorators()) {
                printLabel("Decorator:");
                indent++;
                printLabel("Name:");
                indent++;
                decorator.getName().accept(this);
                indent--;
                
                if (decorator.hasArgs()) {
                    printLabel("Args:");
                    indent++;
                    for (int i = 0; i < decorator.getArgs().size(); i++) {
                        printLabel("Arg[" + i + "]:");
                        indent++;
                        decorator.getArgs().get(i).accept(this);
                        indent--;
                    }
                    indent--;
                }
                
                if (decorator.hasKwargs()) {
                    printLabel("Kwargs:");
                    indent++;
                    for (String key : decorator.getKwargs().keySet()) {
                        printLabel("Kwarg " + key + ":");
                        indent++;
                        decorator.getKwargs().get(key).accept(this);
                        indent--;
                    }
                    indent--;
                }
                indent--;
            }
            indent--;
        }

        if (node.hasBases()) {
            printLabel("Bases:");
            indent++;
            for (Expression base : node.getBases()) {
                base.accept(this);
            }
            indent--;
        }

        printLabel("Body:");
        indent++;
        for (Statement stmt : node.getBody()) {
            stmt.accept(this);
        }
        indent--;
        indent--;
        return null;
    }

    // ========================================
    // Expressions - Operations
    // ========================================

    @Override
    public Void visitBinaryOp(BinaryOpNode node) {
        printNode("BinaryOpNode", node.getLine(), node.getColumn(), "operator=" + node.getOperator());
        indent++;
        printLabel("Left:");
        indent++;
        node.getLeft().accept(this);
        indent--;
        printLabel("Right:");
        indent++;
        node.getRight().accept(this);
        indent--;
        indent--;
        return null;
    }

    @Override
    public Void visitUnaryOp(UnaryOpNode node) {
        printNode("UnaryOpNode", node.getLine(), node.getColumn(), "operator=" + node.getOperator());
        indent++;
        printLabel("Operand:");
        indent++;
        node.getOperand().accept(this);
        indent--;
        indent--;
        return null;
    }

    @Override
    public Void visitCompare(CompareNode node) {
        printNode("CompareNode", node.getLine(), node.getColumn());
        indent++;

        // Left expression
        printLabel("Left:");
        indent++;
        node.getLeft().accept(this);
        indent--;

        // Print operators and comparators in sequence
        for (int i = 0; i < node.getComparisonCount(); i++) {
            // Print operator
            String opStr = node.getOperators().get(i).toString();
            // Convert enum to readable format
            switch (node.getOperators().get(i)) {
                case LT:
                    opStr = "<";
                    break;
                case LTE:
                    opStr = "<=";
                    break;
                case GT:
                    opStr = ">";
                    break;
                case GTE:
                    opStr = ">=";
                    break;
                case EQ:
                    opStr = "==";
                    break;
                case NEQ:
                    opStr = "!=";
                    break;
                case IN:
                    opStr = "in";
                    break;
                case IS:
                    opStr = "is";
                    break;
            }
            printLabel("Operator: " + opStr);

            // Print comparator
            printLabel("Comparator:");
            indent++;
            node.getComparators().get(i).accept(this);
            indent--;
        }

        indent--;
        return null;
    }

    // ========================================
    // Expressions - Access
    // ========================================

    @Override
    public Void visitFunctionCall(FunctionCallNode node) {
        printNode("FunctionCallNode", node.getLine(), node.getColumn(), "args=" + node.getArgs().size() + ", kwargs=" + node.getKwargs().size());
        indent++;
        printLabel("Function:");
        indent++;
        node.getFunction().accept(this);
        indent--;
        if (!node.getArgs().isEmpty()) {
            printLabel("Args:");
            indent++;
            for (int i = 0; i < node.getArgs().size(); i++) {
                printLabel("Arg[" + i + "]:");
                indent++;
                node.getArgs().get(i).accept(this);
                indent--;
            }
            indent--;
        }
        if (!node.getKwargs().isEmpty()) {
            printLabel("Kwargs:");
            indent++;
            for (String key : node.getKwargs().keySet()) {
                printLabel("Kwarg " + key + ":");
                indent++;
                node.getKwargs().get(key).accept(this);
                indent--;
            }
            indent--;
        }
        indent--;
        return null;
    }

    @Override
    public Void visitAttributeAccess(AttributeAccessNode node) {
        printNode("AttributeAccessNode", node.getLine(), node.getColumn(), "attribute=" + node.getAttribute());
        indent++;
        printLabel("Object:");
        indent++;
        node.getObject().accept(this);
        indent--;
        indent--;
        return null;
    }

    @Override
    public Void visitSubscript(SubscriptNode node) {
        printNode("SubscriptNode", node.getLine(), node.getColumn());
        indent++;
        printLabel("Object:");
        indent++;
        node.getObject().accept(this);
        indent--;
        printLabel("Index:");
        indent++;
        node.getIndex().accept(this);
        indent--;
        indent--;
        return null;
    }

    // ========================================
    // Expressions - Atoms
    // ========================================

    @Override
    public Void visitIdentifier(IdentifierNode node) {
        printNode("IdentifierNode", node.getLine(), node.getColumn(), "name=" + node.getName());
        return null;
    }

    @Override
    public Void visitLiteral(LiteralNode node) {
        String valueStr = String.valueOf(node.getValue());
        if (node.getLiteralType() == LiteralNode.LiteralType.STRING) {
            valueStr = "\"" + valueStr + "\"";
        }
        printNode("LiteralNode", node.getLine(), node.getColumn(), "type=" + node.getLiteralType() + ", value=" + valueStr);
        return null;
    }

    @Override
    public Void visitList(ListNode node) {
        printNode("ListNode", node.getLine(), node.getColumn(), "size=" + node.size());
        indent++;
        for (int i = 0; i < node.getElements().size(); i++) {
            printLabel("Element[" + i + "]:");
            indent++;
            node.getElements().get(i).accept(this);
            indent--;
        }
        indent--;
        return null;
    }

    @Override
    public Void visitDict(DictNode node) {
        printNode("DictNode", node.getLine(), node.getColumn(), "size=" + node.size());
        indent++;
        for (int i = 0; i < node.getItems().size(); i++) {
            DictNode.DictItem item = node.getItems().get(i);
            printLabel("Item[" + i + "]:");
            indent++;
            printLabel("Key:");
            indent++;
            item.getKey().accept(this);
            indent--;
            printLabel("Value:");
            indent++;
            item.getValue().accept(this);
            indent--;
            indent--;
        }
        indent--;
        return null;
    }

    @Override
    public Void visitSet(SetNode node) {
        printNode("SetNode", node.getLine(), node.getColumn(), "size=" + node.size());
        indent++;
        for (int i = 0; i < node.getElements().size(); i++) {
            printLabel("Element[" + i + "]:");
            indent++;
            node.getElements().get(i).accept(this);
            indent--;
        }
        indent--;
        return null;
    }

    @Override
    public Void visitTuple(TupleNode node) {
        StringBuilder attrs = new StringBuilder("size=" + node.size());
        if (!node.hasParentheses()) {
            attrs.append(", implicit=true");
        }
        // location handled below
        printNode("TupleNode", node.getLine(), node.getColumn(), attrs.toString());
        indent++;
        for (int i = 0; i < node.getElements().size(); i++) {
            printLabel("Element[" + i + "]:");
            indent++;
            node.getElements().get(i).accept(this);
            indent--;
        }
        indent--;
        return null;
    }

    @Override
    public Void visitFString(FStringNode node) {
        printNode("FStringNode", node.getLine(), node.getColumn());
        indent++;
        printLabel("Parts:");
        indent++;
        for (int i = 0; i < node.getParts().size(); i++) {
            FStringPart part = node.getParts().get(i);
            printLabel("Part[" + i + "]:");
            indent++;
            if (part instanceof FStringPart.StringPart) {
                FStringPart.StringPart strPart = (FStringPart.StringPart) part;
                printLabel("Type: StringPart");
                printLabel("Value: \"" + strPart.getValue() + "\"");
            } else if (part instanceof FStringPart.ExpressionPart) {
                FStringPart.ExpressionPart exprPart = (FStringPart.ExpressionPart) part;
                printLabel("Type: ExpressionPart");
                printLabel("Expression:");
                indent++;
                exprPart.getExpression().accept(this);
                indent--;
            }
            indent--;
        }
        indent--;
        indent--;
        return null;
    }
}
