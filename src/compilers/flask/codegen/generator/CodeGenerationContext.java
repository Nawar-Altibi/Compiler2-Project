package compilers.flask.codegen.generator;

import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.codegen.analysis.BindingAnalysisResult;
import compilers.flask.codegen.analysis.ScopeLayout;
import compilers.flask.codegen.bytecode.CodeObjectBuilder;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** All mutable emission state owned by one executable code object. */
public final class CodeGenerationContext {
    private final CodeObjectBuilder builder;
    private final BindingAnalysisResult bindings;
    private final ScopeLayout scope;
    private final DiagnosticReporter reporter;
    private final String sourceFile;
    private final TemporaryAllocator temporaries = new TemporaryAllocator();
    private final Deque<LoopContext> loops = new ArrayDeque<>();
    private int nextHandlerId;

    public CodeGenerationContext(
            CodeObjectBuilder builder,
            BindingAnalysisResult bindings,
            ScopeLayout scope,
            DiagnosticReporter reporter,
            String sourceFile) {
        this.builder = Objects.requireNonNull(builder, "builder");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.sourceFile = sourceFile == null ? "" : sourceFile;
    }

    public CodeObjectBuilder getBuilder() { return builder; }
    public BindingAnalysisResult getBindings() { return bindings; }
    public ScopeLayout getScope() { return scope; }
    public DiagnosticReporter getReporter() { return reporter; }
    public String getSourceFile() { return sourceFile; }
    public TemporaryAllocator getTemporaries() { return temporaries; }

    public void pushLoop(LoopContext loop) {
        loops.push(Objects.requireNonNull(loop, "loop"));
    }

    public LoopContext currentLoop() {
        return loops.peek();
    }

    public void popLoop(LoopContext expected) {
        if (loops.isEmpty() || loops.pop() != expected) {
            throw new IllegalStateException("Loop-context stack corruption");
        }
    }

    public boolean hasLoops() {
        return !loops.isEmpty();
    }

    public int nextHandlerId() {
        return nextHandlerId++;
    }
}
