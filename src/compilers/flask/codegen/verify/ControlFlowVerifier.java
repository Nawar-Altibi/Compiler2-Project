package compilers.flask.codegen.verify;

import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.codegen.analysis.ResolvedName;
import compilers.flask.codegen.bytecode.BytecodeFormat;
import compilers.flask.codegen.bytecode.BytecodeModule;
import compilers.flask.codegen.bytecode.CleanupRegion;
import compilers.flask.codegen.bytecode.CodeKind;
import compilers.flask.codegen.bytecode.CodeObject;
import compilers.flask.codegen.bytecode.ConstantPool;
import compilers.flask.codegen.bytecode.ExceptionRegion;
import compilers.flask.codegen.bytecode.FunctionSignature;
import compilers.flask.codegen.bytecode.Instruction;
import compilers.flask.codegen.bytecode.InstructionLocation;
import compilers.flask.codegen.bytecode.OpCode;
import compilers.flask.codegen.bytecode.Operand;
import compilers.flask.codegen.bytecode.StackAnchor;
import compilers.flask.codegen.bytecode.StackValueDescriptor;
import compilers.flask.codegen.bytecode.VerifiedBytecodeModule;
import compilers.flask.vm.values.PyValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

/**
 * Typed, path-sensitive verifier for assembled Flask bytecode.
 *
 * <p>Verification is deliberately a deep immutable rebuild.  The assembled
 * module and every original code object remain untouched; only the rebuilt
 * graph receives verifier-owned anchors and {@code maxStack}.</p>
 */
public final class ControlFlowVerifier {
    private static final int MAX_DYNAMIC_STACK_ARITY = 1_000_000;
    private static final Set<OpCode> HANDLED_OPCODES =
            Collections.unmodifiableSet(EnumSet.copyOf(StackTransition.handledOpcodes()));

    /** Result contract which never exposes a module when any error was found. */
    public static final class VerificationResult {
        private final VerifiedBytecodeModule module;
        private final List<Diagnostic> diagnostics;

        private VerificationResult(
                VerifiedBytecodeModule module, List<Diagnostic> diagnostics) {
            this.module = module;
            this.diagnostics = Collections.unmodifiableList(
                    new ArrayList<>(diagnostics));
        }

        public boolean isSuccess() {
            return module != null && diagnostics.isEmpty();
        }

        public VerifiedBytecodeModule getModule() {
            return module;
        }

        public List<Diagnostic> getDiagnostics() {
            return diagnostics;
        }
    }

    /**
     * Unforgeable verifier capability consumed by {@code VerifiedBytecodeModule}.
     * Its constructor is private and it is bound to exact module identity.
     */
    public static final class VerificationStamp {
        private final BytecodeModule moduleIdentity;
        private final int magic;
        private final int formatVersion;
        private final String fingerprint;

        private VerificationStamp(BytecodeModule module) {
            this.moduleIdentity = Objects.requireNonNull(module, "module");
            this.magic = module.getMagic();
            this.formatVersion = module.getFormatVersion();
            this.fingerprint = fingerprint(module);
        }

        /** Revalidates identity, exact format, and deterministic content. */
        public boolean matches(BytecodeModule module) {
            return moduleIdentity == module
                    && magic == BytecodeFormat.MAGIC
                    && formatVersion == BytecodeFormat.CURRENT_VERSION
                    && module.getMagic() == magic
                    && module.getFormatVersion() == formatVersion
                    && fingerprint.equals(fingerprint(module));
        }
    }

    /** Verifies a module and returns diagnostics without throwing for bad bytecode. */
    public VerificationResult verify(BytecodeModule module) {
        Session session = new Session(module);
        if (module == null) {
            session.reportHeader("Bytecode module cannot be null");
            return new VerificationResult(null, session.diagnostics);
        }
        if (module.getMagic() != BytecodeFormat.MAGIC) {
            session.reportHeader(
                    "Invalid bytecode magic 0x"
                            + Integer.toHexString(module.getMagic())
                            + "; expected 0x"
                            + Integer.toHexString(BytecodeFormat.MAGIC));
            return new VerificationResult(null, session.diagnostics);
        }
        if (module.getFormatVersion() != BytecodeFormat.CURRENT_VERSION) {
            session.reportUnsupportedVersion(module.getFormatVersion());
            return new VerificationResult(null, session.diagnostics);
        }
        if (module.getRootCode().getKind() != CodeKind.MODULE) {
            session.report(module.getRootCode(), -1,
                    "Root code object must have kind MODULE");
        }

        CodeObject verifiedRoot = session.verifyRecursively(
                module.getRootCode(), true);
        if (!session.diagnostics.isEmpty() || verifiedRoot == null) {
            return new VerificationResult(null, session.diagnostics);
        }

        BytecodeModule verifiedModule = module.withRootCode(verifiedRoot);
        VerificationStamp stamp = new VerificationStamp(verifiedModule);
        VerifiedBytecodeModule executable =
                VerifiedBytecodeModule.fromVerifier(verifiedModule, stamp);
        return new VerificationResult(executable, session.diagnostics);
    }

    /** Convenience bridge for compiler stages already using a reporter. */
    public VerifiedBytecodeModule verify(
            BytecodeModule module, DiagnosticReporter reporter) {
        Objects.requireNonNull(reporter, "reporter");
        VerificationResult result = verify(module);
        reporter.reportAll(result.getDiagnostics());
        return result.getModule();
    }

    /** Immutable opcode-coverage inventory; adding an opcode is a test gate. */
    public static Set<OpCode> handledOpcodes() {
        return HANDLED_OPCODES;
    }

    private static final class Session {
        private enum Visit { VISITING, VERIFIED, FAILED }

        private final BytecodeModule module;
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private final IdentityHashMap<CodeObject, Visit> visits =
                new IdentityHashMap<>();
        private final IdentityHashMap<CodeObject, CodeObject> verifiedCopies =
                new IdentityHashMap<>();
        private final Map<Integer, CodeObject> codeIds = new LinkedHashMap<>();

        private Session(BytecodeModule module) {
            this.module = module;
        }

        private CodeObject verifyRecursively(CodeObject code, boolean root) {
            Visit visit = visits.get(code);
            if (visit == Visit.VISITING) {
                report(code, -1,
                        "Cycle in nested CodeObject constants at code id "
                                + code.getCodeId());
                visits.put(code, Visit.FAILED);
                return null;
            }
            if (visit == Visit.VERIFIED) {
                return verifiedCopies.get(code);
            }
            if (visit == Visit.FAILED) {
                return null;
            }

            visits.put(code, Visit.VISITING);
            int errorsBefore = diagnostics.size();
            CodeObject sameId = codeIds.putIfAbsent(code.getCodeId(), code);
            if (sameId != null && sameId != code) {
                report(code, -1,
                        "Duplicate code id " + code.getCodeId()
                                + " is used by distinct CodeObjects");
            }
            if (code.getFormatVersion() != BytecodeFormat.CURRENT_VERSION) {
                report(code, -1,
                        "CodeObject version " + code.getFormatVersion()
                                + " does not match module version "
                                + BytecodeFormat.CURRENT_VERSION);
            }
            if (!root && code.getKind() == CodeKind.MODULE) {
                report(code, -1,
                        "A nested CodeObject constant cannot have kind MODULE");
            }
            validateCodeLayout(code, root);

            List<Object> verifiedEntries = new ArrayList<>();
            for (Object entry : code.getConstantPool().getEntries()) {
                if (entry instanceof CodeObject) {
                    CodeObject verifiedNested = verifyRecursively(
                            (CodeObject) entry, false);
                    verifiedEntries.add(verifiedNested == null ? entry : verifiedNested);
                } else {
                    verifiedEntries.add(entry);
                }
            }

            CodeVerification verification = verifyCode(code);
            if (diagnostics.size() != errorsBefore || verification == null) {
                visits.put(code, Visit.FAILED);
                return null;
            }

            ConstantPool verifiedConstants;
            try {
                verifiedConstants = new ConstantPool(verifiedEntries);
            } catch (IllegalArgumentException failure) {
                report(code, -1, "Invalid rebuilt constant pool: " + failure.getMessage());
                visits.put(code, Visit.FAILED);
                return null;
            }

            CodeObject verified = code.withVerification(
                    verifiedConstants,
                    verification.exceptionRegions,
                    verification.cleanupRegions,
                    verification.anchors,
                    verification.maxStack);
            verifiedCopies.put(code, verified);
            visits.put(code, Visit.VERIFIED);
            return verified;
        }

        private void validateCodeLayout(CodeObject code, boolean root) {
            reportLayoutOverlap(
                    code, "fast and cell",
                    code.getFastLocalNames(), code.getCellVariableNames());
            reportLayoutOverlap(
                    code, "fast and free",
                    code.getFastLocalNames(), code.getFreeVariableNames());
            reportLayoutOverlap(
                    code, "cell and free",
                    code.getCellVariableNames(), code.getFreeVariableNames());

            if (code.getKind() == CodeKind.FUNCTION) {
                for (String parameter : code.getSignature().getParameterNames()) {
                    boolean fast = code.getFastLocalNames().contains(parameter);
                    boolean cell = code.getCellVariableNames().contains(parameter);
                    if (fast == cell) {
                        report(code, -1, "Function parameter '" + parameter
                                + "' must occupy exactly one fast/cell slot");
                    }
                }
            } else if (!code.getSignature().getParameterNames().isEmpty()
                    || !code.getSignature()
                            .getDefaultedParameterNames().isEmpty()) {
                report(code, -1,
                        code.getKind() + " code may not declare a function signature");
            }

            if (root && code.getKind() == CodeKind.MODULE
                    && (!code.getFastLocalNames().isEmpty()
                    || !code.getCellVariableNames().isEmpty()
                    || !code.getFreeVariableNames().isEmpty())) {
                report(code, -1,
                        "Module root may not declare fast, cell, or free-variable slots");
            }
        }

        private void reportLayoutOverlap(
                CodeObject code,
                String label,
                List<String> left,
                List<String> right) {
            Set<String> overlap = new LinkedHashSet<>(left);
            overlap.retainAll(right);
            if (!overlap.isEmpty()) {
                report(code, -1, "Names overlap between " + label
                        + " variable tables: " + overlap);
            }
        }

        private CodeVerification verifyCode(CodeObject code) {
            int errorsBefore = diagnostics.size();
            if (!HANDLED_OPCODES.equals(OpCode.allOpcodes())) {
                report(code, -1, "Verifier opcode inventory is incomplete");
                return null;
            }
            List<Instruction> instructions = code.getInstructions();
            if (instructions.isEmpty()) {
                report(code, -1, "CodeObject has no instructions");
                return null;
            }
            if (code.getMaxStack() < 0 && !code.getStackAnchors().isEmpty()) {
                report(code, -1,
                        "Unverified code may not carry generator-supplied stack anchors");
            }

            RegionModel regions = validateRegions(code);
            validateInstructions(code, regions);
            if (diagnostics.size() != errorsBefore || regions == null) {
                return null;
            }

            FlowAnalysis flow = analyzeControlFlow(code, regions);
            if (flow == null || diagnostics.size() != errorsBefore) {
                return null;
            }
            return finalizeVerification(code, regions, flow);
        }

        private RegionModel validateRegions(CodeObject code) {
            int instructionCount = code.getInstructions().size();
            List<Boundary> boundaries = new ArrayList<>();
            Map<Integer, Boundary> byId = new LinkedHashMap<>();
            Map<Integer, CleanupRegion> cleanupById = new LinkedHashMap<>();
            Map<Integer, CleanupRegion> handlerByHandlerId = new LinkedHashMap<>();
            Map<Integer, List<CleanupRegion>> tempOwners = new LinkedHashMap<>();
            Set<Integer> specialEntries = new LinkedHashSet<>();

            for (ExceptionRegion region : code.getExceptionRegions()) {
                Boundary boundary = new Boundary(region);
                registerBoundary(code, boundary, byId);
                boundaries.add(boundary);
                if (!validProtectedRange(boundary, instructionCount)) {
                    report(code, -1,
                            "Exception region " + region.getId()
                                    + " is outside the instruction array");
                }
                if (!validInstructionOffset(region.getHandlerOffset(), instructionCount)) {
                    report(code, -1,
                            "Exception region " + region.getId()
                                    + " has invalid handler offset "
                                    + region.getHandlerOffset());
                } else {
                    specialEntries.add(region.getHandlerOffset());
                    if (boundary.containsOffset(region.getHandlerOffset())) {
                        report(code, region.getHandlerOffset(),
                                "Exception handler may not be inside its protected range");
                    }
                }
            }

            for (CleanupRegion region : code.getCleanupRegions()) {
                Boundary boundary = new Boundary(region);
                registerBoundary(code, boundary, byId);
                boundaries.add(boundary);
                if (cleanupById.put(region.getId(), region) != null) {
                    report(code, -1, "Duplicate cleanup region id " + region.getId());
                }
                if (!validProtectedRange(boundary, instructionCount)) {
                    report(code, -1,
                            "Cleanup region " + region.getId()
                                    + " is outside the instruction array");
                }

                Set<Integer> distinctSlots = new LinkedHashSet<>();
                for (Integer slot : region.getResourceSlots()) {
                    if (slot == null || slot < 0
                            || slot >= code.getTemporarySlotCount()) {
                        report(code, -1,
                                "Cleanup region " + region.getId()
                                        + " references invalid temp slot " + slot);
                        continue;
                    }
                    if (!distinctSlots.add(slot)) {
                        report(code, -1,
                                "Cleanup region " + region.getId()
                                        + " repeats temp slot " + slot);
                    }
                    tempOwners.computeIfAbsent(
                            slot, ignored -> new ArrayList<>()).add(region);
                }

                switch (region.getKind()) {
                    case FINALLY:
                        validateBytecodeCleanupOffsets(code, region, instructionCount);
                        requireEmptyResources(code, region);
                        requireNoHandlerMetadata(code, region);
                        break;
                    case WITH:
                        validateBytecodeCleanupOffsets(code, region, instructionCount);
                        if (region.getResourceSlots().size() != 1) {
                            report(code, -1,
                                    "WITH cleanup " + region.getId()
                                            + " must own exactly one temp slot");
                        }
                        requireNoHandlerMetadata(code, region);
                        break;
                    case EXCEPT_HANDLER:
                        requireStateActionOffsets(code, region);
                        if (region.getHandlerId() < 0) {
                            report(code, -1,
                                    "EXCEPT_HANDLER cleanup " + region.getId()
                                            + " has no handler id");
                        } else if (handlerByHandlerId.put(
                                region.getHandlerId(), region) != null) {
                            report(code, -1,
                                    "Duplicate exception handler id "
                                            + region.getHandlerId());
                        }
                        if (!region.getResourceSlots().isEmpty()) {
                            report(code, -1,
                                    "EXCEPT_HANDLER cleanup may not own temp slots");
                        }
                        break;
                    case TEMP_CLEAR:
                        requireStateActionOffsets(code, region);
                        if (region.getResourceSlots().isEmpty()) {
                            report(code, -1,
                                    "TEMP_CLEAR cleanup " + region.getId()
                                            + " has no slots");
                        }
                        requireNoHandlerMetadata(code, region);
                        break;
                }

                if (region.getHandlerOffset() >= 0) {
                    specialEntries.add(region.getHandlerOffset());
                    if (boundary.containsOffset(region.getHandlerOffset())) {
                        report(code, region.getHandlerOffset(),
                                "Cleanup handler may not be inside its protected range");
                    }
                }
            }

            validateBoundaryNesting(code, boundaries, byId);
            validateTempOwnerReuse(code, tempOwners);
            return new RegionModel(
                    Collections.unmodifiableList(boundaries),
                    Collections.unmodifiableMap(byId),
                    Collections.unmodifiableMap(cleanupById),
                    Collections.unmodifiableMap(handlerByHandlerId),
                    immutableListMap(tempOwners),
                    Collections.unmodifiableSet(specialEntries));
        }

        private void validateTempOwnerReuse(
                CodeObject code,
                Map<Integer, List<CleanupRegion>> tempOwners) {
            for (Map.Entry<Integer, List<CleanupRegion>> entry
                    : tempOwners.entrySet()) {
                List<CleanupRegion> owners = entry.getValue();
                for (int leftIndex = 0; leftIndex < owners.size(); leftIndex++) {
                    CleanupRegion left = owners.get(leftIndex);
                    for (int rightIndex = leftIndex + 1;
                            rightIndex < owners.size(); rightIndex++) {
                        CleanupRegion right = owners.get(rightIndex);
                        if (left.getStartOffset() < right.getEndOffset()
                                && right.getStartOffset() < left.getEndOffset()) {
                            report(code, -1,
                                    "Temp slot " + entry.getKey()
                                            + " has overlapping lifetimes in cleanup "
                                            + left.getId() + " and " + right.getId());
                        }
                    }
                }
            }
        }

        private void registerBoundary(
                CodeObject code,
                Boundary boundary,
                Map<Integer, Boundary> byId) {
            Boundary previous = byId.putIfAbsent(boundary.id, boundary);
            if (previous != null) {
                report(code, -1, "Duplicate boundary id " + boundary.id);
            }
        }

        private void validateBoundaryNesting(
                CodeObject code,
                List<Boundary> boundaries,
                Map<Integer, Boundary> byId) {
            for (int leftIndex = 0; leftIndex < boundaries.size(); leftIndex++) {
                Boundary left = boundaries.get(leftIndex);
                for (int rightIndex = leftIndex + 1;
                        rightIndex < boundaries.size(); rightIndex++) {
                    Boundary right = boundaries.get(rightIndex);
                    boolean overlaps = left.start < right.end && right.start < left.end;
                    if (!overlaps) {
                        continue;
                    }
                    if (left.start == right.start && left.end == right.end) {
                        report(code, -1,
                                "Boundaries " + left.id + " and " + right.id
                                        + " have an ambiguous identical range");
                    } else if (!left.strictlyContains(right)
                            && !right.strictlyContains(left)) {
                        report(code, -1,
                                "Boundaries " + left.id + " and " + right.id
                                        + " partially overlap");
                    }
                }
            }

            List<Boundary> byWidth = new ArrayList<>(boundaries);
            // Parents must receive their depth before children consult it.
            // Sorting widest-to-narrowest makes three-or-more nested regions
            // just as reliable as the two-level case.
            byWidth.sort(Comparator
                    .comparingInt((Boundary value) -> value.end - value.start)
                    .reversed()
                    .thenComparingInt(value -> value.start)
                    .thenComparingInt(value -> value.id));
            for (Boundary boundary : byWidth) {
                Boundary parent = null;
                for (Boundary candidate : boundaries) {
                    if (!candidate.strictlyContains(boundary)) {
                        continue;
                    }
                    if (parent == null
                            || candidate.end - candidate.start
                                    < parent.end - parent.start) {
                        parent = candidate;
                    }
                }
                boundary.parentId = parent == null ? -1 : parent.id;
                boundary.depth = parent == null ? 0 : parent.depth + 1;

                int suppliedParent = boundary.type == BoundaryType.EXCEPTION
                        ? boundary.exceptionRegion.getParentBoundaryId()
                        : boundary.cleanupRegion.getParentBoundaryId();
                int suppliedDepth = boundary.type == BoundaryType.EXCEPTION
                        ? boundary.exceptionRegion.getNestingDepth()
                        : boundary.cleanupRegion.getNestingDepth();
                // (-1, 0) is the assembler's explicit unresolved sentinel.
                boolean unresolved = suppliedParent == -1 && suppliedDepth == 0
                        && parent != null;
                if (!unresolved && (suppliedParent != boundary.parentId
                        || suppliedDepth != boundary.depth)) {
                    report(code, -1,
                            "Boundary " + boundary.id
                                    + " declares parent/depth " + suppliedParent
                                    + "/" + suppliedDepth + " but lexical nesting is "
                                    + boundary.parentId + "/" + boundary.depth);
                }
                if (boundary.parentId >= 0 && !byId.containsKey(boundary.parentId)) {
                    report(code, -1,
                            "Boundary " + boundary.id + " has unknown parent "
                                    + boundary.parentId);
                }
            }
            boundaries.sort(Comparator
                    .comparingInt((Boundary value) -> value.depth).reversed()
                    .thenComparingInt(value -> value.start)
                    .thenComparingInt(value -> value.id));
        }

        private void validateInstructions(CodeObject code, RegionModel regions) {
            List<Instruction> instructions = code.getInstructions();
            for (int offset = 0; offset < instructions.size(); offset++) {
                Instruction instruction = instructions.get(offset);
                OpCode opCode = instruction.getOpCode();
                Operand operand = instruction.getOperand();
                if (operand.getKind() != opCode.getOperandKind()) {
                    report(code, offset,
                            opCode + " has operand kind " + operand.getKind()
                                    + " instead of " + opCode.getOperandKind());
                    continue;
                }
                if (opCode.mayRaise() != expectedMayRaise(opCode)) {
                    report(code, offset,
                            opCode + " has incorrect mayRaise metadata");
                }
                validateOperand(code, offset, instruction, regions);
            }

            int last = instructions.size() - 1;
            if (hasNormalFallthrough(instructions.get(last).getOpCode())) {
                report(code, last, "Instruction falls through beyond the code array");
            }
        }

        private void validateOperand(
                CodeObject code,
                int offset,
                Instruction instruction,
                RegionModel regions) {
            Operand operand = instruction.getOperand();
            OpCode opCode = instruction.getOpCode();
            if (opCode == OpCode.IMPORT_STAR
                    && code.getKind() != CodeKind.MODULE) {
                report(code, offset,
                        "IMPORT_STAR is only valid in MODULE code");
            }
            switch (operand.getKind()) {
                case NONE:
                case UNARY_OPERATOR:
                case BINARY_OPERATOR:
                case COMPARE_OPERATOR:
                    break;
                case IMPORT_SPEC: {
                    String moduleName = ((Operand.ImportSpec) operand).getModuleName();
                    if (!Operand.isCanonicalModuleName(moduleName)) {
                        report(code, offset,
                                "Invalid canonical import module name '"
                                        + moduleName + "'");
                    }
                    break;
                }
                case CONST:
                    checkIndex(code, offset, "constant",
                            ((Operand.ConstOperand) operand).getIndex(),
                            code.getConstantPool().size());
                    break;
                case NAME: {
                    int nameIndex = ((Operand.NameOperand) operand).getIndex();
                    checkIndex(code, offset, "name", nameIndex,
                            code.getNamePool().size());
                    if (opCode == OpCode.IMPORT_FROM
                            && nameIndex >= 0
                            && nameIndex < code.getNamePool().size()) {
                        String importedName = code.getNamePool().get(nameIndex);
                        if (!Operand.isIdentifier(importedName)) {
                            report(code, offset,
                                    "Invalid IMPORT_FROM identifier '"
                                            + importedName + "'");
                        }
                    }
                    break;
                }
                case LOCAL_SLOT:
                    checkIndex(code, offset, "fast-local",
                            ((Operand.LocalSlotOperand) operand).getIndex(),
                            code.getFastLocalNames().size());
                    break;
                case DEREF_SLOT:
                    checkIndex(code, offset, "cell/free",
                            ((Operand.DerefSlotOperand) operand).getIndex(),
                            code.getCellVariableNames().size()
                                    + code.getFreeVariableNames().size());
                    break;
                case TEMP_SLOT: {
                    int slot = ((Operand.TempSlotOperand) operand).getIndex();
                    checkIndex(code, offset, "temporary", slot,
                            code.getTemporarySlotCount());
                    if (!regions.tempOwners.containsKey(slot)) {
                        report(code, offset,
                                "Temp slot " + slot
                                        + " has no TEMP_CLEAR/WITH lifetime metadata");
                    } else if (matchingTempOwners(
                            opCode, offset, regions.tempOwners.get(slot)).isEmpty()) {
                        report(code, offset,
                                "Temp slot " + slot
                                        + " is accessed outside its declared lifetime");
                    }
                    break;
                }
                case JUMP: {
                    int target = ((Operand.JumpOperand) operand).getTargetOffset();
                    if (!validInstructionOffset(target, code.getInstructions().size())) {
                        report(code, offset, "Invalid jump target " + target);
                    } else if (regions.specialEntryOffsets.contains(target)) {
                        report(code, offset,
                                (opCode == OpCode.UNWIND_JUMP
                                        ? "UNWIND_JUMP targets handler/cleanup entry "
                                        : "Ordinary jump targets handler/cleanup entry ")
                                        + target);
                    }
                    break;
                }
                case COUNT: {
                    int count = ((Operand.CountOperand) operand).getCount();
                    if (count > MAX_DYNAMIC_STACK_ARITY) {
                        report(code, offset,
                                opCode + " count exceeds verifier limit: " + count);
                    }
                    if ((opCode == OpCode.COPY || opCode == OpCode.SWAP)
                            && count < 1) {
                        report(code, offset, opCode + " depth must be one-based");
                    }
                    if (opCode == OpCode.RAISE && count > 2) {
                        report(code, offset, "RAISE count must be 0, 1, or 2");
                    }
                    break;
                }
                case CALL_SPEC: {
                    Operand.CallSpec spec = (Operand.CallSpec) operand;
                    checkDistinct(code, offset, "call keyword",
                            spec.getOrderedKeywordNames());
                    checkArity(code, offset, opCode,
                            spec.getPositionalCount(), spec.getKeywordCount());
                    break;
                }
                case MAKE_FUNCTION_SPEC: {
                    Operand.MakeFunctionSpec spec =
                            (Operand.MakeFunctionSpec) operand;
                    checkDistinct(code, offset, "default parameter",
                            spec.getOrderedDefaultParameterNames());
                    checkDistinct(code, offset, "annotation",
                            spec.getOrderedAnnotationNames());
                    checkDistinct(code, offset, "free variable",
                            spec.getOrderedFreeVarNames());
                    checkArity(code, offset, opCode,
                            spec.getOrderedDefaultParameterNames().size(),
                            spec.getOrderedAnnotationNames().size(),
                            spec.getOrderedFreeVarNames().size(), 1);
                    break;
                }
                case BUILD_CLASS_SPEC: {
                    Operand.BuildClassSpec spec =
                            (Operand.BuildClassSpec) operand;
                    checkDistinct(code, offset, "class free variable",
                            spec.getOrderedFreeVarNames());
                    checkArity(code, offset, opCode,
                            spec.getBaseCount(), spec.getOrderedFreeVarNames().size(), 1);
                    break;
                }
                case CLEANUP_SPEC:
                    validateCleanupOperand(code, offset, instruction, regions);
                    break;
                case HANDLER_SPEC:
                    validateHandlerOperand(code, offset,
                            (Operand.HandlerSpec) operand, regions);
                    break;
                case WITH_SPEC:
                    validateWithOperand(code, offset,
                            (Operand.WithSpec) operand, regions);
                    break;
            }
        }

        private void validateCleanupOperand(
                CodeObject code,
                int offset,
                Instruction instruction,
                RegionModel regions) {
            int value = ((Operand.CleanupSpec) instruction.getOperand()).getCleanupId();
            OpCode opCode = instruction.getOpCode();
            if (opCode == OpCode.END_EXCEPT) {
                if (!regions.handlerByHandlerId.containsKey(value)) {
                    report(code, offset,
                            "END_EXCEPT references unknown handler id " + value);
                }
                return;
            }
            CleanupRegion cleanup = regions.cleanupById.get(value);
            if (cleanup == null) {
                report(code, offset,
                        opCode + " references unknown cleanup id " + value);
                return;
            }
            if ((opCode == OpCode.ENTER_CLEANUP || opCode == OpCode.END_CLEANUP)
                    && cleanup.getKind() != CleanupRegion.Kind.FINALLY
                    && cleanup.getKind() != CleanupRegion.Kind.WITH) {
                report(code, offset,
                        opCode + " requires FINALLY/WITH cleanup, got "
                                + cleanup.getKind());
            }
        }

        private void validateHandlerOperand(
                CodeObject code,
                int offset,
                Operand.HandlerSpec spec,
                RegionModel regions) {
            CleanupRegion cleanup = regions.cleanupById.get(spec.getCleanupRegionId());
            if (cleanup == null
                    || cleanup.getKind() != CleanupRegion.Kind.EXCEPT_HANDLER) {
                report(code, offset,
                        "BEGIN_EXCEPT references invalid EXCEPT_HANDLER cleanup "
                                + spec.getCleanupRegionId());
            } else if (cleanup.getHandlerId() != spec.getHandlerId()) {
                report(code, offset,
                        "BEGIN_EXCEPT handler id " + spec.getHandlerId()
                                + " does not match cleanup handler id "
                                + cleanup.getHandlerId());
            } else if (!sameResolvedName(
                    cleanup.getAlias(), spec.getAlias())) {
                report(code, offset,
                        "BEGIN_EXCEPT alias does not match cleanup alias");
            } else {
                validateResolvedAlias(code, offset, spec.getAlias());
            }
        }

        private boolean sameResolvedName(
                ResolvedName left, ResolvedName right) {
            if (left == right) return true;
            return left != null && right != null
                    && left.getName().equals(right.getName())
                    && left.getKind() == right.getKind()
                    && left.getSlotIndex() == right.getSlotIndex()
                    && left.getUseScope().getOrdinal()
                            == right.getUseScope().getOrdinal();
        }

        private void validateResolvedAlias(
                CodeObject code, int offset, ResolvedName alias) {
            if (alias == null) return;
            if (alias.getUseScope().getOrdinal() != code.getCodeId()) {
                report(code, offset,
                        "Except alias belongs to a different CodeObject scope");
                return;
            }
            switch (alias.getKind()) {
                case NAME:
                case GLOBAL:
                    if (alias.getSlotIndex() != -1) {
                        report(code, offset,
                                "Namespace except alias carries a layout slot");
                    }
                    return;
                case FAST:
                    validateAliasSlot(code, offset, alias,
                            code.getFastLocalNames(), 0, "fast-local");
                    return;
                case DEREF:
                    List<String> deref = new ArrayList<>();
                    deref.addAll(code.getCellVariableNames());
                    deref.addAll(code.getFreeVariableNames());
                    validateAliasSlot(code, offset, alias, deref, 0, "cell/free");
                    return;
                default:
                    report(code, offset, "Unknown except-alias binding kind");
            }
        }

        private void validateAliasSlot(
                CodeObject code,
                int offset,
                ResolvedName alias,
                List<String> names,
                int base,
                String label) {
            int slot = alias.getSlotIndex() - base;
            if (slot < 0 || slot >= names.size()
                    || !names.get(slot).equals(alias.getName())) {
                report(code, offset,
                        "Except alias has invalid " + label + " slot/name");
            }
        }

        private void validateWithOperand(
                CodeObject code,
                int offset,
                Operand.WithSpec spec,
                RegionModel regions) {
            CleanupRegion cleanup = regions.cleanupById.get(spec.getCleanupId());
            if (cleanup == null || cleanup.getKind() != CleanupRegion.Kind.WITH) {
                report(code, offset,
                        "WITH opcode references invalid WITH cleanup "
                                + spec.getCleanupId());
            } else if (cleanup.getResourceSlot() != spec.getResourceSlot()) {
                report(code, offset,
                        "WITH resource slot " + spec.getResourceSlot()
                                + " does not match cleanup slot "
                                + cleanup.getResourceSlot());
            }
            checkIndex(code, offset, "with resource", spec.getResourceSlot(),
                    code.getTemporarySlotCount());
        }

        private void checkIndex(
                CodeObject code,
                int offset,
                String label,
                int index,
                int size) {
            if (index < 0 || index >= size) {
                report(code, offset,
                        "Invalid " + label + " index " + index
                                + " for table size " + size);
            }
        }

        private void checkDistinct(
                CodeObject code,
                int offset,
                String label,
                List<String> values) {
            Set<String> distinct = new HashSet<>();
            for (String value : values) {
                if (!distinct.add(value)) {
                    report(code, offset,
                            "Duplicate " + label + " name '" + value + "'");
                }
            }
        }

        private void checkArity(
                CodeObject code,
                int offset,
                OpCode opCode,
                int... counts) {
            long total = 0;
            for (int count : counts) {
                total += count;
            }
            if (total > MAX_DYNAMIC_STACK_ARITY) {
                report(code, offset,
                        opCode + " stack arity exceeds verifier limit: " + total);
            }
        }

        private void validateBytecodeCleanupOffsets(
                CodeObject code,
                CleanupRegion region,
                int instructionCount) {
            if (!validInstructionOffset(region.getHandlerOffset(), instructionCount)) {
                report(code, -1,
                        region.getKind() + " cleanup " + region.getId()
                                + " has invalid handler offset "
                                + region.getHandlerOffset());
            }
            if (!validInstructionOffset(
                    region.getNormalContinuationOffset(), instructionCount)) {
                report(code, -1,
                        region.getKind() + " cleanup " + region.getId()
                                + " has invalid normal continuation "
                                + region.getNormalContinuationOffset());
            }
            if (validInstructionOffset(region.getHandlerOffset(), instructionCount)
                    && validInstructionOffset(
                            region.getNormalContinuationOffset(), instructionCount)
                    && region.getHandlerOffset()
                            >= region.getNormalContinuationOffset()) {
                report(code, -1,
                        region.getKind() + " cleanup " + region.getId()
                                + " handler must precede its normal continuation");
            }
        }

        private void requireStateActionOffsets(
                CodeObject code, CleanupRegion region) {
            if (region.getHandlerOffset() != -1
                    || region.getNormalContinuationOffset() != -1) {
                report(code, -1,
                        region.getKind() + " cleanup " + region.getId()
                                + " is a metadata action and may not own bytecode offsets");
            }
        }

        private void requireEmptyResources(
                CodeObject code, CleanupRegion region) {
            if (!region.getResourceSlots().isEmpty()) {
                report(code, -1,
                        region.getKind() + " cleanup " + region.getId()
                                + " may not own resource slots");
            }
        }

        private void requireNoHandlerMetadata(
                CodeObject code, CleanupRegion region) {
            if (region.getHandlerId() != -1 || region.getAlias() != null) {
                report(code, -1,
                        region.getKind() + " cleanup " + region.getId()
                                + " carries exception-handler metadata");
            }
        }

        private List<CleanupRegion> matchingTempOwners(
                OpCode opCode,
                int offset,
                List<CleanupRegion> owners) {
            List<CleanupRegion> matches = new ArrayList<>();
            for (CleanupRegion owner : owners) {
                boolean match;
                if (opCode == OpCode.STORE_TEMP) {
                    match = owner.contains(offset)
                            || offset + 1 == owner.getStartOffset();
                } else if (opCode == OpCode.CLEAR_TEMP) {
                    match = owner.contains(offset)
                            || offset == owner.getEndOffset();
                } else {
                    match = owner.contains(offset);
                }
                if (match) {
                    matches.add(owner);
                }
            }
            return matches;
        }

        private boolean validProtectedRange(Boundary boundary, int size) {
            return boundary.start >= 0 && boundary.start < boundary.end
                    && boundary.end <= size;
        }

        private boolean validInstructionOffset(int offset, int size) {
            return offset >= 0 && offset < size;
        }

        private Map<Integer, List<CleanupRegion>> immutableListMap(
                Map<Integer, List<CleanupRegion>> source) {
            Map<Integer, List<CleanupRegion>> result = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<CleanupRegion>> entry : source.entrySet()) {
                result.put(entry.getKey(), Collections.unmodifiableList(
                        new ArrayList<>(entry.getValue())));
            }
            return Collections.unmodifiableMap(result);
        }

        private FlowAnalysis analyzeControlFlow(
                CodeObject code, RegionModel regions) {
            FlowAnalysis flow = new FlowAnalysis(code.getInstructions().size());
            FlowContext context = new FlowContext(this, code, regions, flow);
            enqueue(context, 0, AbstractState.entry(), false, -1);

            while (!context.queue.isEmpty()) {
                WorkItem item = context.queue.remove();
                if (!isCurrentState(flow, item)) {
                    continue;
                }
                flow.maxStack = Math.max(flow.maxStack, item.state.stack.size());
                processInstruction(context, item.offset, item.state);
            }

            for (Boundary boundary : regions.boundaries) {
                boolean allowedUnreachable =
                        mayBeStaticallyUnreachable(boundary, regions);
                if (flow.states.get(boundary.start).isEmpty()
                        && !allowedUnreachable) {
                    report(code, boundary.start,
                            "Orphan protected-region entry for boundary "
                                    + boundary.id);
                }
                int handler = boundary.handlerOffset();
                if (handler >= 0
                        && !flow.specialIncoming.contains(handler)
                        && boundary.type != BoundaryType.EXCEPTION
                        && !allowedUnreachable) {
                    report(code, handler,
                            "Orphan handler/cleanup entry for boundary "
                                    + boundary.id);
                }
            }
            for (Integer offset : flow.ordinaryIncoming) {
                if (regions.specialEntryOffsets.contains(offset)) {
                    report(code, offset,
                            "Handler/cleanup entry is reachable by an ordinary edge");
                }
            }
            return flow;
        }

        private boolean mayBeStaticallyUnreachable(
                Boundary boundary, RegionModel regions) {
            Boundary cursor = boundary;
            while (cursor != null) {
                if (cursor.type == BoundaryType.CLEANUP
                        && cursor.cleanupRegion.getKind()
                                == CleanupRegion.Kind.EXCEPT_HANDLER) {
                    return true;
                }
                cursor = cursor.parentId < 0
                        ? null : regions.byId.get(cursor.parentId);
            }
            return false;
        }

        private boolean isCurrentState(FlowAnalysis flow, WorkItem item) {
            for (StateSlot slot : flow.states.get(item.offset)) {
                if (slot.state.sameControl(item.state)
                        && slot.state.sameTemps(item.state)) {
                    return true;
                }
            }
            return false;
        }

        private void processInstruction(
                FlowContext context,
                int offset,
                AbstractState state) {
            Instruction instruction = context.code.getInstructions().get(offset);
            OpCode opCode = instruction.getOpCode();

            if (opCode.mayRaise()) {
                AbstractState exceptionalState = state;
                if (opCode == OpCode.WITH_EXIT) {
                    exceptionalState = deactivateWithForExceptionalExit(
                            context, offset, state, instruction);
                }
                beginTransfer(
                        context, offset, exceptionalState,
                        TransferKind.EXCEPTION, -1, true);
            }

            switch (opCode) {
                case JUMP: {
                    AbstractState outgoing = applyTransition(
                            context, offset, state,
                            StackTransition.Edge.JUMP_TAKEN);
                    if (outgoing != null) {
                        enqueueOrdinary(context, offset,
                                jumpTarget(instruction), outgoing,
                                StackTransition.Edge.JUMP_TAKEN);
                    }
                    return;
                }
                case POP_JUMP_IF_FALSE:
                case POP_JUMP_IF_TRUE:
                case JUMP_IF_FALSE_OR_POP:
                case JUMP_IF_TRUE_OR_POP: {
                    AbstractState taken = applyTransition(
                            context, offset, state,
                            StackTransition.Edge.JUMP_TAKEN);
                    AbstractState fallthrough = applyTransition(
                            context, offset, state,
                            StackTransition.Edge.FALLTHROUGH);
                    if (taken != null) {
                        enqueueOrdinary(context, offset,
                                jumpTarget(instruction), taken,
                                StackTransition.Edge.JUMP_TAKEN);
                    }
                    if (fallthrough != null) {
                        enqueueFallthrough(context, offset, fallthrough);
                    }
                    return;
                }
                case FOR_ITER: {
                    AbstractState item = applyTransition(
                            context, offset, state,
                            StackTransition.Edge.ITER_ITEM);
                    AbstractState exhausted = applyTransition(
                            context, offset, state,
                            StackTransition.Edge.ITER_EXHAUSTED);
                    if (item != null) {
                        enqueueFallthrough(context, offset, item);
                    }
                    if (exhausted != null) {
                        enqueueOrdinary(context, offset,
                                jumpTarget(instruction), exhausted,
                                StackTransition.Edge.ITER_EXHAUSTED);
                    }
                    return;
                }
                case UNWIND_JUMP: {
                    AbstractState outgoing = applyTransition(
                            context, offset, state,
                            StackTransition.Edge.ABRUPT_TRANSFER);
                    if (outgoing != null) {
                        beginTransfer(context, offset, outgoing,
                                TransferKind.JUMP,
                                jumpTarget(instruction), false);
                    }
                    return;
                }
                case RETURN_VALUE: {
                    AbstractState outgoing = applyTransition(
                            context, offset, state,
                            StackTransition.Edge.ABRUPT_TRANSFER);
                    if (outgoing != null) {
                        beginTransfer(context, offset, outgoing,
                                TransferKind.RETURN, -1, false);
                    }
                    return;
                }
                case RAISE: {
                    AbstractState outgoing = applyTransition(
                            context, offset, state,
                            StackTransition.Edge.ABRUPT_TRANSFER);
                    if (outgoing != null) {
                        beginRaiseTransfer(context, offset, state, outgoing,
                                ((Operand.CountOperand) instruction.getOperand())
                                        .getCount());
                    }
                    return;
                }
                case ENTER_CLEANUP:
                    enterCleanup(context, offset, state, instruction);
                    return;
                case END_CLEANUP:
                    endCleanup(context, offset, state, instruction);
                    return;
                case WITH_EXIT:
                    processWithExit(context, offset, state, instruction);
                    return;

                case NOP:
                case POP_TOP:
                case COPY:
                case SWAP:
                case LOAD_CONST:
                case LOAD_NAME:
                case STORE_NAME:
                case DELETE_NAME:
                case LOAD_FAST:
                case STORE_FAST:
                case DELETE_FAST:
                case LOAD_GLOBAL:
                case STORE_GLOBAL:
                case DELETE_GLOBAL:
                case LOAD_DEREF:
                case STORE_DEREF:
                case DELETE_DEREF:
                case LOAD_CLOSURE:
                case LOAD_TEMP:
                case STORE_TEMP:
                case CLEAR_TEMP:
                case LOAD_ATTR:
                case STORE_ATTR:
                case DELETE_ATTR:
                case BINARY_SUBSCR:
                case STORE_SUBSCR:
                case DELETE_SUBSCR:
                case BUILD_LIST:
                case BUILD_TUPLE:
                case BUILD_SET:
                case BUILD_MAP:
                case UNPACK_SEQUENCE:
                case FORMAT_VALUE:
                case BUILD_STRING:
                case GET_ITER:
                case UNARY_OP:
                case BINARY_OP:
                case COMPARE_OP:
                case CALL:
                case MAKE_FUNCTION:
                case BUILD_CLASS:
                case IMPORT_NAME:
                case IMPORT_FROM:
                case IMPORT_STAR:
                case LOAD_CURRENT_EXCEPTION:
                case EXCEPTION_MATCH:
                case BEGIN_EXCEPT:
                case END_EXCEPT:
                case WITH_ENTER:
                {
                    AbstractState outgoing = applyTransition(
                            context, offset, state,
                            StackTransition.Edge.FALLTHROUGH);
                    if (outgoing != null) {
                        enqueueFallthrough(context, offset, outgoing);
                    }
                    return;
                }
            }
            throw new AssertionError("Unhandled opcode " + opCode);
        }

        private AbstractState deactivateWithForExceptionalExit(
                FlowContext context,
                int offset,
                AbstractState state,
                Instruction instruction) {
            Operand.WithSpec spec = (Operand.WithSpec) instruction.getOperand();
            BitSet temps = (BitSet) state.initializedTemps.clone();
            Set<Integer> active = new LinkedHashSet<>(state.activeCleanups);
            temps.clear(spec.getResourceSlot());
            active.remove(spec.getCleanupId());
            return state.with(state.stack, temps, active, state.pending);
        }

        private void processWithExit(
                FlowContext context,
                int offset,
                AbstractState state,
                Instruction instruction) {
            AbstractState outgoing = applyTransition(
                    context, offset, state, StackTransition.Edge.FALLTHROUGH);
            if (outgoing == null) return;
            enqueueFallthrough(context, offset, outgoing);

            PendingState pending = outgoing.pending;
            if (pending == null
                    || pending.kind != TransferKind.EXCEPTION) {
                return;
            }
            Operand.WithSpec spec = (Operand.WithSpec) instruction.getOperand();
            CleanupRegion cleanup = context.regions.cleanupById.get(
                    spec.getCleanupId());
            if (cleanup == null) return;
            PendingState suppressed = new PendingState(
                    TransferKind.NORMAL,
                    cleanup.getNormalContinuationOffset(),
                    pending.origin,
                    pending.visited,
                    pending.activeCleanupId,
                    false,
                    pending.suspended);
            enqueueFallthrough(
                    context, offset, outgoing.withPending(suppressed));
        }

        private void enqueueFallthrough(
                FlowContext context, int source, AbstractState state) {
            int target = source + 1;
            if (target >= context.code.getInstructions().size()) {
                report(context.code, source,
                        "Instruction falls through beyond the code array");
                return;
            }
            enqueueOrdinary(context, source, target, state);
        }

        private void enqueueOrdinary(
                FlowContext context,
                int source,
                int target,
                AbstractState state) {
            enqueueOrdinary(
                    context, source, target, state,
                    StackTransition.Edge.FALLTHROUGH);
        }

        private void enqueueOrdinary(
                FlowContext context,
                int source,
                int target,
                AbstractState state,
                StackTransition.Edge edge) {
            if (!validateOrdinaryBoundaryCrossing(
                    context, source, target, state, edge)) {
                return;
            }
            enqueue(context, target, state, false, source);
        }

        private void enqueueSpecial(
                FlowContext context,
                int source,
                int target,
                AbstractState state) {
            enqueue(context, target, state, true, source);
        }

        private void enqueue(
                FlowContext context,
                int target,
                AbstractState incoming,
                boolean special,
                int source) {
            if (!validInstructionOffset(
                    target, context.code.getInstructions().size())) {
                report(context.code, Math.max(source, 0),
                        "Control-flow edge has invalid target " + target);
                return;
            }
            if (!special && context.regions.specialEntryOffsets.contains(target)) {
                context.flow.ordinaryIncoming.add(target);
                report(context.code, source,
                        "Ordinary edge enters handler/cleanup offset " + target);
                return;
            }
            if (special) {
                context.flow.specialIncoming.add(target);
            } else if (source >= 0) {
                context.flow.ordinaryIncoming.add(target);
            }
            context.flow.incomingCounts.merge(target, 1, Integer::sum);
            context.flow.maxStack = Math.max(
                    context.flow.maxStack, incoming.stack.size());

            List<StateSlot> slots = context.flow.states.get(target);
            for (StateSlot slot : slots) {
                if (!slot.state.stack.equals(incoming.stack)) {
                    report(context.code, target,
                            "Incompatible stack join: " + slot.state.stack
                                    + " versus " + incoming.stack);
                    return;
                }
                if (slot.state.sameControl(incoming)) {
                    AbstractState merged = slot.state.intersectTemps(incoming);
                    if (!merged.sameTemps(slot.state)) {
                        slot.state = merged;
                        context.queue.add(new WorkItem(target, merged));
                    }
                    return;
                }
            }

            boolean resumableExceptionDisjunction = false;
            for (StateSlot slot : slots) {
                if (slot.state.sameResumableControlHead(incoming)) {
                    resumableExceptionDisjunction = true;
                    break;
                }
            }
            if (!slots.isEmpty()
                    && !context.regions.specialEntryOffsets.contains(target)
                    && !isActiveCleanupHandlerState(
                            target, incoming, context.regions)
                    && !resumableExceptionDisjunction) {
                report(context.code, target,
                        "Incompatible active-region or PendingTransfer state at join: "
                                + slots.get(0).state.controlSummary()
                                + " versus " + incoming.controlSummary());
                return;
            }
            StateSlot slot = new StateSlot(incoming);
            slots.add(slot);
            context.queue.add(new WorkItem(target, incoming));
        }

        private boolean isActiveCleanupHandlerState(
                int offset, AbstractState state, RegionModel regions) {
            return isActiveCleanupHandlerState(
                    offset, state.pending, regions);
        }

        /**
         * A locally dispatched exception can carry an older transfer suspended
         * in an enclosing finally/with handler.  Multiple such transfers are
         * valid at joins inside that handler; inspect the complete suspension
         * chain rather than only the top replacement transfer.
         */
        private boolean isActiveCleanupHandlerState(
                int offset, PendingState pending, RegionModel regions) {
            for (PendingState cursor = pending;
                    cursor != null; cursor = cursor.suspended) {
                if (cursor.activeCleanupId < 0) continue;
                CleanupRegion cleanup = regions.cleanupById.get(
                        cursor.activeCleanupId);
                if (cleanup != null && cleanup.getHandlerOffset() >= 0
                        && cleanup.getNormalContinuationOffset()
                                > cleanup.getHandlerOffset()
                        && offset >= cleanup.getHandlerOffset()
                        && offset < cleanup.getNormalContinuationOffset()) {
                    return true;
                }
            }
            return false;
        }

        private boolean validateOrdinaryBoundaryCrossing(
                FlowContext context,
                int source,
                int target,
                AbstractState state,
                StackTransition.Edge edge) {
            boolean isJump = target != source + 1;
            if (state.pending != null
                    && state.pending.activeCleanupId >= 0) {
                CleanupRegion active = context.regions.cleanupById.get(
                        state.pending.activeCleanupId);
                if (active != null && active.getHandlerOffset() >= 0
                        && active.getNormalContinuationOffset()
                                > active.getHandlerOffset()) {
                    boolean sourceInHandler = source >= active.getHandlerOffset()
                            && source < active.getNormalContinuationOffset();
                    boolean targetInHandler = target >= active.getHandlerOffset()
                            && target < active.getNormalContinuationOffset();
                    if (sourceInHandler && !targetInHandler) {
                        report(context.code, source,
                                "Active cleanup handler " + active.getId()
                                        + " exits without END_CLEANUP");
                        return false;
                    }
                }
            }
            for (Boundary boundary : context.regions.boundaries) {
                if (boundary.type != BoundaryType.CLEANUP) {
                    continue;
                }
                CleanupRegion cleanup = boundary.cleanupRegion;
                boolean sourceInside = cleanup.contains(source);
                boolean targetInside = cleanup.contains(target);
                if (sourceInside == targetInside) {
                    continue;
                }
                if (sourceInside && !targetInside
                        && cleanup.getKind() == CleanupRegion.Kind.TEMP_CLEAR
                        && edge == StackTransition.Edge.ITER_EXHAUSTED
                        && target == cleanup.getEndOffset()
                        && isLeadingClearFor(context.code, target, cleanup)) {
                    // Frozen for-loop lowering: exhaustion lands on CLEAR_TEMP
                    // at L_else, just outside [L_top, L_else).
                    continue;
                }
                if (isJump) {
                    report(context.code, source,
                            "Plain control-flow edge crosses cleanup boundary "
                                    + cleanup.getId());
                    return false;
                }
                if (!sourceInside && targetInside) {
                    // Lexical fallthrough at the protected-range start is legal.
                    if (target != cleanup.getStartOffset()) {
                        report(context.code, source,
                                "Fallthrough enters cleanup lifetime "
                                        + cleanup.getId() + " away from its start");
                        return false;
                    }
                    continue;
                }

                if (cleanup.getKind() == CleanupRegion.Kind.FINALLY
                        || cleanup.getKind() == CleanupRegion.Kind.WITH) {
                    report(context.code, source,
                            "Fallthrough leaves " + cleanup.getKind()
                                    + " cleanup " + cleanup.getId()
                                    + " without ENTER_CLEANUP/WITH_EXIT");
                    return false;
                }
                if (state.activeCleanups.contains(cleanup.getId())) {
                    report(context.code, source,
                            "Fallthrough leaves active cleanup " + cleanup.getId());
                    return false;
                }
                for (Integer slot : cleanup.getResourceSlots()) {
                    if (state.initializedTemps.get(slot)) {
                        report(context.code, source,
                                "Fallthrough leaves temp lifetime "
                                        + cleanup.getId() + " with slot " + slot
                                        + " still initialized");
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean isLeadingClearFor(
                CodeObject code, int offset, CleanupRegion cleanup) {
            if (!validInstructionOffset(offset, code.getInstructions().size())) {
                return false;
            }
            Instruction instruction = code.getInstructions().get(offset);
            return instruction.getOpCode() == OpCode.CLEAR_TEMP
                    && cleanup.getResourceSlots().contains(
                            ((Operand.TempSlotOperand) instruction.getOperand())
                                    .getIndex());
        }

        private AbstractState applyTransition(
                FlowContext context,
                int offset,
                AbstractState input,
                StackTransition.Edge edge) {
            Instruction instruction = context.code.getInstructions().get(offset);
            StackTransition transition;
            try {
                transition = StackTransition.transition(instruction, edge);
            } catch (IllegalArgumentException failure) {
                report(context.code, offset, failure.getMessage());
                return null;
            }

            List<AbstractValue> stack = new ArrayList<>(input.stack);
            BitSet temps = (BitSet) input.initializedTemps.clone();
            Set<Integer> active = new LinkedHashSet<>(input.activeCleanups);
            PendingState pending = input.pending;

            if (transition.getSpecial() == StackTransition.Special.COPY) {
                int depth = ((Operand.CountOperand) instruction.getOperand()).getCount();
                if (depth < 1 || depth > stack.size()) {
                    report(context.code, offset,
                            "COPY depth " + depth + " exceeds stack depth "
                                    + stack.size());
                    return null;
                }
                stack.add(stack.get(stack.size() - depth));
                return input.with(stack, temps, active, pending);
            }
            if (transition.getSpecial() == StackTransition.Special.SWAP) {
                int depth = ((Operand.CountOperand) instruction.getOperand()).getCount();
                if (depth < 1 || depth > stack.size()) {
                    report(context.code, offset,
                            "SWAP depth " + depth + " exceeds stack depth "
                                    + stack.size());
                    return null;
                }
                int top = stack.size() - 1;
                int other = stack.size() - depth;
                AbstractValue value = stack.get(top);
                stack.set(top, stack.get(other));
                stack.set(other, value);
                return input.with(stack, temps, active, pending);
            }

            List<AbstractValue> popped = new ArrayList<>();
            for (StackTransition.ValueKind expected
                    : transition.getPoppedFromTop()) {
                if (stack.isEmpty()) {
                    report(context.code, offset,
                            instruction.getOpCode() + " underflows the operand stack");
                    return null;
                }
                AbstractValue value = stack.remove(stack.size() - 1);
                if (!matches(expected, value)) {
                    report(context.code, offset,
                            instruction.getOpCode() + " requires " + expected
                                    + " but TOS is " + value);
                    return null;
                }
                popped.add(value);
            }

            if (instruction.getOpCode() == OpCode.MAKE_FUNCTION
                    && !validateMakeFunction(context.code, offset,
                            (Operand.MakeFunctionSpec) instruction.getOperand(),
                            popped)) {
                return null;
            }
            if (instruction.getOpCode() == OpCode.BUILD_CLASS
                    && !validateBuildClass(context.code, offset,
                            (Operand.BuildClassSpec) instruction.getOperand(),
                            popped)) {
                return null;
            }

            for (StackTransition.Producer producer : transition.getPushed()) {
                switch (producer) {
                    case PY_VALUE:
                        stack.add(AbstractValue.py());
                        break;
                    case CONSTANT:
                        stack.add(constantValue(context.code, offset, instruction));
                        break;
                    case CELL_REF:
                        stack.add(closureValue(context.code, offset, instruction));
                        break;
                    case COPY_AT_DEPTH:
                        throw new AssertionError(
                                "COPY producer must be handled before generic transition");
                }
            }

            switch (transition.getSpecial()) {
                case LOAD_TEMP: {
                    int slot = tempSlot(instruction);
                    if (!temps.get(slot)) {
                        report(context.code, offset,
                                "LOAD_TEMP reads slot " + slot
                                        + " before definite initialization");
                        return null;
                    }
                    break;
                }
                case STORE_TEMP: {
                    int slot = tempSlot(instruction);
                    temps.set(slot);
                    for (CleanupRegion owner : matchingTempOwners(
                            OpCode.STORE_TEMP, offset,
                            context.regions.tempOwners.getOrDefault(
                                    slot, Collections.<CleanupRegion>emptyList()))) {
                        if (owner.getKind() == CleanupRegion.Kind.TEMP_CLEAR) {
                            active.add(owner.getId());
                        }
                    }
                    break;
                }
                case CLEAR_TEMP: {
                    int slot = tempSlot(instruction);
                    temps.clear(slot);
                    for (CleanupRegion owner
                            : context.regions.tempOwners.getOrDefault(
                                    slot, Collections.<CleanupRegion>emptyList())) {
                        boolean anyInitialized = false;
                        for (Integer owned : owner.getResourceSlots()) {
                            anyInitialized |= temps.get(owned);
                        }
                        if (!anyInitialized) {
                            active.remove(owner.getId());
                        }
                    }
                    break;
                }
                case WITH_ENTER: {
                    Operand.WithSpec spec = (Operand.WithSpec) instruction.getOperand();
                    if (temps.get(spec.getResourceSlot())) {
                        report(context.code, offset,
                                "WITH_ENTER overwrites initialized resource slot "
                                        + spec.getResourceSlot());
                        return null;
                    }
                    temps.set(spec.getResourceSlot());
                    active.add(spec.getCleanupId());
                    break;
                }
                case WITH_EXIT: {
                    Operand.WithSpec spec = (Operand.WithSpec) instruction.getOperand();
                    if (!temps.get(spec.getResourceSlot())
                            || !active.contains(spec.getCleanupId())) {
                        report(context.code, offset,
                                "WITH_EXIT executes without an active WITH resource");
                        return null;
                    }
                    if (pending == null
                            || pending.activeCleanupId != spec.getCleanupId()) {
                        report(context.code, offset,
                                "WITH_EXIT requires PendingTransfer for cleanup "
                                        + spec.getCleanupId());
                        return null;
                    }
                    temps.clear(spec.getResourceSlot());
                    active.remove(spec.getCleanupId());
                    break;
                }
                case BEGIN_EXCEPT: {
                    Operand.HandlerSpec spec =
                            (Operand.HandlerSpec) instruction.getOperand();
                    if (pending == null
                            || pending.kind != TransferKind.EXCEPTION
                            || !pending.dispatching) {
                        report(context.code, offset,
                                "BEGIN_EXCEPT is not reached from exception dispatch");
                        return null;
                    }
                    // Keep a non-dispatchable abstract handler marker.  Its
                    // suspended link models the dynamic ExceptionState stack:
                    // a normal END_EXCEPT resumes an older pending cleanup,
                    // while an abrupt handler exit replaces it.
                    pending = new PendingState(
                            TransferKind.HANDLED,
                            pending.target,
                            pending.origin,
                            pending.visited,
                            spec.getCleanupRegionId(),
                            false,
                            pending.suspended);
                    active.add(spec.getCleanupRegionId());
                    break;
                }
                case END_EXCEPT: {
                    int handlerId = ((Operand.CleanupSpec) instruction.getOperand())
                            .getCleanupId();
                    CleanupRegion handler =
                            context.regions.handlerByHandlerId.get(handlerId);
                    if (handler == null || !active.remove(handler.getId())) {
                        report(context.code, offset,
                                "END_EXCEPT closes inactive handler " + handlerId);
                        return null;
                    }
                    if (pending == null || pending.kind != TransferKind.HANDLED) {
                        report(context.code, offset,
                                "END_EXCEPT has no active handled-exception state");
                        return null;
                    }
                    pending = pending.suspended;
                    break;
                }
                case NONE:
                case LOAD_CONST:
                case LOAD_CLOSURE:
                case MAKE_FUNCTION:
                case BUILD_CLASS:
                    break;
                case COPY:
                case SWAP:
                case ENTER_CLEANUP:
                case END_CLEANUP:
                case RESTORE_REGION_ANCHOR:
                case ABRUPT_TRANSFER:
                    // Cases with behavior above or owned by transfer dispatch.
                    break;
            }
            return input.with(stack, temps, active, pending);
        }

        private boolean matches(
                StackTransition.ValueKind expected, AbstractValue value) {
            if (expected == StackTransition.ValueKind.ANY) {
                return true;
            }
            if (expected == StackTransition.ValueKind.PY_VALUE) {
                return value.kind == StackValueDescriptor.Kind.PY_VALUE;
            }
            if (expected == StackTransition.ValueKind.CELL_REF) {
                return value.kind == StackValueDescriptor.Kind.CELL_REF;
            }
            return value.kind == StackValueDescriptor.Kind.CODE_REF;
        }

        private AbstractValue constantValue(
                CodeObject code, int offset, Instruction instruction) {
            int index = ((Operand.ConstOperand) instruction.getOperand()).getIndex();
            Object constant = code.getConstantPool().get(index);
            if (constant instanceof CodeObject) {
                return AbstractValue.code((CodeObject) constant);
            }
            if (constant instanceof PyValue) {
                return AbstractValue.py();
            }
            report(code, offset,
                    "LOAD_CONST references unsupported constant "
                            + constant.getClass().getName());
            return AbstractValue.py();
        }

        private AbstractValue closureValue(
                CodeObject code, int offset, Instruction instruction) {
            int deref = ((Operand.DerefSlotOperand) instruction.getOperand()).getIndex();
            int cells = code.getCellVariableNames().size();
            if (deref < cells) {
                return AbstractValue.cell(
                        code.getCodeId(), deref,
                        code.getCellVariableNames().get(deref));
            }
            int free = deref - cells;
            return AbstractValue.cell(
                    code.getCodeId(), deref,
                    code.getFreeVariableNames().get(free));
        }

        private boolean validateMakeFunction(
                CodeObject owner,
                int offset,
                Operand.MakeFunctionSpec spec,
                List<AbstractValue> popped) {
            AbstractValue codeRef = popped.get(0);
            CodeObject nested = codeRef.code;
            if (nested.getKind() != CodeKind.FUNCTION) {
                report(owner, offset,
                        "MAKE_FUNCTION requires FUNCTION CodeRef, got "
                                + nested.getKind());
                return false;
            }
            if (!nested.getFreeVariableNames().equals(
                    spec.getOrderedFreeVarNames())) {
                report(owner, offset,
                        "MAKE_FUNCTION free-variable metadata does not match "
                                + nested.getQualifiedName());
                return false;
            }
            if (!nested.getSignature().getDefaultedParameterNames().equals(
                    spec.getOrderedDefaultParameterNames())) {
                report(owner, offset,
                        "MAKE_FUNCTION default metadata does not match function signature");
                return false;
            }
            List<String> actualAnnotations =
                    spec.getOrderedAnnotationNames();
            boolean hasReturn = actualAnnotations.contains("return");
            if (hasReturn && !"return".equals(actualAnnotations.get(
                    actualAnnotations.size() - 1))) {
                report(owner, offset,
                        "MAKE_FUNCTION return annotation must be last");
                return false;
            }
            List<String> expectedAnnotations = new ArrayList<>();
            for (String parameter
                    : nested.getSignature().getParameterNames()) {
                if (actualAnnotations.contains(parameter)) {
                    expectedAnnotations.add(parameter);
                }
            }
            if (hasReturn) {
                expectedAnnotations.add("return");
            }
            if (!actualAnnotations.equals(expectedAnnotations)) {
                report(owner, offset,
                        "MAKE_FUNCTION annotation metadata is not in signature order");
                return false;
            }
            int freeCount = spec.getOrderedFreeVarNames().size();
            for (int popIndex = 0; popIndex < freeCount; popIndex++) {
                AbstractValue cell = popped.get(1 + popIndex);
                String expectedName = spec.getOrderedFreeVarNames().get(
                        freeCount - 1 - popIndex);
                int expectedSlot = derefSlot(owner, expectedName);
                if (cell.ownerCodeId != owner.getCodeId()
                        || cell.slot != expectedSlot
                        || !cell.name.equals(expectedName)) {
                    report(owner, offset,
                            "MAKE_FUNCTION cell provenance mismatch: expected '"
                                    + expectedName + "' from code "
                                    + owner.getCodeId() + " slot " + expectedSlot
                                    + " but got '" + cell.name + "' from code "
                                    + cell.ownerCodeId + " slot " + cell.slot);
                    return false;
                }
            }
            return true;
        }

        private boolean validateBuildClass(
                CodeObject owner,
                int offset,
                Operand.BuildClassSpec spec,
                List<AbstractValue> popped) {
            AbstractValue codeRef = popped.get(0);
            CodeObject nested = codeRef.code;
            if (nested.getKind() != CodeKind.CLASS_BODY) {
                report(owner, offset,
                        "BUILD_CLASS requires CLASS_BODY CodeRef, got "
                                + nested.getKind());
                return false;
            }
            if (!nested.getName().equals(spec.getClassName())) {
                report(owner, offset,
                        "BUILD_CLASS name does not match CLASS_BODY CodeRef");
                return false;
            }
            if (!nested.getFreeVariableNames().equals(
                    spec.getOrderedFreeVarNames())) {
                report(owner, offset,
                        "BUILD_CLASS free-variable metadata does not match "
                                + nested.getQualifiedName());
                return false;
            }
            int freeCount = spec.getOrderedFreeVarNames().size();
            for (int popIndex = 0; popIndex < freeCount; popIndex++) {
                AbstractValue cell = popped.get(1 + popIndex);
                String expectedName = spec.getOrderedFreeVarNames().get(
                        freeCount - 1 - popIndex);
                int expectedSlot = derefSlot(owner, expectedName);
                if (cell.ownerCodeId != owner.getCodeId()
                        || cell.slot != expectedSlot
                        || !cell.name.equals(expectedName)) {
                    report(owner, offset,
                            "BUILD_CLASS cell provenance mismatch: expected '"
                                    + expectedName + "' from code "
                                    + owner.getCodeId() + " slot " + expectedSlot
                                    + " but got '" + cell.name + "' from code "
                                    + cell.ownerCodeId + " slot " + cell.slot);
                    return false;
                }
            }
            return true;
        }

        private int derefSlot(CodeObject owner, String name) {
            int cell = owner.getCellVariableNames().indexOf(name);
            if (cell >= 0) {
                return cell;
            }
            int free = owner.getFreeVariableNames().indexOf(name);
            if (free >= 0) {
                return owner.getCellVariableNames().size() + free;
            }
            return -1;
        }

        private int tempSlot(Instruction instruction) {
            return ((Operand.TempSlotOperand) instruction.getOperand()).getIndex();
        }

        private void beginTransfer(
                FlowContext context,
                int origin,
                AbstractState state,
                TransferKind kind,
                int target,
                boolean implicitException) {
            List<Integer> inherited = state.pending == null
                    ? Collections.<Integer>emptyList()
                    : state.pending.visited;
            PendingState pending = new PendingState(
                    kind, target, origin, inherited, -1, false,
                    suspendedForReplacement(context, state));
            AbstractState transferState = state.withPending(pending);
            dispatchPending(context, origin, transferState, implicitException);
        }

        private void beginRaiseTransfer(
                FlowContext context,
                int offset,
                AbstractState input,
                AbstractState outgoing,
                int raiseCount) {
            if (raiseCount == 0 && input.pending != null
                    && input.pending.kind == TransferKind.EXCEPTION
                    && input.pending.dispatching) {
                PendingState old = input.pending;
                PendingState resumed = new PendingState(
                        TransferKind.EXCEPTION, -1, old.origin,
                        old.visited, -1, false, old.suspended);
                dispatchPending(
                        context, offset, outgoing.withPending(resumed), false);
                return;
            }
            beginTransfer(context, offset, outgoing,
                    TransferKind.EXCEPTION, -1, false);
        }

        /**
         * Carries the state that must reappear if a locally raised exception
         * is caught.  Active cleanup transfers and enclosing handler markers
         * are resumable; an unmatched dispatch candidate itself is replaced,
         * but may carry an older cleanup suspension of its own.
         */
        private PendingState suspendedForReplacement(
                FlowContext context, AbstractState state) {
            PendingState current = state.pending;
            if (current == null) return null;
            if (current.kind == TransferKind.HANDLED
                    || current.activeCleanupId >= 0) {
                if (current.activeCleanupId >= 0) {
                    CleanupRegion cleanup = context.regions.cleanupById.get(
                            current.activeCleanupId);
                    if (cleanup != null
                            && cleanup.getKind() == CleanupRegion.Kind.WITH
                            && !state.activeCleanups.contains(cleanup.getId())) {
                        // The WITH_EXIT exceptional edge deactivates its
                        // manager first.  A failing __exit__ replaces this
                        // cleanup transfer instead of making it resumable.
                        return current.suspended;
                    }
                }
                return current;
            }
            return current.dispatching ? current.suspended : null;
        }

        private void enterCleanup(
                FlowContext context,
                int offset,
                AbstractState state,
                Instruction instruction) {
            int cleanupId = ((Operand.CleanupSpec) instruction.getOperand())
                    .getCleanupId();
            CleanupRegion cleanup = context.regions.cleanupById.get(cleanupId);
            if (cleanup == null) {
                return;
            }
            List<AbstractValue> anchorStack = anchorStack(
                    context, cleanup.getStartOffset(), offset);
            if (anchorStack == null) {
                return;
            }
            if (!state.stack.equals(anchorStack)) {
                report(context.code, offset,
                        "ENTER_CLEANUP stack " + state.stack
                                + " does not match region anchor " + anchorStack);
                return;
            }
            PendingState pending = new PendingState(
                    TransferKind.NORMAL,
                    cleanup.getNormalContinuationOffset(),
                    offset,
                    appendBoundary(
                            state.pending == null
                                    ? Collections.<Integer>emptyList()
                                    : state.pending.visited,
                            cleanupId),
                    cleanupId,
                    false,
                    state.pending);
            AbstractState handlerState = state.withPending(pending)
                    .withStack(anchorStack);
            enqueueSpecial(context, offset,
                    cleanup.getHandlerOffset(), handlerState);
        }

        private void endCleanup(
                FlowContext context,
                int offset,
                AbstractState state,
                Instruction instruction) {
            int cleanupId = ((Operand.CleanupSpec) instruction.getOperand())
                    .getCleanupId();
            CleanupRegion cleanup = context.regions.cleanupById.get(cleanupId);
            if (cleanup == null) {
                return;
            }
            if (state.pending == null
                    || state.pending.activeCleanupId != cleanupId
                    || !state.pending.visited.contains(cleanupId)) {
                report(context.code, offset,
                        "END_CLEANUP " + cleanupId
                                + " has no matching active PendingTransfer");
                return;
            }
            if (cleanup.getKind() == CleanupRegion.Kind.WITH
                    && (state.activeCleanups.contains(cleanupId)
                    || state.initializedTemps.get(cleanup.getResourceSlot()))) {
                report(context.code, offset,
                        "END_CLEANUP reaches active WITH " + cleanupId
                                + " before WITH_EXIT");
                return;
            }
            Set<Integer> active = new LinkedHashSet<>(state.activeCleanups);
            active.remove(cleanupId);
            PendingState resumed = state.pending.clearActiveCleanup();
            AbstractState resumedState = state.with(
                    state.stack, state.initializedTemps, active, resumed);
            dispatchPending(context, offset, resumedState, false);
        }

        private void dispatchPending(
                FlowContext context,
                int source,
                AbstractState state,
                boolean implicitException) {
            PendingState pending = state.pending;
            if (pending == null) {
                report(context.code, source,
                        "Internal verifier state lost PendingTransfer");
                return;
            }
            Boundary boundary = selectBoundary(context, state, pending);
            if (boundary == null) {
                if (pending.kind == TransferKind.JUMP
                        || pending.kind == TransferKind.NORMAL) {
                    AbstractState completed = state.withPending(
                            pending.kind == TransferKind.NORMAL
                                    ? pending.suspended : null);
                    enqueue(context, pending.target, completed, false, source);
                } else if (pending.kind == TransferKind.HANDLED) {
                    report(context.code, source,
                            "Handled-exception state entered transfer dispatch");
                }
                // RETURN and EXCEPTION leave the frame when no boundary remains.
                return;
            }

            PendingState visited = pending.visit(boundary.id, -1, false);
            if (boundary.type == BoundaryType.EXCEPTION) {
                List<AbstractValue> anchor = anchorStack(
                        context, boundary.start, source);
                if (anchor == null) {
                    return;
                }
                List<Integer> canonicalVisited = new ArrayList<>();
                for (Integer visitedId : visited.visited) {
                    Boundary visitedBoundary = context.regions.byId.get(visitedId);
                    if (visitedId == boundary.id
                            || (visitedBoundary != null
                            && visitedBoundary.containsOffset(boundary.start))) {
                        canonicalVisited.add(visitedId);
                    }
                }
                PendingState dispatch = new PendingState(
                        TransferKind.EXCEPTION,
                        -1,
                        boundary.start,
                        canonicalVisited,
                        -1,
                        true,
                        suspendedTransferForHandler(
                                pending.suspended,
                                boundary.exceptionRegion.getHandlerOffset(),
                                context.regions));
                AbstractState dispatchState = state.withPending(dispatch)
                        .withStack(anchor);
                enqueueSpecial(context, source,
                        boundary.exceptionRegion.getHandlerOffset(),
                        dispatchState);
                return;
            }

            CleanupRegion cleanup = boundary.cleanupRegion;
            if (cleanup.getKind() == CleanupRegion.Kind.TEMP_CLEAR) {
                BitSet temps = (BitSet) state.initializedTemps.clone();
                Set<Integer> active = new LinkedHashSet<>(state.activeCleanups);
                for (Integer slot : cleanup.getResourceSlots()) {
                    temps.clear(slot);
                }
                active.remove(cleanup.getId());
                AbstractState cleared = state.with(
                        state.stack, temps, active, visited);
                dispatchPending(context, source, cleared, implicitException);
                return;
            }
            if (cleanup.getKind() == CleanupRegion.Kind.EXCEPT_HANDLER) {
                Set<Integer> active = new LinkedHashSet<>(state.activeCleanups);
                active.remove(cleanup.getId());
                AbstractState cleared = state.with(
                        state.stack, state.initializedTemps, active, visited);
                dispatchPending(context, source, cleared, implicitException);
                return;
            }

            List<AbstractValue> anchor = anchorStack(
                    context, boundary.start, source);
            if (anchor == null) {
                return;
            }
            PendingState suspended = visited.visit(
                    boundary.id, cleanup.getId(), false);
            AbstractState cleanupState = state.withPending(suspended)
                    .withStack(anchor);
            enqueueSpecial(context, source,
                    cleanup.getHandlerOffset(), cleanupState);
        }

        private Boundary selectBoundary(
                FlowContext context,
                AbstractState state,
                PendingState pending) {
            for (Boundary boundary : context.regions.boundaries) {
                if (pending.visited.contains(boundary.id)
                        || !boundary.containsOffset(pending.origin)) {
                    continue;
                }
                if ((pending.kind == TransferKind.NORMAL
                        || pending.kind == TransferKind.JUMP)
                        && boundary.containsOffset(pending.target)) {
                    continue;
                }
                if (boundary.type == BoundaryType.EXCEPTION) {
                    if (pending.kind == TransferKind.EXCEPTION) {
                        return boundary;
                    }
                    continue;
                }
                CleanupRegion cleanup = boundary.cleanupRegion;
                if (cleanup.getKind() == CleanupRegion.Kind.FINALLY) {
                    return boundary;
                }
                if (cleanup.getKind() == CleanupRegion.Kind.WITH
                        && state.activeCleanups.contains(cleanup.getId())) {
                    return boundary;
                }
                if (cleanup.getKind() == CleanupRegion.Kind.EXCEPT_HANDLER
                        && state.activeCleanups.contains(cleanup.getId())) {
                    return boundary;
                }
                if (cleanup.getKind() == CleanupRegion.Kind.TEMP_CLEAR
                        && isTempCleanupActive(state, cleanup)) {
                    return boundary;
                }
            }
            return null;
        }

        private boolean isTempCleanupActive(
                AbstractState state, CleanupRegion cleanup) {
            if (state.activeCleanups.contains(cleanup.getId())) {
                return true;
            }
            for (Integer slot : cleanup.getResourceSlots()) {
                if (state.initializedTemps.get(slot)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Keeps only a continuation whose cleanup/handler still dynamically
         * encloses the selected exception handler.  Suspensions belonging to
         * cleanups already crossed by dispatch must never be restored by
         * END_EXCEPT.
         */
        private PendingState suspendedTransferForHandler(
                PendingState suspended,
                int handlerOffset,
                RegionModel regions) {
            PendingState cursor = suspended;
            while (cursor != null) {
                if (cursor.activeCleanupId >= 0) {
                    CleanupRegion cleanup = regions.cleanupById.get(
                            cursor.activeCleanupId);
                    if (cleanup != null) {
                        boolean handlerIsInside;
                        if (cleanup.getKind()
                                == CleanupRegion.Kind.EXCEPT_HANDLER) {
                            handlerIsInside = cleanup.contains(handlerOffset);
                        } else {
                            handlerIsInside = cleanup.getHandlerOffset() >= 0
                                    && cleanup.getNormalContinuationOffset()
                                            > cleanup.getHandlerOffset()
                                    && handlerOffset >= cleanup.getHandlerOffset()
                                    && handlerOffset
                                            < cleanup.getNormalContinuationOffset();
                        }
                        if (handlerIsInside) return cursor;
                    }
                }
                cursor = cursor.suspended;
            }
            return null;
        }

        private List<AbstractValue> anchorStack(
                FlowContext context, int regionStart, int source) {
            List<StateSlot> slots = context.flow.states.get(regionStart);
            if (slots.isEmpty()) {
                report(context.code, source,
                        "Region anchor at offset " + regionStart
                                + " is not reachable");
                return null;
            }
            List<AbstractValue> anchor = slots.get(0).state.stack;
            for (StateSlot slot : slots) {
                if (!anchor.equals(slot.state.stack)) {
                    report(context.code, source,
                            "Region anchor has incompatible stack provenances");
                    return null;
                }
            }
            return anchor;
        }

        private List<Integer> appendBoundary(
                List<Integer> source, int boundaryId) {
            List<Integer> result = new ArrayList<>(source);
            if (!result.contains(boundaryId)) result.add(boundaryId);
            return result;
        }

        private CodeVerification finalizeVerification(
                CodeObject code,
                RegionModel regions,
                FlowAnalysis flow) {
            TreeSet<Integer> anchorOffsets = new TreeSet<>();
            anchorOffsets.add(0);
            for (Instruction instruction : code.getInstructions()) {
                if (instruction.getOperand() instanceof Operand.JumpOperand) {
                    anchorOffsets.add(((Operand.JumpOperand) instruction.getOperand())
                            .getTargetOffset());
                }
            }
            for (Boundary boundary : regions.boundaries) {
                anchorOffsets.add(boundary.start);
                if (boundary.handlerOffset() >= 0) {
                    anchorOffsets.add(boundary.handlerOffset());
                }
                if (boundary.type == BoundaryType.CLEANUP
                        && boundary.cleanupRegion.getNormalContinuationOffset() >= 0) {
                    anchorOffsets.add(
                            boundary.cleanupRegion.getNormalContinuationOffset());
                }
            }
            for (Map.Entry<Integer, Integer> incoming
                    : flow.incomingCounts.entrySet()) {
                if (incoming.getValue() > 1) {
                    anchorOffsets.add(incoming.getKey());
                }
            }

            List<StackAnchor> anchors = new ArrayList<>();
            Map<Integer, StackAnchor> anchorByOffset = new LinkedHashMap<>();
            int nextAnchorId = 0;
            for (Integer offset : anchorOffsets) {
                if (!validInstructionOffset(offset, code.getInstructions().size())
                        || flow.states.get(offset).isEmpty()) {
                    continue;
                }
                List<AbstractValue> stack = flow.states.get(offset).get(0).state.stack;
                boolean compatible = true;
                for (StateSlot slot : flow.states.get(offset)) {
                    if (!stack.equals(slot.state.stack)) {
                        compatible = false;
                        report(code, offset,
                                "Anchor has incompatible stack provenance states");
                        break;
                    }
                }
                if (!compatible) {
                    continue;
                }
                List<StackValueDescriptor> descriptors = descriptors(stack);
                StackAnchor anchor = new StackAnchor(
                        nextAnchorId++, offset, descriptors);
                anchors.add(anchor);
                anchorByOffset.put(offset, anchor);
            }

            // A statically non-raising try still owns valid (but unreachable)
            // handler bytecode.  Give its handler-owned regions the same
            // verifier-proven stack shape as the dispatching try anchor rather
            // than inventing a fake exceptional predecessor.
            List<Boundary> unreachable = new ArrayList<>(regions.boundaries);
            unreachable.sort(Comparator
                    .comparingInt((Boundary value) -> value.depth)
                    .thenComparingInt(value -> value.start));
            for (Boundary boundary : unreachable) {
                if (anchorByOffset.containsKey(boundary.start)
                        || !mayBeStaticallyUnreachable(boundary, regions)) {
                    continue;
                }
                StackAnchor reference = unreachableAnchorReference(
                        code, boundary, regions, anchorByOffset);
                if (reference == null) {
                    report(code, boundary.start,
                            "Cannot derive unreachable handler stack anchor");
                    continue;
                }
                StackAnchor synthetic = new StackAnchor(
                        nextAnchorId++, boundary.start,
                        reference.getStackValues());
                anchors.add(synthetic);
                anchorByOffset.put(boundary.start, synthetic);
            }

            if (code.isVerified()) {
                if (code.getMaxStack() != flow.maxStack) {
                    report(code, -1,
                            "Stale maxStack " + code.getMaxStack()
                                    + "; recomputed value is " + flow.maxStack);
                }
                validateExistingAnchors(code, anchorByOffset);
            }

            List<ExceptionRegion> verifiedExceptions = new ArrayList<>();
            for (ExceptionRegion region : code.getExceptionRegions()) {
                Boundary boundary = regions.byId.get(region.getId());
                StackAnchor anchor = anchorByOffset.get(region.getStartOffset());
                if (anchor == null) {
                    report(code, region.getStartOffset(),
                            "Cannot resolve exception-region stack anchor");
                    continue;
                }
                validateSuppliedRegionAnchor(code, region.getAnchor(), anchor,
                        region.getId());
                verifiedExceptions.add(new ExceptionRegion(
                        region.getId(), boundary.parentId, boundary.depth,
                        region.getStartOffset(), region.getEndOffset(),
                        region.getHandlerOffset(), anchor));
            }

            List<CleanupRegion> verifiedCleanups = new ArrayList<>();
            for (CleanupRegion region : code.getCleanupRegions()) {
                Boundary boundary = regions.byId.get(region.getId());
                StackAnchor anchor = anchorByOffset.get(region.getStartOffset());
                if (anchor == null) {
                    report(code, region.getStartOffset(),
                            "Cannot resolve cleanup-region stack anchor");
                    continue;
                }
                validateSuppliedRegionAnchor(code, region.getAnchor(), anchor,
                        region.getId());
                verifiedCleanups.add(new CleanupRegion(
                        region.getId(), boundary.parentId, boundary.depth,
                        region.getKind(), region.getStartOffset(),
                        region.getEndOffset(), region.getHandlerOffset(),
                        region.getNormalContinuationOffset(),
                        region.getResourceSlots(), region.getHandlerId(),
                        region.getAlias(), anchor));
            }

            if (!diagnostics.isEmpty()) {
                // The caller compares its per-code error count; this prevents
                // accidental publication when finalization added an error.
                return null;
            }
            return new CodeVerification(
                    Collections.unmodifiableList(verifiedExceptions),
                    Collections.unmodifiableList(verifiedCleanups),
                    Collections.unmodifiableList(anchors),
                    flow.maxStack);
        }

        private StackAnchor unreachableAnchorReference(
                CodeObject code,
                Boundary boundary,
                RegionModel regions,
                Map<Integer, StackAnchor> anchors) {
            if (boundary.parentId >= 0) {
                Boundary parent = regions.byId.get(boundary.parentId);
                StackAnchor parentAnchor = parent == null
                        ? null : anchors.get(parent.start);
                if (parentAnchor != null) return parentAnchor;
            }
            if (boundary.type == BoundaryType.CLEANUP
                    && boundary.cleanupRegion.getKind()
                            == CleanupRegion.Kind.EXCEPT_HANDLER) {
                ExceptionRegion closest = null;
                for (ExceptionRegion region : code.getExceptionRegions()) {
                    if (region.getHandlerOffset() <= boundary.start
                            && (closest == null
                            || region.getHandlerOffset()
                                    > closest.getHandlerOffset())) {
                        closest = region;
                    }
                }
                if (closest != null) {
                    return anchors.get(closest.getStartOffset());
                }
            }
            return null;
        }

        private void validateExistingAnchors(
                CodeObject code, Map<Integer, StackAnchor> recomputed) {
            Map<Integer, StackAnchor> supplied = new LinkedHashMap<>();
            for (StackAnchor anchor : code.getStackAnchors()) {
                if (supplied.put(anchor.getOffset(), anchor) != null) {
                    report(code, anchor.getOffset(),
                            "Duplicate supplied anchor offset " + anchor.getOffset());
                }
            }
            if (!supplied.keySet().equals(recomputed.keySet())) {
                report(code, -1,
                        "Stale stack-anchor offset set; supplied "
                                + supplied.keySet() + " but recomputed "
                                + recomputed.keySet());
                return;
            }
            for (Map.Entry<Integer, StackAnchor> entry : recomputed.entrySet()) {
                StackAnchor old = supplied.get(entry.getKey());
                if (!old.getStackValues().equals(
                        entry.getValue().getStackValues())) {
                    report(code, entry.getKey(),
                            "Stale stack-anchor shape at offset " + entry.getKey());
                }
            }
        }

        private void validateSuppliedRegionAnchor(
                CodeObject code,
                StackAnchor supplied,
                StackAnchor recomputed,
                int regionId) {
            if (supplied == null) {
                return;
            }
            if (supplied.getOffset() != recomputed.getOffset()
                    || !supplied.getStackValues().equals(
                            recomputed.getStackValues())) {
                report(code, recomputed.getOffset(),
                        "Region " + regionId
                                + " carries a stale/raw stack anchor");
            }
        }

        private List<StackValueDescriptor> descriptors(
                List<AbstractValue> stack) {
            List<StackValueDescriptor> result = new ArrayList<>(stack.size());
            for (AbstractValue value : stack) {
                result.add(value.descriptor());
            }
            return result;
        }

        private void reportHeader(String message) {
            String source = module == null ? "<unknown>" : module.getSourceName();
            addDiagnostic(Diagnostics.invalidBytecode(message, 0, 0, source));
        }

        private void reportUnsupportedVersion(int actual) {
            addDiagnostic(Diagnostics.unsupportedBytecodeVersion(
                    actual,
                    BytecodeFormat.CURRENT_VERSION,
                    module == null ? "<unknown>" : module.getSourceName()));
        }

        private void report(CodeObject code, int offset, String message) {
            SourceSpan span = SourceSpan.UNKNOWN;
            if (code != null && offset >= 0
                    && offset < code.getSourceMap().size()) {
                InstructionLocation location = code.getSourceMap().get(offset);
                if (location != null && location.getSpan() != null) {
                    span = location.getSpan();
                }
            }
            String source = span.isKnown()
                    ? span.getSourceFile()
                    : code == null || code.getSourceFile().isEmpty()
                            ? module == null ? "<unknown>" : module.getSourceName()
                            : code.getSourceFile();
            String qualified = code == null ? "<module>" : code.getQualifiedName();
            String located = offset < 0
                    ? qualified + ": " + message
                    : qualified + " @ bytecode offset " + offset + ": " + message;
            addDiagnostic(Diagnostics.bytecodeVerificationError(
                    located,
                    span.getStartLine(),
                    span.getStartColumn(),
                    source));
        }

        private void addDiagnostic(Diagnostic diagnostic) {
            if (!diagnostics.contains(diagnostic)) {
                diagnostics.add(diagnostic);
            }
        }
    }

    private static final class CodeVerification {
        private final List<ExceptionRegion> exceptionRegions;
        private final List<CleanupRegion> cleanupRegions;
        private final List<StackAnchor> anchors;
        private final int maxStack;

        private CodeVerification(
                List<ExceptionRegion> exceptionRegions,
                List<CleanupRegion> cleanupRegions,
                List<StackAnchor> anchors,
                int maxStack) {
            this.exceptionRegions = exceptionRegions;
            this.cleanupRegions = cleanupRegions;
            this.anchors = anchors;
            this.maxStack = maxStack;
        }
    }

    private enum BoundaryType { EXCEPTION, CLEANUP }

    private static final class Boundary {
        private final BoundaryType type;
        private final ExceptionRegion exceptionRegion;
        private final CleanupRegion cleanupRegion;
        private final int id;
        private final int start;
        private final int end;
        private int parentId;
        private int depth;

        private Boundary(ExceptionRegion region) {
            this.type = BoundaryType.EXCEPTION;
            this.exceptionRegion = region;
            this.cleanupRegion = null;
            this.id = region.getId();
            this.start = region.getStartOffset();
            this.end = region.getEndOffset();
        }

        private Boundary(CleanupRegion region) {
            this.type = BoundaryType.CLEANUP;
            this.exceptionRegion = null;
            this.cleanupRegion = region;
            this.id = region.getId();
            this.start = region.getStartOffset();
            this.end = region.getEndOffset();
        }

        private boolean containsOffset(int offset) {
            return offset >= start && offset < end;
        }

        private boolean strictlyContains(Boundary child) {
            return start <= child.start && end >= child.end
                    && (start < child.start || end > child.end);
        }

        private int handlerOffset() {
            return type == BoundaryType.EXCEPTION
                    ? exceptionRegion.getHandlerOffset()
                    : cleanupRegion.getHandlerOffset();
        }
    }

    private static final class RegionModel {
        private final List<Boundary> boundaries;
        private final Map<Integer, Boundary> byId;
        private final Map<Integer, CleanupRegion> cleanupById;
        private final Map<Integer, CleanupRegion> handlerByHandlerId;
        private final Map<Integer, List<CleanupRegion>> tempOwners;
        private final Set<Integer> specialEntryOffsets;

        private RegionModel(
                List<Boundary> boundaries,
                Map<Integer, Boundary> byId,
                Map<Integer, CleanupRegion> cleanupById,
                Map<Integer, CleanupRegion> handlerByHandlerId,
                Map<Integer, List<CleanupRegion>> tempOwners,
                Set<Integer> specialEntryOffsets) {
            this.boundaries = boundaries;
            this.byId = byId;
            this.cleanupById = cleanupById;
            this.handlerByHandlerId = handlerByHandlerId;
            this.tempOwners = tempOwners;
            this.specialEntryOffsets = specialEntryOffsets;
        }
    }

    /**
     * HANDLED models a claimed exception while an except suite executes.  It
     * is not dispatchable itself; its suspended link lets END_EXCEPT restore
     * an outer cleanup transfer or enclosing handler state on a normal exit.
     */
    private enum TransferKind { NORMAL, JUMP, RETURN, EXCEPTION, HANDLED }

    private static final class PendingState {
        private final TransferKind kind;
        private final int target;
        private final int origin;
        private final List<Integer> visited;
        private final int activeCleanupId;
        private final boolean dispatching;
        private final PendingState suspended;

        private PendingState(
                TransferKind kind,
                int target,
                int origin,
                List<Integer> visited,
                int activeCleanupId,
                boolean dispatching) {
            this(kind, target, origin, visited,
                    activeCleanupId, dispatching, null);
        }

        private PendingState(
                TransferKind kind,
                int target,
                int origin,
                List<Integer> visited,
                int activeCleanupId,
                boolean dispatching,
                PendingState suspended) {
            this.kind = kind;
            this.target = target;
            this.origin = origin;
            this.visited = Collections.unmodifiableList(new ArrayList<>(visited));
            this.activeCleanupId = activeCleanupId;
            this.dispatching = dispatching;
            this.suspended = suspended;
        }

        private PendingState visit(int boundaryId, int activeCleanup, boolean dispatch) {
            List<Integer> copy = new ArrayList<>(visited);
            if (!copy.contains(boundaryId)) {
                copy.add(boundaryId);
            }
            return new PendingState(
                    kind, target, origin, copy, activeCleanup, dispatch,
                    suspended);
        }

        private PendingState clearActiveCleanup() {
            return new PendingState(
                    kind, target, origin, visited, -1, dispatching,
                    suspended);
        }

        private PendingState withOrigin(int canonicalOrigin) {
            return new PendingState(
                    kind, target, canonicalOrigin, visited,
                    activeCleanupId, dispatching, suspended);
        }

        /**
         * Compares the executable transfer state while deliberately leaving
         * its suspended continuation disjunctive.  Two exception-dispatch or
         * handled-exception paths may execute the same handler bytecode yet
         * need END_EXCEPT to resume different enclosing cleanup transfers.
         */
        private boolean sameResumableHead(PendingState that) {
            if (that == null
                    || (kind != TransferKind.EXCEPTION
                            && kind != TransferKind.HANDLED)
                    || kind != that.kind) {
                return false;
            }
            return target == that.target
                    && origin == that.origin
                    && activeCleanupId == that.activeCleanupId
                    && dispatching == that.dispatching;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PendingState)) {
                return false;
            }
            PendingState that = (PendingState) other;
            return kind == that.kind && target == that.target
                    && origin == that.origin
                    && activeCleanupId == that.activeCleanupId
                    && dispatching == that.dispatching
                    && visited.equals(that.visited)
                    && Objects.equals(suspended, that.suspended);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    kind, target, origin, visited, activeCleanupId,
                    dispatching, suspended);
        }

        @Override
        public String toString() {
            return kind + "(target=" + target + ", origin=" + origin
                    + ", visited=" + visited + ", cleanup="
                    + activeCleanupId + ", dispatch=" + dispatching + ")";
        }
    }

    private static final class AbstractValue {
        private final StackValueDescriptor.Kind kind;
        private final CodeObject code;
        private final int ownerCodeId;
        private final int slot;
        private final String name;

        private AbstractValue(
                StackValueDescriptor.Kind kind,
                CodeObject code,
                int ownerCodeId,
                int slot,
                String name) {
            this.kind = kind;
            this.code = code;
            this.ownerCodeId = ownerCodeId;
            this.slot = slot;
            this.name = name == null ? "" : name;
        }

        private static AbstractValue py() {
            return new AbstractValue(
                    StackValueDescriptor.Kind.PY_VALUE, null, -1, -1, "");
        }

        private static AbstractValue code(CodeObject code) {
            return new AbstractValue(
                    StackValueDescriptor.Kind.CODE_REF,
                    Objects.requireNonNull(code, "code"), -1, -1, "");
        }

        private static AbstractValue cell(int ownerCodeId, int slot, String name) {
            return new AbstractValue(
                    StackValueDescriptor.Kind.CELL_REF,
                    null, ownerCodeId, slot, Objects.requireNonNull(name, "name"));
        }

        private StackValueDescriptor descriptor() {
            if (kind == StackValueDescriptor.Kind.PY_VALUE) {
                return StackValueDescriptor.PY_VALUE;
            }
            if (kind == StackValueDescriptor.Kind.CODE_REF) {
                return new StackValueDescriptor(
                        kind,
                        code.getCodeId() + ":" + code.getKind() + ":"
                                + code.getQualifiedName());
            }
            return new StackValueDescriptor(
                    kind, ownerCodeId + ":" + slot + ":" + name);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof AbstractValue)) {
                return false;
            }
            AbstractValue that = (AbstractValue) other;
            return kind == that.kind
                    && code == that.code
                    && ownerCodeId == that.ownerCodeId
                    && slot == that.slot
                    && name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, System.identityHashCode(code), ownerCodeId, slot, name);
        }

        @Override
        public String toString() {
            return descriptor().toString();
        }
    }

    private static final class AbstractState {
        private final List<AbstractValue> stack;
        private final BitSet initializedTemps;
        private final Set<Integer> activeCleanups;
        private final PendingState pending;

        private AbstractState(
                List<AbstractValue> stack,
                BitSet initializedTemps,
                Set<Integer> activeCleanups,
                PendingState pending) {
            this.stack = Collections.unmodifiableList(new ArrayList<>(stack));
            this.initializedTemps = (BitSet) initializedTemps.clone();
            this.activeCleanups = Collections.unmodifiableSet(
                    new LinkedHashSet<>(activeCleanups));
            this.pending = pending;
        }

        private static AbstractState entry() {
            return new AbstractState(
                    Collections.<AbstractValue>emptyList(),
                    new BitSet(), Collections.<Integer>emptySet(), null);
        }

        private AbstractState with(
                List<AbstractValue> values,
                BitSet temps,
                Set<Integer> active,
                PendingState transfer) {
            return new AbstractState(values, temps, active, transfer);
        }

        private AbstractState withStack(List<AbstractValue> values) {
            return with(values, initializedTemps, activeCleanups, pending);
        }

        private AbstractState withPending(PendingState transfer) {
            return with(stack, initializedTemps, activeCleanups, transfer);
        }

        private boolean sameControl(AbstractState that) {
            return stack.equals(that.stack)
                    && activeCleanups.equals(that.activeCleanups)
                    && Objects.equals(pending, that.pending);
        }

        private boolean sameResumableControlHead(AbstractState that) {
            return stack.equals(that.stack)
                    && activeCleanups.equals(that.activeCleanups)
                    && pending != null
                    && pending.sameResumableHead(that.pending);
        }

        private AbstractState intersectTemps(AbstractState that) {
            BitSet intersection = (BitSet) initializedTemps.clone();
            intersection.and(that.initializedTemps);
            return with(stack, intersection, activeCleanups, pending);
        }

        private boolean sameTemps(AbstractState that) {
            return initializedTemps.equals(that.initializedTemps);
        }

        private String controlSummary() {
            return "active=" + activeCleanups + ", pending=" + pending;
        }
    }

    private static final class StateSlot {
        private AbstractState state;

        private StateSlot(AbstractState state) {
            this.state = state;
        }
    }

    private static final class WorkItem {
        private final int offset;
        private final AbstractState state;

        private WorkItem(int offset, AbstractState state) {
            this.offset = offset;
            this.state = state;
        }
    }

    private static final class FlowAnalysis {
        private final List<List<StateSlot>> states;
        private final Set<Integer> specialIncoming;
        private final Set<Integer> ordinaryIncoming;
        private final Map<Integer, Integer> incomingCounts;
        private int maxStack;

        private FlowAnalysis(int instructionCount) {
            this.states = new ArrayList<>(instructionCount);
            for (int index = 0; index < instructionCount; index++) {
                states.add(new ArrayList<StateSlot>());
            }
            this.specialIncoming = new HashSet<>();
            this.ordinaryIncoming = new HashSet<>();
            this.incomingCounts = new HashMap<>();
        }
    }

    private static final class FlowContext {
        private final Session session;
        private final CodeObject code;
        private final RegionModel regions;
        private final FlowAnalysis flow;
        private final Queue<WorkItem> queue = new ArrayDeque<>();

        private FlowContext(
                Session session,
                CodeObject code,
                RegionModel regions,
                FlowAnalysis flow) {
            this.session = session;
            this.code = code;
            this.regions = regions;
            this.flow = flow;
        }
    }

    private static boolean expectedMayRaise(OpCode opCode) {
        switch (opCode) {
            case NOP:
            case POP_TOP:
            case COPY:
            case SWAP:
            case LOAD_CONST:
            case LOAD_CLOSURE:
            case STORE_NAME:
            case STORE_FAST:
            case STORE_GLOBAL:
            case STORE_DEREF:
            case LOAD_TEMP:
            case STORE_TEMP:
            case CLEAR_TEMP:
            case JUMP:
            case UNWIND_JUMP:
            case RETURN_VALUE:
            case RAISE:
            case BEGIN_EXCEPT:
            case END_EXCEPT:
            case ENTER_CLEANUP:
            case END_CLEANUP:
                return false;

            case LOAD_NAME:
            case DELETE_NAME:
            case LOAD_FAST:
            case DELETE_FAST:
            case LOAD_GLOBAL:
            case DELETE_GLOBAL:
            case LOAD_DEREF:
            case DELETE_DEREF:
            case LOAD_ATTR:
            case STORE_ATTR:
            case DELETE_ATTR:
            case BINARY_SUBSCR:
            case STORE_SUBSCR:
            case DELETE_SUBSCR:
            case BUILD_LIST:
            case BUILD_TUPLE:
            case BUILD_SET:
            case BUILD_MAP:
            case UNPACK_SEQUENCE:
            case FORMAT_VALUE:
            case BUILD_STRING:
            case GET_ITER:
            case FOR_ITER:
            case UNARY_OP:
            case BINARY_OP:
            case COMPARE_OP:
            case CALL:
            case MAKE_FUNCTION:
            case BUILD_CLASS:
            case IMPORT_NAME:
            case IMPORT_FROM:
            case IMPORT_STAR:
            case POP_JUMP_IF_FALSE:
            case POP_JUMP_IF_TRUE:
            case JUMP_IF_FALSE_OR_POP:
            case JUMP_IF_TRUE_OR_POP:
            case LOAD_CURRENT_EXCEPTION:
            case EXCEPTION_MATCH:
            case WITH_ENTER:
            case WITH_EXIT:
                return true;
        }
        throw new AssertionError("Unhandled opcode " + opCode);
    }

    private static boolean hasNormalFallthrough(OpCode opCode) {
        return !opCode.isTerminator();
    }

    private static int jumpTarget(Instruction instruction) {
        return ((Operand.JumpOperand) instruction.getOperand()).getTargetOffset();
    }

    private static String fingerprint(BytecodeModule module) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, module.getMagic());
            update(digest, module.getFormatVersion());
            update(digest, module.getSourceName());
            update(digest, module.getModuleName());
            for (String key : new TreeSet<>(module.getMetadata().keySet())) {
                update(digest, key);
                update(digest, module.getMetadata().get(key));
            }
            fingerprintCode(
                    digest, module.getRootCode(),
                    new IdentityHashMap<CodeObject, Boolean>());
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("JVM has no SHA-256 implementation", impossible);
        }
    }

    private static void fingerprintCode(
            MessageDigest digest,
            CodeObject code,
            IdentityHashMap<CodeObject, Boolean> visited) {
        if (visited.put(code, Boolean.TRUE) != null) {
            update(digest, "CODE_REF");
            update(digest, code.getCodeId());
            return;
        }
        update(digest, "CODE");
        update(digest, code.getFormatVersion());
        update(digest, code.getCodeId());
        update(digest, code.getKind().name());
        update(digest, code.getName());
        update(digest, code.getQualifiedName());
        update(digest, code.getSourceFile());
        updateStrings(digest, code.getSignature().getParameterNames());
        updateStrings(digest, code.getSignature().getDefaultedParameterNames());
        updateStrings(digest, code.getFastLocalNames());
        updateStrings(digest, code.getCellVariableNames());
        updateStrings(digest, code.getFreeVariableNames());
        update(digest, code.getTemporarySlotCount());
        update(digest, code.getMaxStack());

        update(digest, code.getInstructions().size());
        for (Instruction instruction : code.getInstructions()) {
            update(digest, instruction.getOpCode().name());
            fingerprintOperand(digest, instruction.getOperand());
        }

        update(digest, code.getConstantPool().size());
        for (Object constant : code.getConstantPool().getEntries()) {
            if (constant instanceof CodeObject) {
                fingerprintCode(digest, (CodeObject) constant, visited);
            } else {
                PyValue value = (PyValue) constant;
                update(digest, value.getClass().getName());
                update(digest, value.getTypeName());
                update(digest, value.repr());
            }
        }
        updateStrings(digest, code.getNamePool().getNames());

        update(digest, code.getExceptionRegions().size());
        for (ExceptionRegion region : code.getExceptionRegions()) {
            update(digest, region.getId());
            update(digest, region.getParentBoundaryId());
            update(digest, region.getNestingDepth());
            update(digest, region.getStartOffset());
            update(digest, region.getEndOffset());
            update(digest, region.getHandlerOffset());
            fingerprintAnchor(digest, region.getAnchor());
        }
        update(digest, code.getCleanupRegions().size());
        for (CleanupRegion region : code.getCleanupRegions()) {
            update(digest, region.getId());
            update(digest, region.getParentBoundaryId());
            update(digest, region.getNestingDepth());
            update(digest, region.getKind().name());
            update(digest, region.getStartOffset());
            update(digest, region.getEndOffset());
            update(digest, region.getHandlerOffset());
            update(digest, region.getNormalContinuationOffset());
            updateIntegers(digest, region.getResourceSlots());
            update(digest, region.getHandlerId());
            ResolvedName alias = region.getAlias();
            update(digest, alias == null ? "" : alias.toString());
            fingerprintAnchor(digest, region.getAnchor());
        }

        update(digest, code.getStackAnchors().size());
        for (StackAnchor anchor : code.getStackAnchors()) {
            fingerprintAnchor(digest, anchor);
        }
        update(digest, code.getSourceMap().size());
        for (InstructionLocation location : code.getSourceMap().getLocations()) {
            SourceSpan span = location.getSpan();
            update(digest, span.getSourceFile());
            update(digest, span.getStartLine());
            update(digest, span.getStartColumn());
            update(digest, span.getEndLine());
            update(digest, span.getEndColumn());
            update(digest, location.isSynthetic() ? 1 : 0);
        }
    }

    private static void fingerprintOperand(MessageDigest digest, Operand operand) {
        update(digest, operand.getKind().name());
        switch (operand.getKind()) {
            case NONE:
                return;
            case CONST:
            case NAME:
            case LOCAL_SLOT:
            case DEREF_SLOT:
            case TEMP_SLOT:
                update(digest, ((Operand.IndexedOperand) operand).getIndex());
                return;
            case JUMP:
                update(digest, ((Operand.JumpOperand) operand).getTargetOffset());
                return;
            case COUNT:
                update(digest, ((Operand.CountOperand) operand).getCount());
                return;
            case UNARY_OPERATOR:
                update(digest,
                        ((Operand.UnaryOperatorOperand) operand)
                                .getOperator().name());
                return;
            case BINARY_OPERATOR:
                update(digest,
                        ((Operand.BinaryOperatorOperand) operand)
                                .getOperator().name());
                return;
            case COMPARE_OPERATOR:
                update(digest,
                        ((Operand.CompareOperatorOperand) operand)
                                .getOperator().name());
                return;
            case CALL_SPEC: {
                Operand.CallSpec spec = (Operand.CallSpec) operand;
                update(digest, spec.getPositionalCount());
                updateStrings(digest, spec.getOrderedKeywordNames());
                return;
            }
            case MAKE_FUNCTION_SPEC: {
                Operand.MakeFunctionSpec spec =
                        (Operand.MakeFunctionSpec) operand;
                updateStrings(digest, spec.getOrderedDefaultParameterNames());
                updateStrings(digest, spec.getOrderedAnnotationNames());
                updateStrings(digest, spec.getOrderedFreeVarNames());
                return;
            }
            case BUILD_CLASS_SPEC: {
                Operand.BuildClassSpec spec = (Operand.BuildClassSpec) operand;
                update(digest, spec.getClassName());
                update(digest, spec.getBaseCount());
                updateStrings(digest, spec.getOrderedFreeVarNames());
                return;
            }
            case IMPORT_SPEC: {
                Operand.ImportSpec spec = (Operand.ImportSpec) operand;
                update(digest, spec.getModuleName());
                update(digest, spec.getResultMode().name());
                return;
            }
            case CLEANUP_SPEC:
                update(digest, ((Operand.CleanupSpec) operand).getCleanupId());
                return;
            case HANDLER_SPEC: {
                Operand.HandlerSpec spec = (Operand.HandlerSpec) operand;
                update(digest, spec.getHandlerId());
                update(digest, spec.getCleanupRegionId());
                update(digest, spec.getAlias() == null
                        ? ""
                        : spec.getAlias().toString());
                return;
            }
            case WITH_SPEC: {
                Operand.WithSpec spec = (Operand.WithSpec) operand;
                update(digest, spec.getCleanupId());
                update(digest, spec.getResourceSlot());
                return;
            }
        }
        throw new AssertionError("Unhandled operand kind " + operand.getKind());
    }

    private static void fingerprintAnchor(
            MessageDigest digest, StackAnchor anchor) {
        if (anchor == null) {
            update(digest, -1);
            return;
        }
        update(digest, anchor.getId());
        update(digest, anchor.getOffset());
        update(digest, anchor.getStackValues().size());
        for (StackValueDescriptor value : anchor.getStackValues()) {
            update(digest, value.getKind().name());
            update(digest, value.getProvenance());
        }
    }

    private static void updateStrings(
            MessageDigest digest, List<String> values) {
        update(digest, values.size());
        for (String value : values) {
            update(digest, value);
        }
    }

    private static void updateIntegers(
            MessageDigest digest, List<Integer> values) {
        update(digest, values.size());
        for (Integer value : values) {
            update(digest, value);
        }
    }

    private static void update(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        update(digest, bytes.length);
        digest.update(bytes);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xf, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }
}
