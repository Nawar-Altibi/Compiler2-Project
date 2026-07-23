package compilers.flask.codegen;

import compilers.diagnostics.CompilerPhase;
import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.codegen.bytecode.VerifiedBytecodeModule;

import java.util.Objects;

/**
 * Transactional result of bytecode generation, assembly, and verification.
 *
 * <p>An executable artifact is exposed only when every stage succeeded.  The
 * reporter is retained on both success and failure so callers do not need a
 * second error channel.</p>
 */
public final class GenerationResult {
    private final VerifiedBytecodeModule module;
    private final DiagnosticReporter reporter;
    private final RuntimeException internalFailure;
    private final CompilerPhase internalFailurePhase;

    private GenerationResult(
            VerifiedBytecodeModule module,
            DiagnosticReporter reporter,
            RuntimeException internalFailure,
            CompilerPhase internalFailurePhase) {
        this.module = module;
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.internalFailure = internalFailure;
        this.internalFailurePhase = internalFailurePhase;
        if (module != null && reporter.hasErrors()) {
            throw new IllegalArgumentException(
                    "A verified bytecode module cannot accompany compiler errors");
        }
        if ((internalFailure == null) != (internalFailurePhase == null)) {
            throw new IllegalArgumentException(
                    "An internal failure and its compiler phase must be recorded together");
        }
        if (module != null && internalFailure != null) {
            throw new IllegalArgumentException(
                    "An internal compiler failure cannot expose verified bytecode");
        }
    }

    public static GenerationResult success(
            VerifiedBytecodeModule module, DiagnosticReporter reporter) {
        return new GenerationResult(
                Objects.requireNonNull(module, "module"), reporter, null, null);
    }

    /** Ordinary user-facing generation failure with no preserved Java cause. */
    public static GenerationResult failure(DiagnosticReporter reporter) {
        return new GenerationResult(null, reporter, null, null);
    }

    /**
     * Internal compiler failure.  The reporter is expected to already contain
     * the single INTERNAL_COMPILER_ERROR diagnostic for this cause.
     */
    public static GenerationResult failure(
            DiagnosticReporter reporter,
            RuntimeException internalFailure,
            CompilerPhase internalFailurePhase) {
        return new GenerationResult(
                null,
                reporter,
                Objects.requireNonNull(internalFailure, "internalFailure"),
                Objects.requireNonNull(internalFailurePhase, "internalFailurePhase"));
    }

    /** Phase-first convenience overload retained for natural call-site ordering. */
    public static GenerationResult failure(
            DiagnosticReporter reporter,
            CompilerPhase internalFailurePhase,
            RuntimeException internalFailure) {
        return failure(reporter, internalFailure, internalFailurePhase);
    }

    public boolean isSuccess() {
        return module != null && !reporter.hasErrors();
    }

    public VerifiedBytecodeModule getModule() {
        return module;
    }

    public VerifiedBytecodeModule requireModule() {
        if (!isSuccess()) {
            throw new IllegalStateException("Generation did not produce an executable module");
        }
        return module;
    }

    public DiagnosticReporter getReporter() {
        return reporter;
    }

    public boolean hasInternalFailure() {
        return internalFailure != null;
    }

    /** Preserved Java cause for an opt-in debug presentation layer. */
    public RuntimeException getInternalFailure() {
        return internalFailure;
    }

    public CompilerPhase getInternalFailurePhase() {
        return internalFailurePhase;
    }
}
