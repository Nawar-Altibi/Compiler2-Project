package compilers.flask.codegen;

import compilers.diagnostics.CompilerPhase;
import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.codegen.analysis.BindingAnalysisResult;
import compilers.flask.codegen.bytecode.BytecodeModule;
import compilers.flask.codegen.bytecode.CodeObjectBuilder;
import compilers.flask.codegen.bytecode.VerifiedBytecodeModule;
import compilers.flask.codegen.generator.BytecodeGenerator;
import compilers.flask.codegen.verify.BytecodeAssembler;
import compilers.flask.codegen.verify.ControlFlowVerifier;

import java.util.Objects;

/** Transactional reusable entry point for Flask bytecode generation. */
public final class FlaskBytecodeCompiler {
    private final BytecodeAssembler assembler;
    private final ControlFlowVerifier verifier;

    public FlaskBytecodeCompiler() {
        this(new BytecodeAssembler(), new ControlFlowVerifier());
    }

    public FlaskBytecodeCompiler(
            BytecodeAssembler assembler, ControlFlowVerifier verifier) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    public GenerationResult compile(
            ProgramNode program,
            BindingAnalysisResult bindings,
            String sourceFile,
            String moduleName) {
        return compile(program, bindings, sourceFile, moduleName,
                new DiagnosticReporter());
    }

    public GenerationResult compile(
            ProgramNode program,
            BindingAnalysisResult bindings,
            String sourceFile,
            String moduleName,
            DiagnosticReporter reporter) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(reporter, "reporter");
        if (reporter.hasErrors()) {
            return GenerationResult.failure(reporter);
        }

        CodeObjectBuilder symbolic;
        try {
            symbolic = new BytecodeGenerator(
                    program, bindings, reporter, sourceFile, moduleName).generate();
        } catch (RuntimeException internalFailure) {
            reportInternal(
                    reporter,
                    CompilerPhase.CODE_GENERATION,
                    internalFailure,
                    program.getSourceSpan(),
                    sourceFile);
            return GenerationResult.failure(
                    reporter, internalFailure, CompilerPhase.CODE_GENERATION);
        }
        if (symbolic == null || reporter.hasErrors()) {
            return GenerationResult.failure(reporter);
        }

        BytecodeModule assembled;
        try {
            assembled = assembler.assembleModule(
                    sourceFile,
                    moduleName == null || moduleName.isEmpty() ? "__main__" : moduleName,
                    symbolic,
                    reporter);
        } catch (RuntimeException internalFailure) {
            reportInternal(
                    reporter,
                    CompilerPhase.CODE_GENERATION,
                    internalFailure,
                    program.getSourceSpan(),
                    sourceFile);
            return GenerationResult.failure(
                    reporter, internalFailure, CompilerPhase.CODE_GENERATION);
        }
        if (assembled == null || reporter.hasErrors()) {
            return GenerationResult.failure(reporter);
        }

        final VerifiedBytecodeModule verified;
        try {
            verified = verifier.verify(assembled, reporter);
        } catch (RuntimeException internalFailure) {
            reportInternal(
                    reporter,
                    CompilerPhase.BYTECODE_VERIFICATION,
                    internalFailure,
                    program.getSourceSpan(),
                    sourceFile);
            return GenerationResult.failure(
                    reporter, internalFailure, CompilerPhase.BYTECODE_VERIFICATION);
        }
        if (verified == null || reporter.hasErrors()) {
            return GenerationResult.failure(reporter);
        }
        return GenerationResult.success(verified, reporter);
    }

    private static void reportInternal(
            DiagnosticReporter reporter,
            CompilerPhase phase,
            RuntimeException failure,
            SourceSpan span,
            String fallbackSource) {
        SourceSpan location = span == null ? SourceSpan.UNKNOWN : span;
        String message = failure.getMessage();
        reporter.report(Diagnostics.internalCompilerError(
                phase,
                message == null || message.isEmpty()
                        ? failure.getClass().getSimpleName()
                        : message,
                location.getStartLine(),
                location.getStartColumn(),
                location.isKnown() ? location.getSourceFile() : fallbackSource));
    }
}
