import compilers.diagnostics.CompilerPhase;
import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticCategory;
import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.SymbolTable.SymbolTableBuilder;
import compilers.flask.antlr_gen.FlaskLexer;
import compilers.flask.antlr_gen.FlaskParser;
import compilers.flask.ast.builder.ASTBuilder;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.Statement;
import compilers.flask.ast.nodes.expressions.access.FunctionCallNode;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.expressions.atoms.LiteralNode;
import compilers.flask.ast.nodes.expressions.atoms.TupleNode;
import compilers.flask.ast.nodes.helpers.CallArgument;
import compilers.flask.ast.nodes.helpers.ExceptClause;
import compilers.flask.ast.nodes.helpers.Parameter;
import compilers.flask.ast.nodes.helpers.WithItem;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.ast.nodes.statements.compound.ClassDefNode;
import compilers.flask.ast.nodes.statements.compound.ForStatementNode;
import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;
import compilers.flask.ast.nodes.statements.compound.TryStatementNode;
import compilers.flask.ast.nodes.statements.compound.WithStatementNode;
import compilers.flask.ast.nodes.statements.imports.FromImportNode;
import compilers.flask.ast.nodes.statements.simple.AssignmentNode;
import compilers.flask.ast.nodes.statements.simple.DelNode;
import compilers.flask.ast.nodes.statements.simple.ExpressionStatementNode;
import compilers.flask.ast.nodes.statements.simple.PassNode;
import compilers.flask.semantic.validation.AstStructuralValidator;
import compilers.flask.semantic.validation.ScopeRuleChecker;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Dependency-free executable regression tests for the two validation gates. */
public final class FlaskValidationHarness {
    private static int passed;
    private static int failed;

    private FlaskValidationHarness() {
    }

    public static void main(String[] args) {
        run("module/function/class control contexts",
                FlaskValidationHarness::testControlFlowContexts);
        run("loop context isolation and loop else",
                FlaskValidationHarness::testLoopIsolationAndElse);
        run("exception handler ordering",
                FlaskValidationHarness::testExceptionHandlerOrder);
        run("parameter default ordering",
                FlaskValidationHarness::testParameterOrder);
        run("ordered call argument rules",
                FlaskValidationHarness::testOrderedCallRules);
        run("wildcard import executable scope",
                FlaskValidationHarness::testWildcardImportScope);
        run("illegal preserved AST targets",
                FlaskValidationHarness::testIllegalTargets);
        run("parser preserves invalid target for validation",
                FlaskValidationHarness::testParserPreservesInvalidTarget);
        run("valid structural boundary program",
                FlaskValidationHarness::testStructurallyValidProgram);
        run("global conflicts with parameters",
                FlaskValidationHarness::testGlobalParameterConflict);
        run("global conflicts with prior use/store/delete",
                FlaskValidationHarness::testGlobalPriorOccurrences);
        run("global conflicts with every binding form",
                FlaskValidationHarness::testGlobalBindingForms);
        run("global declarations are block-isolated",
                FlaskValidationHarness::testGlobalBlockIsolation);
        run("validation diagnostics use AST validation phase",
                FlaskValidationHarness::testDiagnosticContract);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " validation test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " Flask validation tests passed.");
    }

    private static void testControlFlowContexts() {
        DiagnosticReporter reporter = structurallyValidate(lines(
                "return 1",
                "break",
                "continue",
                "def valid():",
                "    while True:",
                "        break",
                "    class NestedInvalid:",
                "        return 2",
                "    return 1",
                "class Invalid:",
                "    return 1"), "control-contexts.py");

        assertCount(reporter, DiagnosticCategory.INVALID_CONTROL_FLOW, 5);
        equal(5, reporter.diagnostics().size(), "control diagnostic count");
    }

    private static void testLoopIsolationAndElse() {
        DiagnosticReporter reporter = structurallyValidate(lines(
                "while True:",
                "    break",
                "    continue",
                "    def nested_function():",
                "        break",
                "    class NestedClass:",
                "        continue",
                "else:",
                "    break"), "loop-isolation.py");

        assertCount(reporter, DiagnosticCategory.INVALID_CONTROL_FLOW, 3);
        equal(3, reporter.diagnostics().size(), "isolated loop diagnostic count");
    }

    private static void testExceptionHandlerOrder() {
        TryStatementNode invalidTry = new TryStatementNode(
                statements(new PassNode()),
                Arrays.asList(
                        new ExceptClause(statements(new PassNode())),
                        new ExceptClause(
                                new IdentifierNode("Exception"),
                                statements(new PassNode()))),
                null,
                null);

        DiagnosticReporter reporter = structurallyValidate(
                new ProgramNode(statements(invalidTry)), "handler-order.py");
        assertOnly(reporter, DiagnosticCategory.INVALID_EXCEPTION_HANDLER_ORDER, 1);
    }

    private static void testParameterOrder() {
        FunctionDefNode invalid = new FunctionDefNode(
                "invalid",
                Arrays.asList(
                        new Parameter("first", LiteralNode.integer(1)),
                        new Parameter("required")),
                statements(new PassNode()));

        DiagnosticReporter reporter = structurallyValidate(
                new ProgramNode(statements(invalid)), "parameters.py");
        assertOnly(reporter, DiagnosticCategory.INVALID_PARAMETER_ORDER, 1);
        check(allDiagnostics(reporter).contains("required"),
                "parameter diagnostic must name the offending parameter");
    }

    private static void testOrderedCallRules() {
        DiagnosticReporter reporter = structurallyValidate(lines(
                "target(value=1, 2, value=3)"), "call-order.py");
        assertOnly(reporter, DiagnosticCategory.INVALID_CALL_ARGUMENTS, 2);
        check(allDiagnostics(reporter).contains("positional"),
                "missing positional-after-keyword diagnostic");
        check(allDiagnostics(reporter).contains("Duplicate keyword"),
                "missing duplicate-keyword diagnostic");
    }

    private static void testWildcardImportScope() {
        FromImportNode moduleStar = FromImportNode.importAll("module_api");
        FunctionDefNode function = new FunctionDefNode(
                "function_scope",
                Collections.<Parameter>emptyList(),
                statements(FromImportNode.importAll("function_api")));
        ClassDefNode classNode = new ClassDefNode(
                "ClassScope",
                statements(FromImportNode.importAll("class_api")));

        DiagnosticReporter reporter = structurallyValidate(
                new ProgramNode(statements(moduleStar, function, classNode)),
                "star-imports.py");
        assertOnly(reporter, DiagnosticCategory.INVALID_IMPORT_SCOPE, 2);
    }

    private static void testIllegalTargets() {
        FunctionCallNode callTarget = new FunctionCallNode(new IdentifierNode("factory"));
        AssignmentNode invalidAssignment = new AssignmentNode(
                callTarget, "=", LiteralNode.integer(1));

        TupleNode augmentedTuple = new TupleNode(Arrays.<Expression>asList(
                new IdentifierNode("left"), new IdentifierNode("right")));
        AssignmentNode invalidAugmented = new AssignmentNode(
                augmentedTuple, "+=", LiteralNode.integer(1));

        ForStatementNode invalidFor = new ForStatementNode(
                LiteralNode.integer(1),
                new IdentifierNode("items"),
                statements(new PassNode()));

        WithStatementNode invalidWith = new WithStatementNode(
                new WithItem(
                        new IdentifierNode("manager"),
                        LiteralNode.string("not-a-target")),
                statements(new PassNode()));

        DelNode invalidDelete = new DelNode(LiteralNode.bool(true));

        DiagnosticReporter reporter = structurallyValidate(
                new ProgramNode(statements(
                        invalidAssignment,
                        invalidAugmented,
                        invalidFor,
                        invalidWith,
                        invalidDelete)),
                "invalid-targets.py");
        assertOnly(reporter, DiagnosticCategory.INVALID_ASSIGNMENT_TARGET, 5);
    }

    private static void testParserPreservesInvalidTarget() {
        DiagnosticReporter reporter = structurallyValidate(lines(
                "1 = 2",
                "factory() = 3"), "preserved-targets.py");
        assertOnly(reporter, DiagnosticCategory.INVALID_ASSIGNMENT_TARGET, 2);
    }

    private static void testStructurallyValidProgram() {
        FunctionCallNode call = new FunctionCallNode(
                new IdentifierNode("target"),
                Arrays.asList(
                        CallArgument.positional(LiteralNode.integer(1)),
                        CallArgument.keyword("named", LiteralNode.integer(2))));
        TryStatementNode orderedHandlers = new TryStatementNode(
                statements(new ExpressionStatementNode(call)),
                Arrays.asList(
                        new ExceptClause(
                                new IdentifierNode("Exception"),
                                statements(new PassNode())),
                        new ExceptClause(statements(new PassNode()))),
                null,
                null);
        FunctionDefNode function = new FunctionDefNode(
                "valid",
                Arrays.asList(
                        new Parameter("required"),
                        new Parameter("optional", LiteralNode.integer(1))),
                statements(orderedHandlers));

        ProgramNode program = new ProgramNode(statements(
                FromImportNode.importAll("module_api"),
                function));
        DiagnosticReporter reporter = structurallyValidate(program, "valid.py");
        check(!reporter.hasDiagnostics(),
                "unexpected structural diagnostics: " + allDiagnostics(reporter));
    }

    private static void testGlobalParameterConflict() {
        DiagnosticReporter reporter = scopeValidate(lines(
                "def invalid(value):",
                "    global value",
                "    return value"), "global-parameter.py");
        assertOnly(reporter, DiagnosticCategory.GLOBAL_DECLARATION_CONFLICT, 1);
        check(allDiagnostics(reporter).contains("parameter"),
                "global/parameter diagnostic reason is missing");
    }

    private static void testGlobalPriorOccurrences() {
        DiagnosticReporter reporter = scopeValidate(lines(
                "module_name = 0",
                "global module_name",
                "def used_before():",
                "    print(used_name)",
                "    global used_name",
                "def assigned_before():",
                "    assigned_name = 1",
                "    global assigned_name",
                "def deleted_before():",
                "    del deleted_name",
                "    global deleted_name"), "global-prior.py");

        assertOnly(reporter, DiagnosticCategory.GLOBAL_DECLARATION_CONFLICT, 4);
        String diagnostics = allDiagnostics(reporter);
        check(diagnostics.contains("used before"), "prior-use reason is missing");
        check(diagnostics.contains("assigned before"), "prior-binding reason is missing");
    }

    private static void testGlobalBindingForms() {
        DiagnosticReporter reporter = scopeValidate(lines(
                "def bindings():",
                "    import os as imported_name",
                "    for loop_name in []:",
                "        pass",
                "    with open(\"data.txt\") as with_name:",
                "        pass",
                "    try:",
                "        pass",
                "    except Exception as exception_name:",
                "        pass",
                "    def nested_name():",
                "        pass",
                "    class ClassName:",
                "        pass",
                "    global imported_name, loop_name, with_name, exception_name, nested_name, ClassName"),
                "global-bindings.py");

        assertOnly(reporter, DiagnosticCategory.GLOBAL_DECLARATION_CONFLICT, 6);
    }

    private static void testGlobalBlockIsolation() {
        DiagnosticReporter reporter = scopeValidate(lines(
                "outer_name = 1",
                "def clean():",
                "    global outer_name",
                "    global outer_name",
                "    print(outer_name)",
                "    outer_name = 2",
                "def nested_use():",
                "    print(separate_name)",
                "global separate_name"), "global-isolation.py");

        check(!reporter.hasDiagnostics(),
                "global state leaked across blocks: " + allDiagnostics(reporter));
    }

    private static void testDiagnosticContract() {
        DiagnosticReporter reporter = structurallyValidate(lines("return 1"), "located.py");
        equal(1, reporter.diagnostics().size(), "diagnostic count");
        Diagnostic diagnostic = reporter.diagnostics().get(0);
        equal(CompilerPhase.AST_VALIDATION, diagnostic.phase(), "validation phase");
        equal(DiagnosticCategory.INVALID_CONTROL_FLOW,
                diagnostic.category(), "validation category");
        check(diagnostic.isError(), "structural validation must report an error");
        equal("located.py", diagnostic.sourceFile(), "diagnostic source");
        equal(1, diagnostic.line(), "diagnostic line");
    }

    private static DiagnosticReporter structurallyValidate(
            String source, String sourceFile) {
        return structurallyValidate(build(source), sourceFile);
    }

    private static DiagnosticReporter structurallyValidate(
            ProgramNode program, String sourceFile) {
        DiagnosticReporter reporter = new DiagnosticReporter();
        new AstStructuralValidator(reporter, sourceFile).validate(program);
        return reporter;
    }

    private static DiagnosticReporter scopeValidate(String source, String sourceFile) {
        ProgramNode program = build(source);
        DiagnosticReporter reporter = new DiagnosticReporter();
        AstStructuralValidator structural = new AstStructuralValidator(reporter, sourceFile);
        check(structural.validate(program),
                "scope fixture failed structural validation: " + allDiagnostics(reporter));

        SymbolTableBuilder symbolBuilder = new SymbolTableBuilder(reporter, sourceFile);
        program.accept(symbolBuilder);
        ScopeRuleChecker scopeRules = new ScopeRuleChecker(
                symbolBuilder.getSymbolTable(), reporter, sourceFile);
        scopeRules.check(program);
        return reporter;
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

    private static List<Statement> statements(Statement... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static String lines(String... sourceLines) {
        return String.join("\n", sourceLines) + "\n";
    }

    private static void assertOnly(
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

    private static String allDiagnostics(DiagnosticReporter reporter) {
        StringBuilder result = new StringBuilder();
        for (Diagnostic diagnostic : reporter.diagnostics()) {
            result.append(diagnostic).append('\n');
        }
        return result.toString();
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
