package compilers.flask.Visitor;

import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.expressions.access.*;
import compilers.flask.ast.nodes.expressions.atoms.*;
import compilers.flask.ast.nodes.expressions.operations.*;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.ast.nodes.statements.compound.*;
import compilers.flask.ast.nodes.statements.imports.*;
import compilers.flask.ast.nodes.statements.simple.*;

public interface ASTVisitor<T> {

    // ========================================
    // Program (1 method)
    // ========================================

    /**
     * Visit the root program node
     */
    T visitProgram(ProgramNode node);

    // ========================================
    // Simple Statements (10 methods)
    // ========================================

    /**
     * Visit assignment statement: x = 5, x += 1
     */
    T visitAssignment(AssignmentNode node);

    /**
     * Visit expression statement: app.run(), print(x)
     */
    T visitExpressionStatement(ExpressionStatementNode node);

    /**
     * Visit return statement: return x, return x, y
     */
    T visitReturn(ReturnNode node);

    /**
     * Visit pass statement
     */
    T visitPass(PassNode node);

    /**
     * Visit break statement
     */
    T visitBreak(BreakNode node);


    T visitContinue(ContinueNode node);

    T visitDel(DelNode node);

  
    T visitAssert(AssertNode node);

   
    T visitGlobal(GlobalNode node);

    /**
     * Visit raise statement: raise ValueError()
     */
    T visitRaise(RaiseNode node);

    // ========================================
    // Import Statements (2 methods)
    // ========================================

    /**
     * Visit import statement: import flask, import flask as f
     */
    T visitImport(ImportNode node);

    /**
     * Visit from-import statement: from flask import Flask
     */
    T visitFromImport(FromImportNode node);


    /**
     * Visit function definition: def foo(): ...
     */
    T visitFunctionDef(FunctionDefNode node);

    /**
     * Visit if statement: if/elif/else
     */
    T visitIfStatement(IfStatementNode node);

    /**
     * Visit for statement: for x in items: ...
     */
    T visitForStatement(ForStatementNode node);

    /**
     * Visit while statement: while condition: ...
     */
    T visitWhileStatement(WhileStatementNode node);

    /**
     * Visit with statement: with context: ...
     */
    T visitWithStatement(WithStatementNode node);

    /**
     * Visit try statement: try/except/finally
     */
    T visitTryStatement(TryStatementNode node);

    /**
     * Visit class definition: class MyClass: ...
     */
    T visitClassDef(ClassDefNode node);

    // ========================================
    // Expression Operations (2 methods)
    // ========================================

    /**
     * Visit binary operation: x + y, x == y, x and y
     */
    T visitBinaryOp(BinaryOpNode node);


    /**
     * Visit unary operation: -x, +x, not x
     */
    T visitUnaryOp(UnaryOpNode node);

    // ========================================
    // Expression Atoms (6 methods)
    // ========================================

    /**
     * Visit identifier: x, app, Flask
     */
    T visitIdentifier(IdentifierNode node);

    /**
     * Visit literal: 5, "hello", True, False, None
     */
    T visitLiteral(LiteralNode node);

    /**
     * Visit list literal: [1, 2, 3]
     */
    T visitList(ListNode node);

    /**
     * Visit dictionary literal: {"key": "value"}
     */
    T visitDict(DictNode node);

    /**
     * Visit tuple literal: (1, 2, 3)
     */
    T visitTuple(TupleNode node);

    /**
     * Visit F-string: f"Hello {name}"
     */
    T visitFString(FStringNode node);

    /**
     * Visit set literal: {1, 2, 3}
     */
    T visitSet(SetNode node);

    T visitCompare(CompareNode node);

    // ========================================
    // Expression Access (3 methods)
    // ========================================

    /**
     * Visit attribute access: obj.attr, app.config
     */
    T visitAttributeAccess(AttributeAccessNode node);

    /**
     * Visit function call: func(), foo(x, y=5)
     */
    T visitFunctionCall(FunctionCallNode node);

    /**
     * Visit subscript: list[0], dict["key"]
     */
    T visitSubscript(SubscriptNode node);
}
