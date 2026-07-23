import compilers.diagnostics.CompilerPhase;
import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticCategory;
import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.codegen.GenerationResult;
import compilers.flask.codegen.analysis.BindingAnalysisResult;
import compilers.flask.pipeline.FlaskCompilationResult;
import compilers.flask.pipeline.FlaskCompilerPipeline;
import org.antlr.v4.runtime.tree.ParseTree;

import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

/** Dependency-free acceptance tests for Phase 8 pipeline gating and failures. */
public final class FlaskPipelineHarness {
    private static final String SOURCE = "x = 1\n";
    private static final String SOURCE_FILE = "pipeline.py";
    private static int passed;
    private static int failed;

    private FlaskPipelineHarness() {
    }

    public static void main(String[] args) {
        run("successful pipeline exposes every artifact",
                FlaskPipelineHarness::testSuccessfulPipeline);
        run("parser diagnostic gates every later stage",
                FlaskPipelineHarness::testParserDiagnosticGate);
        run("AST validation diagnostic exposes AST only",
                FlaskPipelineHarness::testValidationDiagnosticGate);
        run("symbol diagnostic does not publish failed-stage artifact",
                FlaskPipelineHarness::testSymbolDiagnosticGate);
        run("scope diagnostic preserves earlier artifacts",
                FlaskPipelineHarness::testScopeDiagnosticGate);
        run("null semantic artifact is an internal invariant failure",
                FlaskPipelineHarness::testNullSemanticInvariant);
        run("binding exception preserves cause and phase",
                FlaskPipelineHarness::testBindingInternalFailure);
        run("generation internal cause crosses pipeline without duplicate diagnostic",
                FlaskPipelineHarness::testGenerationInternalFailure);
        run("ordinary generation diagnostic is not an internal failure",
                FlaskPipelineHarness::testOrdinaryGenerationFailure);
        run("foreign generation reporter is rejected as an invariant failure",
                FlaskPipelineHarness::testForeignReporterInvariant);
        run("source read failure is a pipeline internal failure",
                FlaskPipelineHarness::testSourceReadFailure);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " pipeline test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " Flask pipeline tests passed.");
    }

    private static void testSuccessfulPipeline() {
        RecordingStages stages = new RecordingStages(Mode.SUCCESS);
        FlaskCompilationResult result = compile(stages);

        check(result.isSuccess(), "successful source did not produce verified bytecode");
        check(result.hasAst(), "missing AST");
        check(result.hasSymbolTable(), "missing symbol table");
        check(result.hasTemplateContexts(), "missing semantic result");
        check(result.hasBindingAnalysis(), "missing binding analysis");
        check(result.hasVerifiedModule(), "missing verified module");
        check(!result.hasInternalFailure(), "success retained an internal failure");
        equal(0, result.getReporter().diagnostics().size(), "success diagnostics");
        stages.assertCounts(1, 1, 1, 1, 1, 1, 1, 1);

        expectThrows(UnsupportedOperationException.class,
                () -> result.getTemplateContexts().put("late", Set.of("x")),
                "template-context map mutability");
    }

    private static void testParserDiagnosticGate() {
        RecordingStages stages = new RecordingStages(Mode.PARSER_DIAGNOSTIC);
        FlaskCompilationResult result = compile(stages);

        check(!result.isSuccess(), "parser error reported success");
        check(!result.hasAst(), "AST leaked past parser error");
        check(!result.hasInternalFailure(), "source syntax error became internal");
        assertOnlyDiagnostic(result, DiagnosticCategory.SYNTAX_ERROR,
                CompilerPhase.PARSER);
        stages.assertCounts(1, 0, 0, 0, 0, 0, 0, 0);
    }

    private static void testValidationDiagnosticGate() {
        RecordingStages stages = new RecordingStages(Mode.VALIDATION_DIAGNOSTIC);
        FlaskCompilationResult result = compile(stages);

        check(result.hasAst(), "successful AST was not retained");
        check(!result.hasSymbolTable(), "symbols ran after AST validation error");
        check(!result.hasInternalFailure(), "validation error became internal");
        assertOnlyDiagnostic(result, DiagnosticCategory.INVALID_AST_STRUCTURE,
                CompilerPhase.AST_VALIDATION);
        stages.assertCounts(1, 1, 1, 0, 0, 0, 0, 0);
    }

    private static void testSymbolDiagnosticGate() {
        RecordingStages stages = new RecordingStages(Mode.SYMBOL_DIAGNOSTIC);
        FlaskCompilationResult result = compile(stages);

        check(result.hasAst(), "AST was lost after symbol error");
        check(!result.hasSymbolTable(),
                "artifact from a failed symbol stage must not be published");
        check(!result.hasTemplateContexts(), "semantics ran after symbol error");
        assertOnlyDiagnostic(result, DiagnosticCategory.DUPLICATE_SYMBOL,
                CompilerPhase.SYMBOL_TABLE);
        stages.assertCounts(1, 1, 1, 1, 0, 0, 0, 0);
    }

    private static void testScopeDiagnosticGate() {
        RecordingStages stages = new RecordingStages(Mode.SCOPE_DIAGNOSTIC);
        FlaskCompilationResult result = compile(stages);

        check(result.hasAst(), "AST was lost after scope error");
        check(result.hasSymbolTable(), "completed symbols were not retained");
        check(!result.hasTemplateContexts(), "semantics ran after scope error");
        check(!result.hasBindingAnalysis(), "bindings ran after scope error");
        assertOnlyDiagnostic(result, DiagnosticCategory.SCOPE_ERROR,
                CompilerPhase.SEMANTIC);
        stages.assertCounts(1, 1, 1, 1, 1, 0, 0, 0);
    }

    private static void testNullSemanticInvariant() {
        RecordingStages stages = new RecordingStages(Mode.NULL_SEMANTICS);
        FlaskCompilationResult result = compile(stages);

        check(result.hasInternalFailure(), "null semantic artifact was not internal");
        equal(CompilerPhase.SEMANTIC, result.getInternalFailurePhase(),
                "null semantic phase");
        check(result.getInternalFailure() instanceof IllegalStateException,
                "null semantic failure type");
        check(result.hasAst() && result.hasSymbolTable(),
                "earlier artifacts were not retained");
        check(!result.hasTemplateContexts() && !result.hasBindingAnalysis()
                        && !result.hasVerifiedModule(),
                "later artifact leaked past null semantics");
        assertOnlyDiagnostic(result, DiagnosticCategory.INTERNAL_COMPILER_ERROR,
                CompilerPhase.SEMANTIC);
        stages.assertCounts(1, 1, 1, 1, 1, 1, 0, 0);
    }

    private static void testBindingInternalFailure() {
        RecordingStages stages = new RecordingStages(Mode.BINDING_EXCEPTION);
        FlaskCompilationResult result = compile(stages);

        check(result.hasInternalFailure(), "binding exception was not preserved");
        check(result.getInternalFailure() == stages.failure,
                "pipeline replaced the original Java cause");
        equal(CompilerPhase.BINDING_RESOLUTION, result.getInternalFailurePhase(),
                "binding failure phase");
        check(result.hasTemplateContexts(), "completed semantics were not retained");
        check(!result.hasBindingAnalysis() && !result.hasVerifiedModule(),
                "failed binding artifact leaked");
        assertOnlyDiagnostic(result, DiagnosticCategory.INTERNAL_COMPILER_ERROR,
                CompilerPhase.BINDING_RESOLUTION);
        stages.assertCounts(1, 1, 1, 1, 1, 1, 1, 0);
    }

    private static void testGenerationInternalFailure() {
        RecordingStages stages = new RecordingStages(Mode.GENERATION_INTERNAL);
        FlaskCompilationResult result = compile(stages);

        check(result.hasInternalFailure(), "generation cause was discarded");
        check(result.getInternalFailure() == stages.failure,
                "generation cause identity changed");
        equal(CompilerPhase.BYTECODE_VERIFICATION,
                result.getInternalFailurePhase(), "generation internal phase");
        check(result.hasBindingAnalysis(), "completed bindings were not retained");
        check(!result.hasVerifiedModule(), "internal generation exposed bytecode");
        Diagnostic diagnostic = assertOnlyDiagnostic(
                result,
                DiagnosticCategory.INTERNAL_COMPILER_ERROR,
                CompilerPhase.BYTECODE_VERIFICATION);
        equal("pre-reported exactly once", diagnostic.message(),
                "generation diagnostic must not be regenerated from the cause");
        stages.assertCounts(1, 1, 1, 1, 1, 1, 1, 1);
    }

    private static void testOrdinaryGenerationFailure() {
        RecordingStages stages = new RecordingStages(Mode.GENERATION_DIAGNOSTIC);
        FlaskCompilationResult result = compile(stages);

        check(!result.hasInternalFailure(), "ordinary codegen error became internal");
        check(result.hasBindingAnalysis(), "bindings were lost on codegen diagnostic");
        check(!result.hasVerifiedModule(), "failed generation exposed bytecode");
        assertOnlyDiagnostic(result, DiagnosticCategory.UNSUPPORTED_AST_NODE,
                CompilerPhase.CODE_GENERATION);
        stages.assertCounts(1, 1, 1, 1, 1, 1, 1, 1);
    }

    private static void testForeignReporterInvariant() {
        RecordingStages stages = new RecordingStages(Mode.FOREIGN_REPORTER);
        FlaskCompilationResult result = compile(stages);

        check(result.hasInternalFailure(), "foreign reporter was silently accepted");
        equal(CompilerPhase.CODE_GENERATION, result.getInternalFailurePhase(),
                "foreign reporter invariant phase");
        check(result.getInternalFailure().getMessage().contains("different diagnostic reporter"),
                "foreign reporter invariant message");
        assertOnlyDiagnostic(result, DiagnosticCategory.INTERNAL_COMPILER_ERROR,
                CompilerPhase.CODE_GENERATION);
        stages.assertCounts(1, 1, 1, 1, 1, 1, 1, 1);
    }

    private static void testSourceReadFailure() {
        FlaskCompilationResult result = new FlaskCompilerPipeline().compile(
                Paths.get(".tmp", "definitely-missing-pipeline-harness-source.py"));

        check(result.hasInternalFailure(), "source read failure was not internal");
        equal(CompilerPhase.PIPELINE, result.getInternalFailurePhase(),
                "source read phase");
        check(!result.hasAst() && !result.hasSymbolTable()
                        && !result.hasTemplateContexts()
                        && !result.hasBindingAnalysis()
                        && !result.hasVerifiedModule(),
                "source read failure exposed compiler artifacts");
        assertOnlyDiagnostic(result, DiagnosticCategory.INTERNAL_COMPILER_ERROR,
                CompilerPhase.PIPELINE);
    }

    private static FlaskCompilationResult compile(RecordingStages stages) {
        return new FlaskCompilerPipeline(stages).compileSource(
                SOURCE, SOURCE_FILE, "pipeline_harness");
    }

    private static Diagnostic assertOnlyDiagnostic(
            FlaskCompilationResult result,
            DiagnosticCategory category,
            CompilerPhase phase) {
        equal(1, result.getReporter().diagnostics().size(), "diagnostic count");
        Diagnostic diagnostic = result.getReporter().diagnostics().get(0);
        equal(category, diagnostic.category(), "diagnostic category");
        equal(phase, diagnostic.phase(), "diagnostic phase");
        return diagnostic;
    }

    private enum Mode {
        SUCCESS,
        PARSER_DIAGNOSTIC,
        VALIDATION_DIAGNOSTIC,
        SYMBOL_DIAGNOSTIC,
        SCOPE_DIAGNOSTIC,
        NULL_SEMANTICS,
        BINDING_EXCEPTION,
        GENERATION_INTERNAL,
        GENERATION_DIAGNOSTIC,
        FOREIGN_REPORTER
    }

    private static final class RecordingStages implements FlaskCompilerPipeline.Stages {
        private final FlaskCompilerPipeline.Stages delegate =
                FlaskCompilerPipeline.defaultStages();
        private final Mode mode;
        private final RuntimeException failure =
                new IllegalStateException("injected stage failure");
        private int parseCalls;
        private int astCalls;
        private int validationCalls;
        private int symbolCalls;
        private int scopeCalls;
        private int semanticCalls;
        private int bindingCalls;
        private int generationCalls;

        private RecordingStages(Mode mode) {
            this.mode = mode;
        }

        @Override
        public ParseTree parse(
                String source, String sourceFile, DiagnosticReporter reporter) {
            parseCalls++;
            ParseTree tree = delegate.parse(source, sourceFile, reporter);
            if (mode == Mode.PARSER_DIAGNOSTIC) {
                reporter.report(Diagnostics.syntaxError(
                        CompilerPhase.PARSER, "injected syntax error", 1, 0, sourceFile));
            }
            return tree;
        }

        @Override
        public ProgramNode buildAst(ParseTree tree, String sourceFile) {
            astCalls++;
            return delegate.buildAst(tree, sourceFile);
        }

        @Override
        public void validateAst(
                ProgramNode ast, DiagnosticReporter reporter, String sourceFile) {
            validationCalls++;
            delegate.validateAst(ast, reporter, sourceFile);
            if (mode == Mode.VALIDATION_DIAGNOSTIC) {
                reporter.report(Diagnostics.invalidAstStructure(
                        "injected invalid AST", 1, 0, sourceFile));
            }
        }

        @Override
        public SymbolTable buildSymbols(
                ProgramNode ast, DiagnosticReporter reporter, String sourceFile) {
            symbolCalls++;
            SymbolTable symbols = delegate.buildSymbols(ast, reporter, sourceFile);
            if (mode == Mode.SYMBOL_DIAGNOSTIC) {
                reporter.report(Diagnostics.duplicateSymbol(
                        "variable", "x", 1, 0, sourceFile));
            }
            return symbols;
        }

        @Override
        public void checkScope(
                ProgramNode ast,
                SymbolTable symbols,
                DiagnosticReporter reporter,
                String sourceFile) {
            scopeCalls++;
            delegate.checkScope(ast, symbols, reporter, sourceFile);
            if (mode == Mode.SCOPE_DIAGNOSTIC) {
                reporter.report(Diagnostics.scopeError(
                        "injected scope error", 1, 0, sourceFile));
            }
        }

        @Override
        public Map<String, Set<String>> analyzeSemantics(
                ProgramNode ast,
                SymbolTable symbols,
                DiagnosticReporter reporter,
                String sourceFile) {
            semanticCalls++;
            Map<String, Set<String>> contexts =
                    delegate.analyzeSemantics(ast, symbols, reporter, sourceFile);
            return mode == Mode.NULL_SEMANTICS ? null : contexts;
        }

        @Override
        public BindingAnalysisResult resolveBindings(
                ProgramNode ast, SymbolTable symbols) {
            bindingCalls++;
            if (mode == Mode.BINDING_EXCEPTION) {
                throw failure;
            }
            return delegate.resolveBindings(ast, symbols);
        }

        @Override
        public GenerationResult generate(
                ProgramNode ast,
                BindingAnalysisResult bindings,
                String sourceFile,
                String moduleName,
                DiagnosticReporter reporter) {
            generationCalls++;
            if (mode == Mode.GENERATION_INTERNAL) {
                reporter.report(Diagnostics.internalCompilerError(
                        CompilerPhase.BYTECODE_VERIFICATION,
                        "pre-reported exactly once",
                        1,
                        0,
                        sourceFile));
                return GenerationResult.failure(
                        reporter, failure, CompilerPhase.BYTECODE_VERIFICATION);
            }
            if (mode == Mode.GENERATION_DIAGNOSTIC) {
                reporter.report(Diagnostics.unsupportedAstNode(
                        "InjectedNode", 1, 0, sourceFile));
                return GenerationResult.failure(reporter);
            }
            if (mode == Mode.FOREIGN_REPORTER) {
                DiagnosticReporter foreign = new DiagnosticReporter();
                foreign.report(Diagnostics.unsupportedAstNode(
                        "ForeignReporterNode", 1, 0, sourceFile));
                return GenerationResult.failure(foreign);
            }
            return delegate.generate(
                    ast, bindings, sourceFile, moduleName, reporter);
        }

        private void assertCounts(
                int parse,
                int ast,
                int validation,
                int symbols,
                int scope,
                int semantics,
                int bindings,
                int generation) {
            equal(parse, parseCalls, "parse calls");
            equal(ast, astCalls, "AST calls");
            equal(validation, validationCalls, "validation calls");
            equal(symbols, symbolCalls, "symbol calls");
            equal(scope, scopeCalls, "scope calls");
            equal(semantics, semanticCalls, "semantic calls");
            equal(bindings, bindingCalls, "binding calls");
            equal(generation, generationCalls, "generation calls");
        }
    }

    private static void run(String name, TestCase test) {
        try {
            test.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable failure) {
            failed++;
            System.err.println("[FAIL] " + name + ": " + failure);
            failure.printStackTrace(System.err);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                    label + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void expectThrows(
            Class<? extends Throwable> expected,
            TestCase action,
            String label) {
        try {
            action.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError(
                    label + ": expected " + expected.getSimpleName()
                            + " but got " + actual,
                    actual);
        }
        throw new AssertionError(
                label + ": expected " + expected.getSimpleName()
                        + " but nothing was thrown");
    }

    @FunctionalInterface
    private interface TestCase {
        void run() throws Exception;
    }
}
