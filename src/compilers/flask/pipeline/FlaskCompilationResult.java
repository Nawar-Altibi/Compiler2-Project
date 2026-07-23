package compilers.flask.pipeline;

import compilers.diagnostics.CompilerPhase;
import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.codegen.analysis.BindingAnalysisResult;
import compilers.flask.codegen.bytecode.VerifiedBytecodeModule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Gated result of the complete Flask compiler pipeline.
 *
 * <p>Every nullable artifact denotes a stage that was not completed.  Earlier
 * successful artifacts remain inspectable after a later failure, while the
 * artifact produced by the failed stage (and every later artifact) is absent.
 * Template contexts use an explicit availability flag because an empty map is
 * also a valid successful semantic result.</p>
 */
public final class FlaskCompilationResult {
    private final ProgramNode ast;
    private final SymbolTable symbolTable;
    private final Map<String, Set<String>> templateContexts;
    private final boolean templateContextsAvailable;
    private final BindingAnalysisResult bindingAnalysis;
    private final VerifiedBytecodeModule verifiedModule;
    private final DiagnosticReporter reporter;
    private final RuntimeException internalFailure;
    private final CompilerPhase internalFailurePhase;

    FlaskCompilationResult(
            ProgramNode ast,
            SymbolTable symbolTable,
            Map<String, Set<String>> templateContexts,
            boolean templateContextsAvailable,
            BindingAnalysisResult bindingAnalysis,
            VerifiedBytecodeModule verifiedModule,
            DiagnosticReporter reporter,
            RuntimeException internalFailure,
            CompilerPhase internalFailurePhase) {
        this.ast = ast;
        this.symbolTable = symbolTable;
        this.templateContextsAvailable = templateContextsAvailable;
        this.templateContexts = templateContextsAvailable
                ? immutableTemplateContexts(templateContexts)
                : null;
        this.bindingAnalysis = bindingAnalysis;
        this.verifiedModule = verifiedModule;
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.internalFailure = internalFailure;
        this.internalFailurePhase = internalFailurePhase;

        validateArtifactOrder();
    }

    private void validateArtifactOrder() {
        if (symbolTable != null && ast == null) {
            throw new IllegalArgumentException("A symbol table requires a successful AST stage");
        }
        if (templateContextsAvailable && symbolTable == null) {
            throw new IllegalArgumentException(
                    "Template contexts require successful symbol and semantic stages");
        }
        if (bindingAnalysis != null && !templateContextsAvailable) {
            throw new IllegalArgumentException(
                    "Binding analysis requires a successful semantic stage");
        }
        if (verifiedModule != null && bindingAnalysis == null) {
            throw new IllegalArgumentException(
                    "Verified bytecode requires successful binding analysis");
        }
        if (verifiedModule != null && reporter.hasErrors()) {
            throw new IllegalArgumentException(
                    "Verified bytecode cannot accompany compiler errors");
        }
        if ((internalFailure == null) != (internalFailurePhase == null)) {
            throw new IllegalArgumentException(
                    "An internal failure and its compiler phase must be recorded together");
        }
        if (internalFailure != null && verifiedModule != null) {
            throw new IllegalArgumentException(
                    "An internal compiler failure cannot expose verified bytecode");
        }
    }

    public boolean isSuccess() {
        return verifiedModule != null && !reporter.hasErrors();
    }

    public boolean hasAst() {
        return ast != null;
    }

    public ProgramNode getAst() {
        return ast;
    }

    public ProgramNode requireAst() {
        return requireArtifact(ast, "AST");
    }

    public boolean hasSymbolTable() {
        return symbolTable != null;
    }

    public SymbolTable getSymbolTable() {
        return symbolTable;
    }

    public SymbolTable requireSymbolTable() {
        return requireArtifact(symbolTable, "symbol table");
    }

    public boolean hasTemplateContexts() {
        return templateContextsAvailable;
    }

    /** Returns the immutable contexts, or {@code null} when semantics did not complete. */
    public Map<String, Set<String>> getTemplateContexts() {
        return templateContexts;
    }

    public Map<String, Set<String>> requireTemplateContexts() {
        if (!templateContextsAvailable) {
            throw unavailable("template contexts");
        }
        return templateContexts;
    }

    public boolean hasBindingAnalysis() {
        return bindingAnalysis != null;
    }

    public BindingAnalysisResult getBindingAnalysis() {
        return bindingAnalysis;
    }

    /** Compatibility-friendly short alias for callers that name this artifact bindings. */
    public BindingAnalysisResult getBindings() {
        return bindingAnalysis;
    }

    public BindingAnalysisResult requireBindingAnalysis() {
        return requireArtifact(bindingAnalysis, "binding analysis");
    }

    public boolean hasVerifiedModule() {
        return verifiedModule != null;
    }

    public VerifiedBytecodeModule getVerifiedModule() {
        return verifiedModule;
    }

    /** Compatibility-friendly short alias used by disassembly and VM callers. */
    public VerifiedBytecodeModule getModule() {
        return verifiedModule;
    }

    public VerifiedBytecodeModule requireVerifiedModule() {
        return requireArtifact(verifiedModule, "verified bytecode module");
    }

    public DiagnosticReporter getReporter() {
        return reporter;
    }

    public boolean hasInternalFailure() {
        return internalFailure != null;
    }

    /** Preserved Java cause for debug-mode CLI output only. */
    public RuntimeException getInternalFailure() {
        return internalFailure;
    }

    public CompilerPhase getInternalFailurePhase() {
        return internalFailurePhase;
    }

    private static <T> T requireArtifact(T artifact, String name) {
        if (artifact == null) {
            throw unavailable(name);
        }
        return artifact;
    }

    private static IllegalStateException unavailable(String name) {
        return new IllegalStateException("Compilation did not produce a successful " + name);
    }

    private static Map<String, Set<String>> immutableTemplateContexts(
            Map<String, Set<String>> contexts) {
        Objects.requireNonNull(contexts, "templateContexts");
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : contexts.entrySet()) {
            String template = Objects.requireNonNull(entry.getKey(), "template name");
            Set<String> values = Objects.requireNonNull(
                    entry.getValue(), "template variables for " + template);
            copy.put(template, Collections.unmodifiableSet(new LinkedHashSet<>(values)));
        }
        return Collections.unmodifiableMap(copy);
    }
}
