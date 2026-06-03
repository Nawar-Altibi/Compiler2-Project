package compilers.flask.semantic;

import compilers.flask.SymbolTable.SymbolEntry;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.Visitor.ASTBaseVisitor;
import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.expressions.access.FunctionCallNode;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;
import compilers.flask.ast.nodes.statements.compound.ClassDefNode;
import compilers.flask.ast.nodes.statements.ProgramNode;

public class FunctionCallChecker extends ASTBaseVisitor<Void> {
    private final SymbolTable rootSymbolTable;
    private final ErrorReporter errorReporter;
    private final String sourceFile;
    private SymbolTable currentScope;

    public FunctionCallChecker(SymbolTable symbolTable, ErrorReporter errorReporter, String sourceFile) {
        this.rootSymbolTable = symbolTable;
        this.errorReporter = errorReporter;
        this.sourceFile = sourceFile;
        this.currentScope = symbolTable;
    }

    @Override
    public Void visitProgram(ProgramNode node) {
        currentScope = node.getScope();
        return super.visitProgram(node);
    }

    @Override
    public Void visitFunctionDef(FunctionDefNode node) {
        SymbolTable previousScope = currentScope;
        currentScope = node.getScope();
        super.visitFunctionDef(node);
        currentScope = previousScope;
        return null;
    }

    @Override
    public Void visitClassDef(ClassDefNode node) {
        SymbolTable previousScope = currentScope;
        currentScope = node.getScope();
        super.visitClassDef(node);
        currentScope = previousScope;
        return null;
    }

    @Override
    public Void visitFunctionCall(FunctionCallNode node) {
        Expression funcExpr = node.getFunction();
        if (funcExpr instanceof IdentifierNode) {
            String funcName = ((IdentifierNode) funcExpr).getName();
            SymbolEntry entry = currentScope.lookup(funcName);
            
            if (entry != null && entry.getFunctionNode() != null) {
                FunctionDefNode def = entry.getFunctionNode();
                int expected = def.getParameterCount();
                int provided = node.getArgs().size() + node.getKwargs().size();
                
                if (expected != provided) {
                    errorReporter.report(new FunctionCallError("Function '" + funcName + "' expects " + expected + " arguments but got " + provided, node.getLine(), node.getColumn(), sourceFile));
                }
            }
        }
        return super.visitFunctionCall(node);
    }
}
