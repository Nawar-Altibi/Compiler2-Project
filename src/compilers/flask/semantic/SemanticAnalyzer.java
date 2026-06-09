package compilers.flask.semantic;

import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.ast.nodes.statements.ProgramNode;

public class SemanticAnalyzer {
    private final DiagnosticReporter reporter;
    private final SymbolTable symbolTable;
    private final String sourceFile;

    public SemanticAnalyzer(SymbolTable symbolTable, String sourceFile, DiagnosticReporter reporter) {
        this.symbolTable = symbolTable;
        this.sourceFile = sourceFile;
        this.reporter = reporter;
    }

    public void analyze(ProgramNode root) {
        new UndefinedVariableChecker(symbolTable, reporter, sourceFile).visitProgram(root);
        new TypeChecker(symbolTable, reporter, sourceFile).visitProgram(root);
        new FunctionCallChecker(symbolTable, reporter, sourceFile).visitProgram(root);
    }

    public DiagnosticReporter getReporter() {
        return reporter;
    }
}
