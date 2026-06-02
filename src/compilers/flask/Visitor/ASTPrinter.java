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

    private StringBuilder output;
    private int indentLevel;
    private static final String BRANCH = " ├── ";
    private static final String LAST_BRANCH = " └── ";
    private static final String CONTINUE = " │   ";
    private static final String EMPTY = "     ";

    public ASTPrinter() {
        this.output = new StringBuilder();
        this.indentLevel = 0;
    }

    public void reset() {
        this.output = new StringBuilder();
        this.indentLevel = 0;
    }

    public String getOutput() {
        return output.toString();
    }

    private void printNode(String nodeName, String attributes) {
        String indent = getIndent();
        String prefix = indentLevel > 0 ? (isLastChild() ? LAST_BRANCH : BRANCH) : "";
        output.append(prefix);
        output.append(nodeName);
        if (attributes != null && !attributes.isEmpty()) {
            output.append(" ").append(attributes);
        }
        output.append("\n");
    }

    private void printNode(String nodeName) {
        printNode(nodeName, null);
    }

    private String getIndent() {
        // Indentation is handled by the prefix in printNode
        return "";
    }

    private boolean isLastChild() {
        // This is a simplified version - in a real implementation,
        // we'd track the child index in the parent
        return false;
    }

    private boolean isLastChildAtLevel(int level) {
        // Simplified - would need proper tracking
        return false;
    }

    private void increaseIndent() {
        indentLevel++;
    }

    private void decreaseIndent() {
        indentLevel--;
    }

    private String formatLineNumber(int line) {
        return line > 0 ? "(line " + line + ")" : "";
    }

    // ========================================
    // Program
    // ========================================

    @Override
    public Void visitProgram(ProgramNode node) {
        printNode("ProgramNode", formatLineNumber(node.getLine()));
        increaseIndent();
        super.visitProgram(node);
        decreaseIndent();
        return null;
    }

    // ========================================
    // Simple Statements
    // ========================================

    @Override
    public Void visitAssignment(AssignmentNode node) {
        printNode("AssignmentNode", "operator=" + node.getOperator() + " " + formatLineNumber(node.getLine()));
        increaseIndent();
        printNode("Target:");
        increaseIndent();
        node.getTarget().accept(this);
        decreaseIndent();
        printNode("Value:");
        increaseIndent();
        node.getValue().accept(this);
        decreaseIndent();
        decreaseIndent();
        return null;
    }

    @Override
    public Void visitExpressionStatement(ExpressionStatementNode node) {
        printNode("ExpressionStatementNode", formatLineNumber(node.getLine()));
        increaseIndent();
        super.visitExpressionStatement(node);
        decreaseIndent();
        return null;
    }

    @Override
    public Void visitReturn(ReturnNode node) {
        printNode("ReturnNode", formatLineNumber(node.getLine()));
        increaseIndent();
        super.visitReturn(node);
        decreaseIndent();
        return null;
    }

    @Override
    public Void visitPass(PassNode node) {
        printNode("PassNode", formatLineNumber(node.getLine()));
        return null;
    }

    @Override
    public Void visitBreak(BreakNode node) {
        printNode("BreakNode", formatLineNumber(node.getLine()));
        return null;
    }

    @Override
    public Void visitContinue(ContinueNode node) {
        printNode("ContinueNode", formatLineNumber(node.getLine()));
        return null;
    }

    @Override
    public Void visitAssert(AssertNode node) {
        printNode("AssertNode", formatLineNumber(node.getLine()));
        increaseIndent();
        printNode("Test:");
        increaseIndent();
        node.getTest().accept(this);
        decreaseIndent();
        if (node.getMessage() != null) {
            printNode("Message:");
            increaseIndent();
            node.getMessage().accept(this);
            decreaseIndent();
        }
        decreaseIndent();
        return null;
    }

    @Override
    public Void visitDel(DelNode node) {
        printNode("DelNode", formatLineNumber(node.getLine()));
        increaseIndent();
        for (int i = 0; i < node.getTargets().size(); i++) {
            printNode("Target[" + i + "]:");
            increaseIndent();
            node.getTargets().get(i).accept(this);
            decreaseIndent();
        }
        decreaseIndent();
        return null;
    }

    @Override
    public Void visitGlobal(GlobalNode node) {
        printNode("GlobalNode", "names=" + String.join(", ", node.getNames()) + " " + formatLineNumber(node.getLine()));
        return null;
    }

    @Override
    public Void visitRaise(RaiseNode node) {
        printNode("RaiseNode", formatLineNumber(node.getLine()));
        increaseIndent();
        if (node.getException() != null) {
            printNode("Exception:");
            increaseIndent();
            node.getException().accept(this);
            decreaseIndent();
        }
        if (node.getCause() != null) {
            printNode("Cause:");
            increaseIndent();
            node.getCause().accept(this);
            decreaseIndent();
        }
        decreaseIndent();
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
        printNode("ImportNode", attrs + " " + formatLineNumber(node.getLine()));
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
        printNode("FromImportNode", attrs.toString() + " " + formatLineNumber(node.getLine()));
        
        // Print individual import items if not import all
        if (!node.isImportAll() && !node.getItems().isEmpty()) {
            increaseIndent();
            printNode("Items:");
            increaseIndent();
            for (int i = 0; i < node.getItems().size(); i++) {
                FromImportNode.ImportItem item = node.getItems().get(i);
                String itemStr = "name=" + item.getName();
                if (item.hasAlias()) {
                    itemStr += ", as=" + item.getAsName();
                }
                printNode("Item[" + i + "]", itemStr);
            }
            decreaseIndent();
            decreaseIndent();
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
        printNode("FunctionDefNode", attrs.toString() + " " + formatLineNumber(node.getLine()));
        increaseIndent();

        if (node.hasDecorators()) {
            printNode("Decorators:");
            increaseIndent();
            for (Decorator decorator : node.getDecorators()) {
                printNode("Decorator:");
                increaseIndent();
                printNode("Name:");
                increaseIndent();
                decorator.getName().accept(this);
                decreaseIndent();
                
                if (decorator.hasArgs()) {
                    printNode("Args:");
                    increaseIndent();
                    for (int i = 0; i < decorator.getArgs().size(); i++) {
                        printNode("Arg[" + i + "]:");
                        increaseIndent();
                        decorator.getArgs().get(i).accept(this);
                        decreaseIndent();
                    }
                    decreaseIndent();
                }
                
                if (decorator.hasKwargs()) {
                    printNode("Kwargs:");
                    increaseIndent();
                    for (String key : decorator.getKwargs().keySet()) {
                        printNode("Kwarg " + key + ":");
                        increaseIndent();
                        decorator.getKwargs().get(key).accept(this);
                        decreaseIndent();
                    }
                    decreaseIndent();
                }
                decreaseIndent();
            }
            decreaseIndent();
        }

        if (node.hasParameters()) {
            printNode("Parameters:");
            increaseIndent();
            for (Parameter param : node.getParameters()) {
                String paramStr = "name=" + param.getName();
                if (param.hasTypeHint()) {
                    paramStr += ", type=" + param.getTypeHint();
                }
                if (param.hasDefault()) {
                    paramStr += ", default=...";
                }
                printNode("Parameter", paramStr);
            }
            decreaseIndent();
        }

        if (node.getReturnType() != null) {
            printNode("ReturnType:");
            increaseIndent();
            node.getReturnType().accept(this);
            decreaseIndent();
        }

        printNode("Body:");
        increaseIndent();
        for (Statement stmt : node.getBody()) {
            stmt.accept(this);
        }
        decreaseIndent();
        decreaseIndent();
        return null;
    }

    @Override
    public Void visitIfStatement(IfStatementNode node) {
        printNode("IfStatementNode", formatLineNumber(node.getLine()));
        increaseIndent();

        printNode("Condition:");
        increaseIndent();
        node.getCondition().accept(this);
        decreaseIndent();

        printNode("Then:");
        increaseIndent();
        for (Statement stmt : node.getThenBody()) {
            stmt.accept(this);
        }
        decreaseIndent();

        if (node.hasElif()) {
            printNode("ElifClauses:");
            increaseIndent();
            for (IfStatementNode.ElifClause elif : node.getElifClauses()) {
                printNode("ElifClause:");
                increaseIndent();
                printNode("Condition:");
                increaseIndent();
                elif.getCondition().accept(this);
                decreaseIndent();
                printNode("Body:");
                increaseIndent();
                for (Statement stmt : elif.getBody()) {
                    stmt.accept(this);
                }
                decreaseIndent();
                decreaseIndent();
            }
            decreaseIndent();
        }

        if (node.hasElse()) {
            printNode("Else:");
            increaseIndent();
            for (Statement stmt : node.getElseBody()) {
                stmt.accept(this);
            }
            decreaseIndent();
        }

        decreaseIndent();
        return null;
    }

    @Override
    public Void visitForStatement(ForStatementNode node) {
        printNode("ForStatementNode", formatLineNumber(node.getLine()));
        increaseIndent();

        printNode("Target:");
        increaseIndent();
        node.getTarget().accept(this);
        decreaseIndent();

        printNode("Iterable:");
        increaseIndent();
        node.getIterable().accept(this);
        decreaseIndent();

        printNode("Body:");
        increaseIndent();
        for (Statement stmt : node.getBody()) {
            stmt.accept(this);
        }
        decreaseIndent();

        if (node.hasElse()) {
            printNode("Else:");
            increaseIndent();
            for (Statement stmt : node.getElseBody()) {
                stmt.accept(this);
            }
            decreaseIndent();
        }

        decreaseIndent();
        return null;
    }

    @Override
    public Void visitWhileStatement(WhileStatementNode node) {
        printNode("WhileStatementNode", formatLineNumber(node.getLine()));
        increaseIndent();

        printNode("Condition:");
        increaseIndent();
        node.getCondition().accept(this);
        decreaseIndent();

        printNode("Body:");
        increaseIndent();
        for (Statement stmt : node.getBody()) {
            stmt.accept(this);
        }
        decreaseIndent();

        if (node.hasElse()) {
            printNode("Else:");
            increaseIndent();
            for (Statement stmt : node.getElseBody()) {
                stmt.accept(this);
            }
            decreaseIndent();
        }

        decreaseIndent();
        return null;
    }

    @Override
    public Void visitWithStatement(WithStatementNode node) {
        printNode("WithStatementNode", formatLineNumber(node.getLine()));
        increaseIndent();

        printNode("Items:");
        increaseIndent();
        for (WithItem item : node.getItems()) {
            printNode("WithItem:");
            increaseIndent();
            printNode("Context:");
            increaseIndent();
            item.getContextExpr().accept(this);
            decreaseIndent();
            if (item.getAsName() != null) {
                printNode("As:");
                increaseIndent();
                item.getAsName().accept(this);
                decreaseIndent();
            }
            decreaseIndent();
        }
        decreaseIndent();

        printNode("Body:");
        increaseIndent();
        for (Statement stmt : node.getBody()) {
            stmt.accept(this);
        }
        decreaseIndent();
        decreaseIndent();
        return null;
    }

    @Override
    public Void visitTryStatement(TryStatementNode node) {
        printNode("TryStatementNode", formatLineNumber(node.getLine()));
        increaseIndent();

        printNode("Try:");
        increaseIndent();
        for (Statement stmt : node.getTryBody()) {
            stmt.accept(this);
        }
        decreaseIndent();

        if (node.hasExcept()) {
            printNode("ExceptClauses:");
            increaseIndent();
            for (ExceptClause except : node.getExceptClauses()) {
                printNode("ExceptClause:");
                increaseIndent();
                if (except.getExceptionType() != null) {
                    printNode("ExceptionType:");
                    increaseIndent();
                    except.getExceptionType().accept(this);
                    decreaseIndent();
                }
                if (except.hasAsName()) {
                    printNode("As:");
                    increaseIndent();
                    printNode("IdentifierNode", "name=" + except.getAsName());
                    decreaseIndent();
                }
                printNode("Body:");
                increaseIndent();
                for (Statement stmt : except.getBody()) {
                    stmt.accept(this);
                }
                decreaseIndent();
                decreaseIndent();
            }
            decreaseIndent();
        }

        if (node.hasElse()) {
            printNode("Else:");
            increaseIndent();
            for (Statement stmt : node.getElseBody()) {
                stmt.accept(this);
            }
            decreaseIndent();
        }

        if (node.hasFinally()) {
            printNode("Finally:");
            increaseIndent();
            for (Statement stmt : node.getFinallyBody()) {
                stmt.accept(this);
            }
            decreaseIndent();
        }

        decreaseIndent();
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
        printNode("ClassDefNode", attrs.toString() + " " + formatLineNumber(node.getLine()));
        increaseIndent();

        if (node.hasDecorators()) {
            printNode("Decorators:");
            increaseIndent();
            for (Decorator decorator : node.getDecorators()) {
                printNode("Decorator:");
                increaseIndent();
                printNode("Name:");
                increaseIndent();
                decorator.getName().accept(this);
                decreaseIndent();
                
                if (decorator.hasArgs()) {
                    printNode("Args:");
                    increaseIndent();
                    for (int i = 0; i < decorator.getArgs().size(); i++) {
                        printNode("Arg[" + i + "]:");
                        increaseIndent();
                        decorator.getArgs().get(i).accept(this);
                        decreaseIndent();
                    }
                    decreaseIndent();
                }
                
                if (decorator.hasKwargs()) {
                    printNode("Kwargs:");
                    increaseIndent();
                    for (String key : decorator.getKwargs().keySet()) {
                        printNode("Kwarg " + key + ":");
                        increaseIndent();
                        decorator.getKwargs().get(key).accept(this);
                        decreaseIndent();
                    }
                    decreaseIndent();
                }
                decreaseIndent();
            }
            decreaseIndent();
        }

        if (node.hasBases()) {
            printNode("Bases:");
            increaseIndent();
            for (Expression base : node.getBases()) {
                base.accept(this);
            }
            decreaseIndent();
        }

        printNode("Body:");
        increaseIndent();
        for (Statement stmt : node.getBody()) {
            stmt.accept(this);
        }
        decreaseIndent();
        decreaseIndent();
        return null;
    }

    // ========================================
    // Expressions - Operations
    // ========================================

    @Override
    public Void visitBinaryOp(BinaryOpNode node) {
        printNode("BinaryOpNode", "operator=" + node.getOperator() + " " + formatLineNumber(node.getLine()));
        increaseIndent();
        printNode("Left:");
        increaseIndent();
        node.getLeft().accept(this);
        decreaseIndent();
        printNode("Right:");
        increaseIndent();
        node.getRight().accept(this);
        decreaseIndent();
        decreaseIndent();
        return null;
    }

    @Override
    public Void visitUnaryOp(UnaryOpNode node) {
        printNode("UnaryOpNode", "operator=" + node.getOperator() + " " + formatLineNumber(node.getLine()));
        increaseIndent();
        printNode("Operand:");
        increaseIndent();
        node.getOperand().accept(this);
        decreaseIndent();
        decreaseIndent();
        return null;
    }

    @Override
    public Void visitCompare(CompareNode node) {
        printNode("CompareNode", formatLineNumber(node.getLine()));
        increaseIndent();

        // Left expression
        printNode("Left:");
        increaseIndent();
        node.getLeft().accept(this);
        decreaseIndent();

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
            printNode("Operator: " + opStr);

            // Print comparator
            printNode("Comparator:");
            increaseIndent();
            node.getComparators().get(i).accept(this);
            decreaseIndent();
        }

        decreaseIndent();
        return null;
    }

    // ========================================
    // Expressions - Access
    // ========================================

    @Override
    public Void visitFunctionCall(FunctionCallNode node) {
        printNode("FunctionCallNode", "args=" + node.getArgs().size() + ", kwargs=" + node.getKwargs().size() + " "
                + formatLineNumber(node.getLine()));
        increaseIndent();
        printNode("Function:");
        increaseIndent();
        node.getFunction().accept(this);
        decreaseIndent();
        if (!node.getArgs().isEmpty()) {
            printNode("Args:");
            increaseIndent();
            for (int i = 0; i < node.getArgs().size(); i++) {
                printNode("Arg[" + i + "]:");
                increaseIndent();
                node.getArgs().get(i).accept(this);
                decreaseIndent();
            }
            decreaseIndent();
        }
        if (!node.getKwargs().isEmpty()) {
            printNode("Kwargs:");
            increaseIndent();
            for (String key : node.getKwargs().keySet()) {
                printNode("Kwarg " + key + ":");
                increaseIndent();
                node.getKwargs().get(key).accept(this);
                decreaseIndent();
            }
            decreaseIndent();
        }
        decreaseIndent();
        return null;
    }

    @Override
    public Void visitAttributeAccess(AttributeAccessNode node) {
        printNode("AttributeAccessNode", "attribute=" + node.getAttribute() + " " + formatLineNumber(node.getLine()));
        increaseIndent();
        printNode("Object:");
        increaseIndent();
        node.getObject().accept(this);
        decreaseIndent();
        decreaseIndent();
        return null;
    }

    @Override
    public Void visitSubscript(SubscriptNode node) {
        printNode("SubscriptNode", formatLineNumber(node.getLine()));
        increaseIndent();
        printNode("Object:");
        increaseIndent();
        node.getObject().accept(this);
        decreaseIndent();
        printNode("Index:");
        increaseIndent();
        node.getIndex().accept(this);
        decreaseIndent();
        decreaseIndent();
        return null;
    }

    // ========================================
    // Expressions - Atoms
    // ========================================

    @Override
    public Void visitIdentifier(IdentifierNode node) {
        printNode("IdentifierNode", "name=" + node.getName() + " " + formatLineNumber(node.getLine()));
        return null;
    }

    @Override
    public Void visitLiteral(LiteralNode node) {
        String valueStr = String.valueOf(node.getValue());
        if (node.getLiteralType() == LiteralNode.LiteralType.STRING) {
            valueStr = "\"" + valueStr + "\"";
        }
        printNode("LiteralNode",
                "type=" + node.getLiteralType() + ", value=" + valueStr + " " + formatLineNumber(node.getLine()));
        return null;
    }

    @Override
    public Void visitList(ListNode node) {
        printNode("ListNode", "size=" + node.size() + " " + formatLineNumber(node.getLine()));
        increaseIndent();
        for (int i = 0; i < node.getElements().size(); i++) {
            printNode("Element[" + i + "]:");
            increaseIndent();
            node.getElements().get(i).accept(this);
            decreaseIndent();
        }
        decreaseIndent();
        return null;
    }

    @Override
    public Void visitDict(DictNode node) {
        printNode("DictNode", "size=" + node.size() + " " + formatLineNumber(node.getLine()));
        increaseIndent();
        for (int i = 0; i < node.getItems().size(); i++) {
            DictNode.DictItem item = node.getItems().get(i);
            printNode("Item[" + i + "]:");
            increaseIndent();
            printNode("Key:");
            increaseIndent();
            item.getKey().accept(this);
            decreaseIndent();
            printNode("Value:");
            increaseIndent();
            item.getValue().accept(this);
            decreaseIndent();
            decreaseIndent();
        }
        decreaseIndent();
        return null;
    }

    @Override
    public Void visitSet(SetNode node) {
        printNode("SetNode", "size=" + node.size() + " " + formatLineNumber(node.getLine()));
        increaseIndent();
        for (int i = 0; i < node.getElements().size(); i++) {
            printNode("Element[" + i + "]:");
            increaseIndent();
            node.getElements().get(i).accept(this);
            decreaseIndent();
        }
        decreaseIndent();
        return null;
    }

    @Override
    public Void visitTuple(TupleNode node) {
        StringBuilder attrs = new StringBuilder("size=" + node.size());
        if (!node.hasParentheses()) {
            attrs.append(", implicit=true");
        }
        attrs.append(" ").append(formatLineNumber(node.getLine()));
        printNode("TupleNode", attrs.toString());
        increaseIndent();
        for (int i = 0; i < node.getElements().size(); i++) {
            printNode("Element[" + i + "]:");
            increaseIndent();
            node.getElements().get(i).accept(this);
            decreaseIndent();
        }
        decreaseIndent();
        return null;
    }

    @Override
    public Void visitFString(FStringNode node) {
        printNode("FStringNode", formatLineNumber(node.getLine()));
        increaseIndent();
        printNode("Parts:");
        increaseIndent();
        for (int i = 0; i < node.getParts().size(); i++) {
            FStringPart part = node.getParts().get(i);
            printNode("Part[" + i + "]:");
            increaseIndent();
            if (part instanceof FStringPart.StringPart) {
                FStringPart.StringPart strPart = (FStringPart.StringPart) part;
                printNode("Type: StringPart");
                printNode("Value: \"" + strPart.getValue() + "\"");
            } else if (part instanceof FStringPart.ExpressionPart) {
                FStringPart.ExpressionPart exprPart = (FStringPart.ExpressionPart) part;
                printNode("Type: ExpressionPart");
                printNode("Expression:");
                increaseIndent();
                exprPart.getExpression().accept(this);
                decreaseIndent();
            }
            decreaseIndent();
        }
        decreaseIndent();
        decreaseIndent();
        return null;
    }
}
