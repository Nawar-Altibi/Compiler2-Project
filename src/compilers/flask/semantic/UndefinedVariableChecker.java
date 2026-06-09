package compilers.flask.semantic;

import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.SymbolTable.SymbolEntry;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.Visitor.ASTBaseVisitor;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;
import compilers.flask.ast.nodes.statements.compound.ClassDefNode;
import compilers.flask.ast.nodes.statements.ProgramNode;

public class UndefinedVariableChecker extends ASTBaseVisitor<Void> {
    private final DiagnosticReporter reporter;
    private final String sourceFile;
    private SymbolTable currentScope;

    public UndefinedVariableChecker(SymbolTable symbolTable, DiagnosticReporter reporter, String sourceFile) {
        this.reporter = reporter;
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
        SymbolEntry entry = currentScope.lookup(name);
        if (entry == null) {
            reporter.report(Diagnostics.undefinedVariable(name, node.getLine(), node.getColumn(), sourceFile));
        }
        return null;
    }
}
