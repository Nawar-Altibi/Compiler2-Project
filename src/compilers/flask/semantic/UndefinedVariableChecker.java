package compilers.flask.semantic;

import compilers.flask.SymbolTable.SymbolEntry;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.Visitor.ASTBaseVisitor;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;
import compilers.flask.ast.nodes.statements.compound.ClassDefNode;
import compilers.flask.ast.nodes.statements.ProgramNode;

public class UndefinedVariableChecker extends ASTBaseVisitor<Void> {
    private final ErrorReporter errorReporter;
    private final String sourceFile;
    private SymbolTable currentScope;

    public UndefinedVariableChecker(SymbolTable symbolTable, ErrorReporter errorReporter, String sourceFile) {
        this.errorReporter = errorReporter;
        this.sourceFile = sourceFile;
        this.currentScope = symbolTable;
    }

    @Override
    public Void visitProgram(ProgramNode node) {
        if (node.getScope() != null) {
            currentScope = node.getScope();
        }
        return super.visitProgram(node);
    }

    @Override
    public Void visitFunctionDef(FunctionDefNode node) {
        SymbolTable previousScope = currentScope;
        if (node.getScope() != null) {
            currentScope = node.getScope();
        }
        super.visitFunctionDef(node);
        currentScope = previousScope;
        return null;
    }

    @Override
    public Void visitClassDef(ClassDefNode node) {
        SymbolTable previousScope = currentScope;
        if (node.getScope() != null) {
            currentScope = node.getScope();
        }
        super.visitClassDef(node);
        currentScope = previousScope;
        return null;
    }

    @Override
    public Void visitIdentifier(IdentifierNode node) {
        if (currentScope == null) return null;
        
        String name = node.getName();
        // Check if defined in any scope
        SymbolEntry entry = currentScope.lookup(name);
        if (entry == null) {
            errorReporter.report(new UndefinedVariableError(name, node.getLine(), node.getColumn(), sourceFile));
        }
        return null;
    }
}
