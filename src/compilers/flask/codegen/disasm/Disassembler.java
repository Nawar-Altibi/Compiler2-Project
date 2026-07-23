package compilers.flask.codegen.disasm;

import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.codegen.bytecode.BytecodeModule;
import compilers.flask.codegen.bytecode.CleanupRegion;
import compilers.flask.codegen.bytecode.CodeObject;
import compilers.flask.codegen.bytecode.ExceptionRegion;
import compilers.flask.codegen.bytecode.Instruction;
import compilers.flask.codegen.bytecode.InstructionLocation;
import compilers.flask.codegen.bytecode.Operand;
import compilers.flask.codegen.bytecode.StackAnchor;
import compilers.flask.codegen.bytecode.VerifiedBytecodeModule;
import compilers.flask.vm.values.PyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic UTF-8/LF textual representation of a bytecode module. */
public final class Disassembler {

    public String disassemble(VerifiedBytecodeModule verified) {
        if (verified == null || !verified.hasValidVerificationStamp()) {
            throw new IllegalArgumentException("A valid verified module is required");
        }
        return disassemble(verified.getModule());
    }

    /** Inspection-only overload; a raw module remains non-executable. */
    public String disassemble(BytecodeModule module) {
        if (module == null) {
            throw new IllegalArgumentException("module cannot be null");
        }
        StringBuilder output = new StringBuilder();
        output.append("FBC\\0 magic=0x")
                .append(String.format(java.util.Locale.ROOT, "%08X", module.getMagic()))
                .append(" version=").append(module.getFormatVersion())
                .append(" module=").append(module.getModuleName())
                .append(" source=").append(displaySource(module.getSourceName()))
                .append('\n');
        IdentityHashMap<CodeObject, Boolean> visited = new IdentityHashMap<>();
        appendCode(output, module.getRootCode(), "", visited);
        return output.toString();
    }

    private static void appendCode(
            StringBuilder output,
            CodeObject code,
            String indent,
            IdentityHashMap<CodeObject, Boolean> visited) {
        if (visited.put(code, Boolean.TRUE) != null) {
            output.append(indent).append("code-ref #").append(code.getCodeId()).append('\n');
            return;
        }
        output.append(indent).append("code #").append(code.getCodeId())
                .append(' ').append(code.getKind())
                .append(' ').append(code.getQualifiedName())
                .append(" version=").append(code.getFormatVersion())
                .append(" maxStack=")
                .append(code.isVerified() ? code.getMaxStack() : "<unverified>")
                .append(" temps=").append(code.getTemporarySlotCount())
                .append('\n');
        output.append(indent).append("  signature parameters=")
                .append(code.getSignature().getParameterNames())
                .append(" defaults=")
                .append(code.getSignature().getDefaultedParameterNames())
                .append('\n');
        output.append(indent).append("  fast=").append(code.getFastLocalNames()).append('\n');
        output.append(indent).append("  cells=").append(code.getCellVariableNames()).append('\n');
        output.append(indent).append("  free=").append(code.getFreeVariableNames()).append('\n');

        output.append(indent).append("  constants:").append('\n');
        for (int i = 0; i < code.getConstantPool().size(); i++) {
            Object constant = code.getConstantPool().get(i);
            output.append(indent).append("    [").append(i).append("] ")
                    .append(renderConstant(constant)).append('\n');
        }
        output.append(indent).append("  names:").append('\n');
        for (int i = 0; i < code.getNamePool().size(); i++) {
            output.append(indent).append("    [").append(i).append("] ")
                    .append(code.getNamePool().get(i)).append('\n');
        }

        output.append(indent).append("  anchors:").append('\n');
        List<StackAnchor> anchors = new ArrayList<>(code.getStackAnchors());
        anchors.sort(java.util.Comparator
                .comparingInt(StackAnchor::getOffset)
                .thenComparingInt(StackAnchor::getId));
        for (StackAnchor anchor : anchors) {
            output.append(indent).append("    ")
                    .append(renderAnchor(anchor)).append('\n');
        }

        output.append(indent).append("  exception-regions:").append('\n');
        List<ExceptionRegion> exceptionRegions =
                new ArrayList<>(code.getExceptionRegions());
        exceptionRegions.sort(java.util.Comparator.comparingInt(ExceptionRegion::getId));
        for (ExceptionRegion region : exceptionRegions) {
            output.append(indent).append("    E").append(region.getId())
                    .append(" parent=").append(region.getParentBoundaryId())
                    .append(" depth=").append(region.getNestingDepth())
                    .append(" protected=[").append(region.getStartOffset())
                    .append(',').append(region.getEndOffset()).append(")")
                    .append(" handler=").append(region.getHandlerOffset())
                    .append(" anchor=").append(renderAnchor(region.getAnchor()))
                    .append('\n');
        }

        output.append(indent).append("  cleanup-regions:").append('\n');
        List<CleanupRegion> cleanupRegions =
                new ArrayList<>(code.getCleanupRegions());
        cleanupRegions.sort(java.util.Comparator.comparingInt(CleanupRegion::getId));
        for (CleanupRegion region : cleanupRegions) {
            output.append(indent).append("    C").append(region.getId())
                    .append(' ').append(region.getKind())
                    .append(" parent=").append(region.getParentBoundaryId())
                    .append(" depth=").append(region.getNestingDepth())
                    .append(" protected=[").append(region.getStartOffset())
                    .append(',').append(region.getEndOffset()).append(")")
                    .append(" handler=").append(region.getHandlerOffset())
                    .append(" normal=").append(region.getNormalContinuationOffset())
                    .append(" resources=").append(region.getResourceSlots())
                    .append(" handlerId=").append(region.getHandlerId())
                    .append(" alias=")
                    .append(region.getAlias() == null
                            ? "<none>"
                            : region.getAlias().toString())
                    .append(" anchor=").append(renderAnchor(region.getAnchor()))
                    .append('\n');
        }

        Set<Integer> targets = new TreeSet<>();
        for (Instruction instruction : code.getInstructions()) {
            if (instruction.getOperand() instanceof Operand.JumpOperand) {
                targets.add(((Operand.JumpOperand) instruction.getOperand()).getTargetOffset());
            }
        }
        output.append(indent).append("  instructions:").append('\n');
        for (int offset = 0; offset < code.getInstructions().size(); offset++) {
            if (targets.contains(offset)) {
                output.append(indent).append("  L").append(pad(offset)).append(':').append('\n');
            }
            Instruction instruction = code.getInstructions().get(offset);
            output.append(indent).append("    ").append(pad(offset)).append(' ')
                    .append(instruction.getOpCode().name());
            String operand = renderOperand(code, instruction.getOperand());
            if (!operand.isEmpty()) {
                output.append(' ').append(operand);
            }
            InstructionLocation location = code.getSourceMap().get(offset);
            output.append("  ; ").append(renderLocation(location)).append('\n');
        }
        if (targets.contains(code.getInstructions().size())) {
            output.append(indent).append("  L")
                    .append(pad(code.getInstructions().size())).append(':').append('\n');
        }

        for (CodeObject nested : code.getNestedCodeObjects()) {
            appendCode(output, nested, indent + "  ", visited);
        }
    }

    private static String renderOperand(CodeObject code, Operand operand) {
        if (operand instanceof Operand.NoOperand) {
            return "";
        }
        if (operand instanceof Operand.JumpOperand) {
            int target = ((Operand.JumpOperand) operand).getTargetOffset();
            return "L" + pad(target) + " (" + target + ")";
        }
        if (operand instanceof Operand.ConstOperand) {
            int index = ((Operand.ConstOperand) operand).getIndex();
            return index >= code.getConstantPool().size()
                    ? String.valueOf(index)
                    : index + " (" + renderConstant(
                            code.getConstantPool().get(index)) + ")";
        }
        if (operand instanceof Operand.NameOperand) {
            int index = ((Operand.NameOperand) operand).getIndex();
            return index >= code.getNamePool().size()
                    ? String.valueOf(index)
                    : index + " (" + code.getNamePool().get(index) + ")";
        }
        if (operand instanceof Operand.LocalSlotOperand) {
            int index = ((Operand.LocalSlotOperand) operand).getIndex();
            return index >= code.getFastLocalNames().size()
                    ? String.valueOf(index)
                    : index + " (" + code.getFastLocalNames().get(index) + ")";
        }
        if (operand instanceof Operand.DerefSlotOperand) {
            int index = ((Operand.DerefSlotOperand) operand).getIndex();
            List<String> derefNames = new ArrayList<>(code.getCellVariableNames());
            derefNames.addAll(code.getFreeVariableNames());
            return index >= derefNames.size()
                    ? String.valueOf(index)
                    : index + " (" + derefNames.get(index) + ")";
        }
        if (operand instanceof Operand.TempSlotOperand) {
            return "t" + ((Operand.TempSlotOperand) operand).getIndex();
        }
        return operand.toString();
    }

    private static String renderAnchor(StackAnchor anchor) {
        if (anchor == null) {
            return "<unresolved>";
        }
        return "A" + anchor.getId() + "@" + anchor.getOffset()
                + " depth=" + anchor.getDepth()
                + " stack=" + anchor.getStackValues();
    }

    private static String renderConstant(Object constant) {
        if (constant instanceof CodeObject) {
            CodeObject code = (CodeObject) constant;
            return "code-ref #" + code.getCodeId() + " " + code.getKind()
                    + " " + code.getQualifiedName();
        }
        PyValue value = (PyValue) constant;
        return value.getTypeName() + " " + value.repr();
    }

    private static String renderLocation(InstructionLocation location) {
        SourceSpan span = location.getSpan();
        String rendered = span.isKnown()
                ? displaySource(span.getSourceFile()) + ":"
                        + span.getStartLine() + ":" + span.getStartColumn()
                : "<unknown>:0:0";
        return location.isSynthetic() ? rendered + " [synthetic]" : rendered;
    }

    private static String displaySource(String source) {
        if (source == null || source.isEmpty()) {
            return "<unknown>";
        }
        String normalized = source.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private static String pad(int offset) {
        return String.format(java.util.Locale.ROOT, "%04d", offset);
    }
}
