import compilers.diagnostics.CompilerPhase;
import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticCategory;
import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.DiagnosticSeverity;
import compilers.diagnostics.Diagnostics;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.SymbolTable.SymbolTableBuilder;
import compilers.flask.antlr_gen.FlaskLexer;
import compilers.flask.antlr_gen.FlaskParser;
import compilers.flask.ast.builder.ASTBuilder;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.ast.nodes.statements.compound.ClassDefNode;
import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;
import compilers.flask.semantic.SemanticAnalyzer;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Dependency-free executable tests for the Flask semantic phase. */
public final class FlaskSemanticRegressionHarness {
    private static int passed;
    private static int failed;

    private FlaskSemanticRegressionHarness() {
    }

    public static void main(String[] args) {
        run("Pass 1 reports duplicates only", FlaskSemanticRegressionHarness::testPassOneOnly);
        run("undefined-variable fixture", FlaskSemanticRegressionHarness::testUndefinedVariables);
        run("type-error fixture", FlaskSemanticRegressionHarness::testTypeErrors);
        run("literal division by zero", FlaskSemanticRegressionHarness::testDivisionByZero);
        run("type-mismatch warnings", FlaskSemanticRegressionHarness::testTypeMismatchWarnings);
        run("reassignment updates observed type",
                FlaskSemanticRegressionHarness::testReassignmentUpdatesObservedType);
        run("direct-call arity fixture", FlaskSemanticRegressionHarness::testFunctionCalls);
        run("duplicate-symbol fixture", FlaskSemanticRegressionHarness::testDuplicateSymbols);
        run("scope fixture", FlaskSemanticRegressionHarness::testScopes);
        run("combined fixture", FlaskSemanticRegressionHarness::testCombined);
        run("default arguments", FlaskSemanticRegressionHarness::testDefaultArguments);
        run("wildcard import", FlaskSemanticRegressionHarness::testWildcardImport);
        run("expanded builtins", FlaskSemanticRegressionHarness::testBuiltins);
        run("bool arithmetic", FlaskSemanticRegressionHarness::testBoolArithmetic);
        run("definition-time scope", FlaskSemanticRegressionHarness::testDefinitionTimeScope);
        run("AST scope attachment", FlaskSemanticRegressionHarness::testScopeAttachment);
        run("template context collection", FlaskSemanticRegressionHarness::testTemplateContexts);
        run("diagnostic identity and formatting", FlaskSemanticRegressionHarness::testDiagnostics);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(failed + " semantic test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " Flask semantic tests passed.");
    }

    private static void testPassOneOnly() {
        ProgramNode ast = build(lines(
                "print(missing)",
                "bad = 1 + \"x\"",
                "def repeated():",
                "    pass",
                "def repeated():",
                "    pass"));
        DiagnosticReporter reporter = new DiagnosticReporter();
        SymbolTableBuilder builder = new SymbolTableBuilder(reporter, "pass-one.py");
        ast.accept(builder);

        equal(1, reporter.diagnostics().size(), "Pass 1 diagnostic count");
        assertCount(reporter, DiagnosticCategory.DUPLICATE_SYMBOL, 1);
        equal(CompilerPhase.SYMBOL_TABLE, reporter.diagnostics().get(0).phase(),
                "duplicate phase");
    }

    private static void testUndefinedVariables() throws IOException {
        Analysis analysis = analyzeFixture("errors", "undefined_variable.py");
        assertOnlyCategory(analysis.reporter, DiagnosticCategory.UNDEFINED_VARIABLE, 3);
        assertMessagesContain(analysis.reporter, "undefined_name", "unknown_var", "message");
    }

    private static void testTypeErrors() throws IOException {
        Analysis analysis = analyzeFixture("errors", "type_error.py");
        assertOnlyCategory(analysis.reporter, DiagnosticCategory.TYPE_ERROR, 4);
        equal(4, analysis.reporter.errors().size(), "type errors are hard errors");
    }

    private static void testDivisionByZero() {
        Analysis analysis = analyze(lines(
                "a = 10 / 0",
                "b = 10 // 0",
                "c = 10 % 0",
                "d = 10 / 0.0",
                "e = 10 / False",
                "valid = 0 / 10",
                "text_format = \"%s\" % 0"), "division-by-zero.py");
        assertOnlyCategory(
                analysis.reporter, DiagnosticCategory.DIVISION_BY_ZERO, 5);
        equal(5, analysis.reporter.errors().size(),
                "division by zero diagnostics are hard errors");
    }

    private static void testTypeMismatchWarnings() throws IOException {
        Analysis analysis = analyzeFixture("errors", "type_mismatch.py");
        assertOnlyCategory(analysis.reporter, DiagnosticCategory.TYPE_MISMATCH, 3);
        equal(0, analysis.reporter.errors().size(), "type mismatch error count");
        equal(3, analysis.reporter.warnings().size(), "type mismatch warning count");
        for (Diagnostic diagnostic : analysis.reporter.diagnostics()) {
            equal(DiagnosticSeverity.WARNING, diagnostic.severity(), "mismatch severity");
        }
    }

    private static void testReassignmentUpdatesObservedType() {
        Analysis analysis = analyze(lines(
                "value = 1",
                "value = \"now a string\"",
                "result = value + \"!\""), "reassignment.py");
        assertOnlyCategory(analysis.reporter, DiagnosticCategory.TYPE_MISMATCH, 1);
    }

    private static void testFunctionCalls() throws IOException {
        Analysis analysis = analyzeFixture("errors", "function_call.py");
        assertOnlyCategory(analysis.reporter, DiagnosticCategory.FUNCTION_CALL_ERROR, 3);
        check(!allDiagnostics(analysis.reporter).contains("calc.add"),
                "method calls must remain outside direct-call arity checking");
    }

    private static void testDuplicateSymbols() throws IOException {
        Analysis analysis = analyzeFixture("errors", "duplicate_symbol.py");
        assertOnlyCategory(analysis.reporter, DiagnosticCategory.DUPLICATE_SYMBOL, 2);
        for (Diagnostic diagnostic : analysis.reporter.diagnostics()) {
            equal(CompilerPhase.SYMBOL_TABLE, diagnostic.phase(), "duplicate phase");
        }
    }

    private static void testScopes() throws IOException {
        Analysis analysis = analyzeFixture("errors", "scope.py");
        assertOnlyCategory(analysis.reporter, DiagnosticCategory.UNDEFINED_VARIABLE, 4);
        assertMessagesContain(analysis.reporter, "'y'", "'val'", "'x'", "'instance'");
    }

    private static void testCombined() throws IOException {
        Analysis analysis = analyzeFixture("errors", "combined_semantic.py");
        equal(6, analysis.reporter.diagnostics().size(), "combined diagnostic count");
        assertCount(analysis.reporter, DiagnosticCategory.UNDEFINED_VARIABLE, 2);
        assertCount(analysis.reporter, DiagnosticCategory.TYPE_ERROR, 1);
        assertCount(analysis.reporter, DiagnosticCategory.TYPE_MISMATCH, 1);
        assertCount(analysis.reporter, DiagnosticCategory.FUNCTION_CALL_ERROR, 2);
        equal(5, analysis.reporter.errors().size(), "combined error count");
        equal(1, analysis.reporter.warnings().size(), "combined warning count");
    }

    private static void testDefaultArguments() throws IOException {
        assertNoDiagnostics(analyzeFixture("valid", "defaults_ok.py"));
    }

    private static void testWildcardImport() throws IOException {
        Analysis analysis = analyzeFixture("valid", "star_import.py");
        assertNoDiagnostics(analysis);
        check(analysis.symbols.hasWildcardImport(), "root wildcard flag was not recorded");
    }

    private static void testBuiltins() throws IOException {
        assertNoDiagnostics(analyzeFixture("valid", "builtins_ok.py"));
    }

    private static void testBoolArithmetic() {
        Analysis analysis = analyze(lines(
                "a = True + 1",
                "b = 2 * False",
                "c = True + 0.5"), "bool-arithmetic.py");
        assertNoDiagnostics(analysis);
    }

    private static void testDefinitionTimeScope() {
        Analysis analysis = analyze(lines(
                "class Base:",
                "    pass",
                "seed = 1",
                "def decorate(value):",
                "    return value",
                "@decorate(seed)",
                "def target(value=seed):",
                "    return value",
                "class Child(Base):",
                "    pass"), "definition-time.py");
        assertNoDiagnostics(analysis);
    }

    private static void testScopeAttachment() {
        Analysis analysis = analyze(lines(
                "def function_scope():",
                "    pass",
                "class ClassScope:",
                "    pass"), "scopes.py");
        FunctionDefNode function = (FunctionDefNode) analysis.ast.getStatements().get(0);
        ClassDefNode classNode = (ClassDefNode) analysis.ast.getStatements().get(1);

        check(analysis.ast.getScope() == analysis.symbols, "program root scope was not attached");
        check(function.getScope() != null, "function scope was not attached");
        check(classNode.getScope() != null, "class scope was not attached");
        check(function.getScope().getParent() == analysis.symbols,
                "function structural parent is wrong");
        check(classNode.getScope().getParent() == analysis.symbols,
                "class structural parent is wrong");
    }

    private static void testTemplateContexts() {
        Analysis analysis = analyze(lines(
                "from flask import render_template",
                "def first():",
                "    return render_template(\"profile.html\", user=\"A\", title=\"Profile\")",
                "def second():",
                "    return render_template(\"profile.html\", items=[])",
                "def third():",
                "    return render_template(\"home.html\", greeting=\"Hello\")"),
                "templates.py");
        assertNoDiagnostics(analysis);

        Map<String, Set<String>> contexts = analysis.semantic.getTemplateContexts();
        equal(Set.of("user", "title", "items"), contexts.get("profile.html"),
                "profile template context");
        equal(Set.of("greeting"), contexts.get("home.html"), "home template context");
        try {
            contexts.get("profile.html").add("mutate");
            throw new AssertionError("template context sets must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected defensive result.
        }
    }

    private static void testDiagnostics() {
        DiagnosticReporter reporter = new DiagnosticReporter();
        Diagnostic warning = Diagnostics.typeMismatch(
                "value", "int", "str", 7, 3, "sample.py");
        reporter.report(warning);
        reporter.report(warning);

        equal(1, reporter.diagnostics().size(), "exact diagnostic deduplication");
        equal(DiagnosticSeverity.WARNING, warning.severity(), "mismatch severity");
        equal(CompilerPhase.SEMANTIC, warning.phase(), "mismatch phase");
        check(warning.toString().contains("sample.py:7:3"), "formatted source location is missing");
        check(warning.toString().contains("Type Mismatch"), "formatted category is missing");
    }

    private static Analysis analyzeFixture(String group, String name) throws IOException {
        Path path = Path.of("Tests", "flask", group, name);
        return analyze(Files.readString(path, StandardCharsets.UTF_8), path.toString());
    }

    private static Analysis analyze(String source, String sourceFile) {
        ProgramNode ast = build(source);
        DiagnosticReporter reporter = new DiagnosticReporter();
        SymbolTableBuilder builder = new SymbolTableBuilder(reporter, sourceFile);
        ast.accept(builder);
        SemanticAnalyzer semantic = new SemanticAnalyzer(
                builder.getSymbolTable(), sourceFile, reporter);
        semantic.analyze(ast);
        return new Analysis(ast, builder.getSymbolTable(), reporter, semantic);
    }

    private static ProgramNode build(String source) {
        List<String> syntaxIssues = new ArrayList<>();
        FlaskLexer lexer = new FlaskLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new CollectingErrorListener("lexer", syntaxIssues));

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        FlaskParser parser = new FlaskParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new CollectingErrorListener("parser", syntaxIssues));
        FlaskParser.ProgramContext tree = parser.program();
        check(syntaxIssues.isEmpty(), "syntax errors: " + syntaxIssues + "\n" + source);

        ASTNode root = new ASTBuilder().visit(tree);
        check(root instanceof ProgramNode, "AST root is not ProgramNode");
        return (ProgramNode) root;
    }

    private static void assertNoDiagnostics(Analysis analysis) {
        check(!analysis.reporter.hasDiagnostics(),
                "unexpected diagnostics: " + allDiagnostics(analysis.reporter));
    }

    private static void assertOnlyCategory(
            DiagnosticReporter reporter, DiagnosticCategory category, int expected) {
        equal(expected, reporter.diagnostics().size(), "total diagnostic count");
        assertCount(reporter, category, expected);
    }

    private static void assertCount(
            DiagnosticReporter reporter, DiagnosticCategory category, int expected) {
        int actual = 0;
        for (Diagnostic diagnostic : reporter.diagnostics()) {
            if (diagnostic.category() == category) {
                actual++;
            }
        }
        equal(expected, actual, category + " count");
    }

    private static void assertMessagesContain(
            DiagnosticReporter reporter, String... fragments) {
        String messages = allDiagnostics(reporter);
        for (String fragment : fragments) {
            check(messages.contains(fragment),
                    "missing diagnostic fragment '" + fragment + "' in " + messages);
        }
    }

    private static String allDiagnostics(DiagnosticReporter reporter) {
        StringBuilder result = new StringBuilder();
        for (Diagnostic diagnostic : reporter.diagnostics()) {
            result.append(diagnostic).append('\n');
        }
        return result.toString();
    }

    private static String lines(String... sourceLines) {
        return String.join("\n", sourceLines) + "\n";
    }

    private static void run(String name, CheckedRunnable test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable failure) {
            failed++;
            System.err.println("FAIL  " + name + " -> " + failure.getMessage());
            failure.printStackTrace(System.err);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class Analysis {
        private final ProgramNode ast;
        private final SymbolTable symbols;
        private final DiagnosticReporter reporter;
        private final SemanticAnalyzer semantic;

        private Analysis(
                ProgramNode ast,
                SymbolTable symbols,
                DiagnosticReporter reporter,
                SemanticAnalyzer semantic) {
            this.ast = ast;
            this.symbols = symbols;
            this.reporter = reporter;
            this.semantic = semantic;
        }
    }

    private static final class CollectingErrorListener extends BaseErrorListener {
        private final String phase;
        private final List<String> issues;

        private CollectingErrorListener(String phase, List<String> issues) {
            this.phase = phase;
            this.issues = issues;
        }

        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String message,
                RecognitionException exception) {
            issues.add(phase + " " + line + ":" + charPositionInLine + " " + message);
        }
    }
}
