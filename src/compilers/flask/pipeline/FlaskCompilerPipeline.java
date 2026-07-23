package compilers.flask.pipeline;

import compilers.diagnostics.CompilerPhase;
import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.SymbolTable.SymbolTableBuilder;
import compilers.flask.antlr_gen.FlaskLexer;
import compilers.flask.antlr_gen.FlaskParser;
import compilers.flask.ast.builder.ASTBuilder;
import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.codegen.FlaskBytecodeCompiler;
import compilers.flask.codegen.GenerationResult;
import compilers.flask.codegen.analysis.BindingAnalysisResult;
import compilers.flask.codegen.analysis.BindingResolver;
import compilers.flask.codegen.bytecode.VerifiedBytecodeModule;
import compilers.flask.semantic.SemanticAnalyzer;
import compilers.flask.semantic.validation.AstStructuralValidator;
import compilers.flask.semantic.validation.ScopeRuleChecker;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ParseTree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reusable production pipeline for Flask/Python source.
 *
 * <p>The pipeline is intentionally transactional at every stage boundary.  A
 * stage is entered only if the shared reporter has no errors, and its artifact
 * becomes visible only after that stage returns successfully without adding an
 * error.  The injectable {@link Stages} boundary allows acceptance tests to
 * prove gating with counters without moving orchestration into the CLI.</p>
 */
public final class FlaskCompilerPipeline {
    private final Stages stages;

    public FlaskCompilerPipeline() {
        this(defaultStages());
    }

    public FlaskCompilerPipeline(Stages stages) {
        this.stages = Objects.requireNonNull(stages, "stages");
    }

    /** Returns a stateless implementation backed by the production compiler passes. */
    public static Stages defaultStages() {
        return new DefaultStages();
    }

    /** Compiles UTF-8 source from a path as the root {@code __main__} module. */
    public FlaskCompilationResult compile(Path sourcePath) {
        return compile(sourcePath, "__main__");
    }

    /** Compiles UTF-8 source from a path using an explicit canonical module name. */
    public FlaskCompilationResult compile(Path sourcePath, String moduleName) {
        Objects.requireNonNull(sourcePath, "sourcePath");
        String sourceFile = sourcePath.toString();
        final String source;
        try {
            source = new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            DiagnosticReporter reporter = new DiagnosticReporter();
            RuntimeException preserved = new IllegalStateException(
                    "Cannot read source file '" + sourceFile + "'", failure);
            reportInternal(
                    reporter, CompilerPhase.PIPELINE, preserved,
                    SourceSpan.UNKNOWN, sourceFile);
            return result(null, null, null, false, null, null,
                    reporter, preserved, CompilerPhase.PIPELINE);
        }
        return compileSource(source, sourceFile, moduleName);
    }

    /** Compiles an in-memory source string as the root {@code __main__} module. */
    public FlaskCompilationResult compileSource(String source, String sourceFile) {
        return compileSource(source, sourceFile, "__main__");
    }

    /**
     * Compiles an in-memory source string through every frontend and bytecode
     * stage.  This method never throws an ordinary compiler-stage runtime
     * failure; it converts it to an internal diagnostic and preserves the Java
     * cause in the returned result for an opt-in debug presentation layer.
     */
    public FlaskCompilationResult compileSource(
            String source, String sourceFile, String moduleName) {
        Objects.requireNonNull(source, "source");
        String normalizedSourceFile = normalizeSourceFile(sourceFile);
        String normalizedModuleName = normalizeModuleName(moduleName);
        DiagnosticReporter reporter = new DiagnosticReporter();

        ProgramNode ast = null;
        SymbolTable symbols = null;
        Map<String, Set<String>> templateContexts = null;
        boolean templateContextsAvailable = false;
        BindingAnalysisResult bindings = null;

        ParseTree tree;
        try {
            tree = stages.parse(source, normalizedSourceFile, reporter);
        } catch (RuntimeException failure) {
            return internalFailure(
                    CompilerPhase.PARSER, failure, SourceSpan.UNKNOWN,
                    normalizedSourceFile, null, null, null, false, null, reporter);
        }
        if (reporter.hasErrors()) {
            return result(null, null, null, false, null, null,
                    reporter, null, null);
        }
        if (tree == null) {
            return invariantFailure(
                    CompilerPhase.PARSER,
                    "Parser returned no parse tree",
                    SourceSpan.UNKNOWN,
                    normalizedSourceFile,
                    null, null, null, false, null, reporter);
        }

        try {
            ast = stages.buildAst(tree, normalizedSourceFile);
        } catch (RuntimeException failure) {
            return internalFailure(
                    CompilerPhase.AST, failure, SourceSpan.UNKNOWN,
                    normalizedSourceFile, null, null, null, false, null, reporter);
        }
        if (ast == null) {
            return invariantFailure(
                    CompilerPhase.AST,
                    "AST builder returned no ProgramNode",
                    SourceSpan.UNKNOWN,
                    normalizedSourceFile,
                    null, null, null, false, null, reporter);
        }

        try {
            stages.validateAst(ast, reporter, normalizedSourceFile);
        } catch (RuntimeException failure) {
            return internalFailure(
                    CompilerPhase.AST_VALIDATION, failure, ast.getSourceSpan(),
                    normalizedSourceFile, ast, null, null, false, null, reporter);
        }
        if (reporter.hasErrors()) {
            return result(ast, null, null, false, null, null,
                    reporter, null, null);
        }

        try {
            symbols = stages.buildSymbols(ast, reporter, normalizedSourceFile);
        } catch (RuntimeException failure) {
            return internalFailure(
                    CompilerPhase.SYMBOL_TABLE, failure, ast.getSourceSpan(),
                    normalizedSourceFile, ast, null, null, false, null, reporter);
        }
        if (reporter.hasErrors()) {
            return result(ast, null, null, false, null, null,
                    reporter, null, null);
        }
        if (symbols == null) {
            return invariantFailure(
                    CompilerPhase.SYMBOL_TABLE,
                    "Symbol-table builder returned no root table",
                    ast.getSourceSpan(),
                    normalizedSourceFile,
                    ast, null, null, false, null, reporter);
        }

        try {
            stages.checkScope(ast, symbols, reporter, normalizedSourceFile);
        } catch (RuntimeException failure) {
            return internalFailure(
                    CompilerPhase.SEMANTIC, failure, ast.getSourceSpan(),
                    normalizedSourceFile, ast, symbols, null, false, null, reporter);
        }
        if (reporter.hasErrors()) {
            return result(ast, symbols, null, false, null, null,
                    reporter, null, null);
        }

        try {
            templateContexts = stages.analyzeSemantics(
                    ast, symbols, reporter, normalizedSourceFile);
        } catch (RuntimeException failure) {
            return internalFailure(
                    CompilerPhase.SEMANTIC, failure, ast.getSourceSpan(),
                    normalizedSourceFile, ast, symbols, null, false, null, reporter);
        }
        if (reporter.hasErrors()) {
            return result(ast, symbols, null, false, null, null,
                    reporter, null, null);
        }
        if (templateContexts == null) {
            return invariantFailure(
                    CompilerPhase.SEMANTIC,
                    "Semantic analyzer returned no template-context result",
                    ast.getSourceSpan(),
                    normalizedSourceFile,
                    ast, symbols, null, false, null, reporter);
        }
        templateContextsAvailable = true;

        try {
            bindings = stages.resolveBindings(ast, symbols);
        } catch (RuntimeException failure) {
            return internalFailure(
                    CompilerPhase.BINDING_RESOLUTION, failure, ast.getSourceSpan(),
                    normalizedSourceFile, ast, symbols, templateContexts,
                    templateContextsAvailable, null, reporter);
        }
        if (reporter.hasErrors()) {
            return result(ast, symbols, templateContexts, templateContextsAvailable,
                    null, null, reporter, null, null);
        }
        if (bindings == null) {
            return invariantFailure(
                    CompilerPhase.BINDING_RESOLUTION,
                    "Binding resolver returned no analysis result",
                    ast.getSourceSpan(),
                    normalizedSourceFile,
                    ast, symbols, templateContexts, templateContextsAvailable,
                    null, reporter);
        }

        final GenerationResult generation;
        try {
            generation = stages.generate(
                    ast,
                    bindings,
                    normalizedSourceFile,
                    normalizedModuleName,
                    reporter);
        } catch (RuntimeException failure) {
            return internalFailure(
                    CompilerPhase.CODE_GENERATION, failure, ast.getSourceSpan(),
                    normalizedSourceFile, ast, symbols, templateContexts,
                    templateContextsAvailable, bindings, reporter);
        }
        if (generation == null) {
            return invariantFailure(
                    CompilerPhase.CODE_GENERATION,
                    "Bytecode compiler returned no generation result",
                    ast.getSourceSpan(),
                    normalizedSourceFile,
                    ast, symbols, templateContexts, templateContextsAvailable,
                    bindings, reporter);
        }
        if (generation.getReporter() != reporter) {
            return invariantFailure(
                    CompilerPhase.CODE_GENERATION,
                    "Bytecode compiler returned a result with a different diagnostic reporter",
                    ast.getSourceSpan(),
                    normalizedSourceFile,
                    ast, symbols, templateContexts, templateContextsAvailable,
                    bindings, reporter);
        }
        if (generation.hasInternalFailure()) {
            // FlaskBytecodeCompiler already emitted exactly one internal
            // diagnostic. Preserve its Java cause without reporting it again.
            return result(ast, symbols, templateContexts, templateContextsAvailable,
                    bindings, null, reporter, generation.getInternalFailure(),
                    generation.getInternalFailurePhase());
        }
        if (!generation.isSuccess() || reporter.hasErrors()) {
            return result(ast, symbols, templateContexts, templateContextsAvailable,
                    bindings, null, reporter, null, null);
        }

        VerifiedBytecodeModule module = generation.getModule();
        if (module == null) {
            return invariantFailure(
                    CompilerPhase.BYTECODE_VERIFICATION,
                    "Successful generation exposed no verified module",
                    ast.getSourceSpan(),
                    normalizedSourceFile,
                    ast, symbols, templateContexts, templateContextsAvailable,
                    bindings, reporter);
        }
        return result(ast, symbols, templateContexts, templateContextsAvailable,
                bindings, module, reporter, null, null);
    }

    private static FlaskCompilationResult invariantFailure(
            CompilerPhase phase,
            String message,
            SourceSpan span,
            String sourceFile,
            ProgramNode ast,
            SymbolTable symbols,
            Map<String, Set<String>> templateContexts,
            boolean templateContextsAvailable,
            BindingAnalysisResult bindings,
            DiagnosticReporter reporter) {
        return internalFailure(
                phase,
                new IllegalStateException(message),
                span,
                sourceFile,
                ast,
                symbols,
                templateContexts,
                templateContextsAvailable,
                bindings,
                reporter);
    }

    private static FlaskCompilationResult internalFailure(
            CompilerPhase phase,
            RuntimeException failure,
            SourceSpan span,
            String sourceFile,
            ProgramNode ast,
            SymbolTable symbols,
            Map<String, Set<String>> templateContexts,
            boolean templateContextsAvailable,
            BindingAnalysisResult bindings,
            DiagnosticReporter reporter) {
        reportInternal(reporter, phase, failure, span, sourceFile);
        return result(ast, symbols, templateContexts, templateContextsAvailable,
                bindings, null, reporter, failure, phase);
    }

    private static void reportInternal(
            DiagnosticReporter reporter,
            CompilerPhase phase,
            RuntimeException failure,
            SourceSpan span,
            String sourceFile) {
        SourceSpan location = span == null ? SourceSpan.UNKNOWN : span;
        String detail = failure.getMessage();
        reporter.report(Diagnostics.internalCompilerError(
                phase,
                detail == null || detail.trim().isEmpty()
                        ? failure.getClass().getSimpleName()
                        : detail,
                location.getStartLine(),
                location.getStartColumn(),
                location.isKnown() ? location.getSourceFile() : sourceFile));
    }

    private static FlaskCompilationResult result(
            ProgramNode ast,
            SymbolTable symbols,
            Map<String, Set<String>> templateContexts,
            boolean templateContextsAvailable,
            BindingAnalysisResult bindings,
            VerifiedBytecodeModule module,
            DiagnosticReporter reporter,
            RuntimeException failure,
            CompilerPhase failurePhase) {
        return new FlaskCompilationResult(
                ast,
                symbols,
                templateContexts,
                templateContextsAvailable,
                bindings,
                module,
                reporter,
                failure,
                failurePhase);
    }

    private static String normalizeSourceFile(String sourceFile) {
        return sourceFile == null || sourceFile.trim().isEmpty()
                ? SourceSpan.UNKNOWN_SOURCE
                : sourceFile;
    }

    private static String normalizeModuleName(String moduleName) {
        return moduleName == null || moduleName.trim().isEmpty()
                ? "__main__"
                : moduleName;
    }

    /** Injectable compiler-stage boundary used by deterministic gate tests. */
    public interface Stages {
        ParseTree parse(
                String source, String sourceFile, DiagnosticReporter reporter);

        ProgramNode buildAst(ParseTree tree, String sourceFile);

        void validateAst(
                ProgramNode ast, DiagnosticReporter reporter, String sourceFile);

        SymbolTable buildSymbols(
                ProgramNode ast, DiagnosticReporter reporter, String sourceFile);

        void checkScope(
                ProgramNode ast,
                SymbolTable symbols,
                DiagnosticReporter reporter,
                String sourceFile);

        Map<String, Set<String>> analyzeSemantics(
                ProgramNode ast,
                SymbolTable symbols,
                DiagnosticReporter reporter,
                String sourceFile);

        BindingAnalysisResult resolveBindings(ProgramNode ast, SymbolTable symbols);

        GenerationResult generate(
                ProgramNode ast,
                BindingAnalysisResult bindings,
                String sourceFile,
                String moduleName,
                DiagnosticReporter reporter);
    }

    private static final class DefaultStages implements Stages {
        @Override
        public ParseTree parse(
                String source, String sourceFile, DiagnosticReporter reporter) {
            CharStream input = CharStreams.fromString(source, sourceFile);
            FlaskLexer lexer = new FlaskLexer(input);
            lexer.removeErrorListeners();
            lexer.addErrorListener(new SyntaxDiagnosticListener(
                    CompilerPhase.LEXER, sourceFile, reporter));

            CommonTokenStream tokens = new CommonTokenStream(lexer);
            // Complete lexing first so lexical diagnostics always precede parser
            // diagnostics and a lexical error gates the parser entirely.
            tokens.fill();
            if (reporter.hasErrors()) {
                return null;
            }
            tokens.seek(0);

            FlaskParser parser = new FlaskParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(new SyntaxDiagnosticListener(
                    CompilerPhase.PARSER, sourceFile, reporter));
            return parser.program();
        }

        @Override
        public ProgramNode buildAst(ParseTree tree, String sourceFile) {
            return (ProgramNode) new ASTBuilder(sourceFile).visit(tree);
        }

        @Override
        public void validateAst(
                ProgramNode ast, DiagnosticReporter reporter, String sourceFile) {
            new AstStructuralValidator(reporter, sourceFile).validate(ast);
        }

        @Override
        public SymbolTable buildSymbols(
                ProgramNode ast, DiagnosticReporter reporter, String sourceFile) {
            SymbolTableBuilder builder = new SymbolTableBuilder(reporter, sourceFile);
            ast.accept(builder);
            return builder.getSymbolTable();
        }

        @Override
        public void checkScope(
                ProgramNode ast,
                SymbolTable symbols,
                DiagnosticReporter reporter,
                String sourceFile) {
            new ScopeRuleChecker(symbols, reporter, sourceFile).check(ast);
        }

        @Override
        public Map<String, Set<String>> analyzeSemantics(
                ProgramNode ast,
                SymbolTable symbols,
                DiagnosticReporter reporter,
                String sourceFile) {
            SemanticAnalyzer analyzer = new SemanticAnalyzer(
                    symbols, sourceFile, reporter);
            analyzer.analyze(ast);
            return analyzer.getTemplateContexts();
        }

        @Override
        public BindingAnalysisResult resolveBindings(
                ProgramNode ast, SymbolTable symbols) {
            return new BindingResolver().resolve(ast, symbols);
        }

        @Override
        public GenerationResult generate(
                ProgramNode ast,
                BindingAnalysisResult bindings,
                String sourceFile,
                String moduleName,
                DiagnosticReporter reporter) {
            return new FlaskBytecodeCompiler().compile(
                    ast, bindings, sourceFile, moduleName, reporter);
        }
    }

    private static final class SyntaxDiagnosticListener extends BaseErrorListener {
        private final CompilerPhase phase;
        private final String sourceFile;
        private final DiagnosticReporter reporter;

        private SyntaxDiagnosticListener(
                CompilerPhase phase,
                String sourceFile,
                DiagnosticReporter reporter) {
            this.phase = phase;
            this.sourceFile = sourceFile;
            this.reporter = reporter;
        }

        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String message,
                RecognitionException exception) {
            reporter.report(Diagnostics.syntaxError(
                    phase,
                    message == null ? "syntax error" : message,
                    line,
                    charPositionInLine,
                    sourceFile));
        }
    }
}
