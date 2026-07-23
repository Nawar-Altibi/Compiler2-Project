package compilers.flask.codegen.verify;

import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.codegen.bytecode.BytecodeFormat;
import compilers.flask.codegen.bytecode.BytecodeModule;
import compilers.flask.codegen.bytecode.CleanupRegion;
import compilers.flask.codegen.bytecode.CodeObject;
import compilers.flask.codegen.bytecode.CodeObjectBuilder;
import compilers.flask.codegen.bytecode.ConstantPool;
import compilers.flask.codegen.bytecode.ExceptionRegion;
import compilers.flask.codegen.bytecode.Instruction;
import compilers.flask.codegen.bytecode.InstructionLocation;
import compilers.flask.codegen.bytecode.Label;
import compilers.flask.codegen.bytecode.Operand;
import compilers.flask.codegen.bytecode.SourceMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves builder-owned labels into immutable instruction offsets. */
public final class BytecodeAssembler {

    public CodeObject assemble(
            CodeObjectBuilder builder, DiagnosticReporter reporter) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(reporter, "reporter");
        return assembleRecursive(
                builder,
                reporter,
                new IdentityHashMap<CodeObjectBuilder, Visit>(),
                new IdentityHashMap<CodeObjectBuilder, CodeObject>());
    }

    private enum Visit { VISITING, ASSEMBLED, FAILED }

    private CodeObject assembleRecursive(
            CodeObjectBuilder builder,
            DiagnosticReporter reporter,
            IdentityHashMap<CodeObjectBuilder, Visit> visits,
            IdentityHashMap<CodeObjectBuilder, CodeObject> assembled) {
        Visit visit = visits.get(builder);
        if (visit == Visit.ASSEMBLED) {
            return assembled.get(builder);
        }
        if (visit == Visit.VISITING) {
            reporter.report(Diagnostics.invalidCodegenContext(
                    "Cycle in symbolic nested CodeObject builders",
                    0, 0, builder.getSourceFile()));
            visits.put(builder, Visit.FAILED);
            return null;
        }
        if (visit == Visit.FAILED) {
            return null;
        }
        visits.put(builder, Visit.VISITING);
        try {
            if (!builder.isSealed()) {
                builder.seal();
            }
            List<CodeObjectBuilder.Entry> entries = builder.getEntries();
            Map<Label, Integer> offsets = resolvePlacements(entries);
            for (Label label : builder.getLabels()) {
                if (!offsets.containsKey(label)) {
                    reportUnresolved(
                            reporter, label, SourceSpan.UNKNOWN,
                            builder.getSourceFile());
                    return null;
                }
            }
            List<Instruction> instructions = new ArrayList<>();
            List<InstructionLocation> locations = new ArrayList<>();

            for (CodeObjectBuilder.Entry entry : entries) {
                if (!(entry instanceof CodeObjectBuilder.InstructionEntry)) {
                    continue;
                }
                CodeObjectBuilder.InstructionEntry instructionEntry =
                        (CodeObjectBuilder.InstructionEntry) entry;
                Operand operand = instructionEntry.getOperand();
                if (instructionEntry.hasSymbolicJump()) {
                    Integer target = offsets.get(instructionEntry.getJumpTarget());
                    if (target == null) {
                        reportUnresolved(
                                reporter,
                                instructionEntry.getJumpTarget(),
                                instructionEntry.getLocation().getSpan(),
                                builder.getSourceFile());
                        return null;
                    }
                    operand = new Operand.JumpOperand(target);
                }
                instructions.add(new Instruction(instructionEntry.getOpCode(), operand));
                locations.add(instructionEntry.getLocation());
            }

            List<ExceptionRegion> exceptionRegions = resolveExceptionRegions(
                    builder, offsets, reporter);
            if (exceptionRegions == null) {
                return null;
            }
            List<CleanupRegion> cleanupRegions = resolveCleanupRegions(
                    builder, offsets, reporter);
            if (cleanupRegions == null) {
                return null;
            }

            ConstantPool constants = builder.buildConstantPool(
                    nested -> assembleRecursive(
                            nested, reporter, visits, assembled));
            if (reporter.hasErrors()) {
                visits.put(builder, Visit.FAILED);
                return null;
            }

            CodeObject result = new CodeObject(
                    BytecodeFormat.CURRENT_VERSION,
                    builder.getCodeId(),
                    builder.getKind(),
                    builder.getName(),
                    builder.getQualifiedName(),
                    builder.getSourceFile(),
                    instructions,
                    constants,
                    builder.buildNamePool(),
                    builder.getSignature(),
                    builder.getFastLocalNames(),
                    builder.getCellVariableNames(),
                    builder.getFreeVariableNames(),
                    builder.getTemporarySlotCount(),
                    exceptionRegions,
                    cleanupRegions,
                    new SourceMap(locations),
                    Collections.emptyList(),
                    -1);
            assembled.put(builder, result);
            visits.put(builder, Visit.ASSEMBLED);
            return result;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            reporter.report(Diagnostics.invalidCodegenContext(
                    failure.getMessage() == null
                            ? "Bytecode assembly failed"
                            : failure.getMessage(),
                    0,
                    0,
                    builder.getSourceFile()));
            visits.put(builder, Visit.FAILED);
            return null;
        }
    }

    public BytecodeModule assembleModule(
            String sourceName,
            String moduleName,
            CodeObjectBuilder rootBuilder,
            DiagnosticReporter reporter) {
        CodeObject root = assemble(rootBuilder, reporter);
        return root == null || reporter.hasErrors()
                ? null
                : new BytecodeModule(sourceName, moduleName, root);
    }

    private static Map<Label, Integer> resolvePlacements(
            List<CodeObjectBuilder.Entry> entries) {
        IdentityHashMap<Label, Integer> offsets = new IdentityHashMap<>();
        int offset = 0;
        for (CodeObjectBuilder.Entry entry : entries) {
            if (entry instanceof CodeObjectBuilder.LabelEntry) {
                Label label = ((CodeObjectBuilder.LabelEntry) entry).getLabel();
                if (offsets.put(label, offset) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate label placement: " + label);
                }
            } else if (entry instanceof CodeObjectBuilder.InstructionEntry) {
                offset++;
            } else {
                throw new IllegalArgumentException(
                        "Unknown symbolic builder entry: " + entry.getClass().getName());
            }
        }
        return offsets;
    }

    private static List<ExceptionRegion> resolveExceptionRegions(
            CodeObjectBuilder builder,
            Map<Label, Integer> offsets,
            DiagnosticReporter reporter) {
        List<ExceptionRegion> result = new ArrayList<>();
        for (CodeObjectBuilder.ExceptionRegionSpec spec
                : builder.getExceptionRegionSpecs()) {
            Integer start = requireOffset(spec.getStart(), offsets, builder, reporter);
            Integer end = requireOffset(spec.getEnd(), offsets, builder, reporter);
            Integer handler = requireOffset(spec.getHandler(), offsets, builder, reporter);
            if (start == null || end == null || handler == null) {
                return null;
            }
            result.add(new ExceptionRegion(
                    spec.getId(),
                    spec.getParentBoundaryId(),
                    spec.getNestingDepth(),
                    start,
                    end,
                    handler,
                    null));
        }
        return result;
    }

    private static List<CleanupRegion> resolveCleanupRegions(
            CodeObjectBuilder builder,
            Map<Label, Integer> offsets,
            DiagnosticReporter reporter) {
        List<CleanupRegion> result = new ArrayList<>();
        for (CodeObjectBuilder.CleanupRegionSpec spec
                : builder.getCleanupRegionSpecs()) {
            Integer start = requireOffset(spec.getStart(), offsets, builder, reporter);
            Integer end = requireOffset(spec.getEnd(), offsets, builder, reporter);
            Integer handler = optionalOffset(
                    spec.getHandler(), offsets, builder, reporter);
            Integer continuation = optionalOffset(
                    spec.getNormalContinuation(), offsets, builder, reporter);
            if (start == null || end == null
                    || handler == null || continuation == null) {
                return null;
            }
            result.add(new CleanupRegion(
                    spec.getId(),
                    spec.getParentBoundaryId(),
                    spec.getNestingDepth(),
                    spec.getKind(),
                    start,
                    end,
                    handler,
                    continuation,
                    spec.getResourceSlots(),
                    spec.getHandlerId(),
                    spec.getAlias(),
                    null));
        }
        return result;
    }

    private static Integer optionalOffset(
            Label label,
            Map<Label, Integer> offsets,
            CodeObjectBuilder builder,
            DiagnosticReporter reporter) {
        return label == null ? -1 : requireOffset(label, offsets, builder, reporter);
    }

    private static Integer requireOffset(
            Label label,
            Map<Label, Integer> offsets,
            CodeObjectBuilder builder,
            DiagnosticReporter reporter) {
        Integer offset = offsets.get(label);
        if (offset == null) {
            reportUnresolved(reporter, label, SourceSpan.UNKNOWN, builder.getSourceFile());
        }
        return offset;
    }

    private static void reportUnresolved(
            DiagnosticReporter reporter,
            Label label,
            SourceSpan span,
            String fallbackSource) {
        SourceSpan location = span == null ? SourceSpan.UNKNOWN : span;
        String source = location.getSourceFile().isEmpty()
                ? fallbackSource
                : location.getSourceFile();
        reporter.report(Diagnostics.unresolvedLabel(
                String.valueOf(label),
                location.getStartLine(),
                location.getStartColumn(),
                source));
    }
}
