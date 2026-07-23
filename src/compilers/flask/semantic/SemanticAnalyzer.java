package compilers.flask.semantic;

import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.ast.nodes.statements.ProgramNode;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Orchestrates the independent semantic-analysis passes. */
public final class SemanticAnalyzer {
    private final DiagnosticReporter reporter;
    private final SymbolTable symbolTable;
    private final String sourceFile;
    private Map<String, Set<String>> templateContexts = Collections.emptyMap();

    public SemanticAnalyzer(
            SymbolTable symbolTable,
            String sourceFile,
            DiagnosticReporter reporter) {
        this.symbolTable = Objects.requireNonNull(symbolTable, "symbolTable");
        this.sourceFile = sourceFile == null || sourceFile.trim().isEmpty()
                ? "<unknown>"
                : sourceFile;
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    public void analyze(ProgramNode root) {
        ProgramNode program = Objects.requireNonNull(root, "root");
        program.accept(new UndefinedVariableChecker(symbolTable, reporter, sourceFile));
        program.accept(new TypeChecker(symbolTable, reporter, sourceFile));
        program.accept(new FunctionCallChecker(symbolTable, reporter, sourceFile));

        TemplateContextCollector collector = new TemplateContextCollector();
        program.accept(collector);
        templateContexts = collector.getTemplateContexts();
    }

    public DiagnosticReporter getReporter() {
        return reporter;
    }

    public Map<String, Set<String>> getTemplateContexts() {
        return templateContexts;
    }
}
