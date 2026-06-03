package compilers.flask.semantic;

import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.ast.nodes.statements.ProgramNode;
import java.util.List;

public class SemanticAnalyzer {
    private final ErrorReporter errorReporter;
    private final SymbolTable symbolTable;
    private final String sourceFile;

    public SemanticAnalyzer(SymbolTable symbolTable, String sourceFile) {
        this.symbolTable = symbolTable;
        this.errorReporter = new ErrorReporter();
        this.sourceFile = sourceFile;
    }

    public void analyze(ProgramNode root) {
        // Run individual checkers
        new UndefinedVariableChecker(symbolTable, errorReporter, sourceFile).visitProgram(root);
        new TypeChecker(symbolTable, errorReporter, sourceFile).visitProgram(root);
        new FunctionCallChecker(symbolTable, errorReporter, sourceFile).visitProgram(root);
    }

    public ErrorReporter getErrorReporter() {
        return errorReporter;
    }
}
