package compilers.flask.codegen.bytecode;

import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.codegen.analysis.ResolvedName;
import compilers.flask.vm.values.PyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Per-code-object symbolic builder.  Labels cannot escape to a different
 * builder, and sealing prevents generation state from changing under the
 * assembler.
 */
public final class CodeObjectBuilder {
    public interface Entry { }

    public static final class LabelEntry implements Entry {
        private final Label label;
        private LabelEntry(Label label) { this.label = label; }
        public Label getLabel() { return label; }
    }

    public static final class InstructionEntry implements Entry {
        private final OpCode opCode;
        private final Operand operand;
        private final Label jumpTarget;
        private final InstructionLocation location;

        private InstructionEntry(
                OpCode opCode,
                Operand operand,
                Label jumpTarget,
                InstructionLocation location) {
            this.opCode = opCode;
            this.operand = operand;
            this.jumpTarget = jumpTarget;
            this.location = location;
        }

        public OpCode getOpCode() { return opCode; }
        public Operand getOperand() { return operand; }
        public Label getJumpTarget() { return jumpTarget; }
        public InstructionLocation getLocation() { return location; }
        public boolean hasSymbolicJump() { return jumpTarget != null; }
    }

    public static final class ExceptionRegionSpec {
        private final int id;
        private final int parentBoundaryId;
        private final int nestingDepth;
        private final Label start;
        private final Label end;
        private final Label handler;

        private ExceptionRegionSpec(
                int id,
                int parentBoundaryId,
                int nestingDepth,
                Label start,
                Label end,
                Label handler) {
            this.id = id;
            this.parentBoundaryId = parentBoundaryId;
            this.nestingDepth = nestingDepth;
            this.start = start;
            this.end = end;
            this.handler = handler;
        }

        public int getId() { return id; }
        public int getParentBoundaryId() { return parentBoundaryId; }
        public int getNestingDepth() { return nestingDepth; }
        public Label getStart() { return start; }
        public Label getEnd() { return end; }
        public Label getHandler() { return handler; }
    }

    public static final class CleanupRegionSpec {
        private final int id;
        private final int parentBoundaryId;
        private final int nestingDepth;
        private final CleanupRegion.Kind kind;
        private final Label start;
        private final Label end;
        private final Label handler;
        private final Label normalContinuation;
        private final List<Integer> resourceSlots;
        private final int handlerId;
        private final ResolvedName alias;

        private CleanupRegionSpec(
                int id,
                int parentBoundaryId,
                int nestingDepth,
                CleanupRegion.Kind kind,
                Label start,
                Label end,
                Label handler,
                Label normalContinuation,
                List<Integer> resourceSlots,
                int handlerId,
                ResolvedName alias) {
            this.id = id;
            this.parentBoundaryId = parentBoundaryId;
            this.nestingDepth = nestingDepth;
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.handler = handler;
            this.normalContinuation = normalContinuation;
            this.resourceSlots = immutableSlots(resourceSlots);
            this.handlerId = handlerId;
            this.alias = alias;
        }

        public int getId() { return id; }
        public int getParentBoundaryId() { return parentBoundaryId; }
        public int getNestingDepth() { return nestingDepth; }
        public CleanupRegion.Kind getKind() { return kind; }
        public Label getStart() { return start; }
        public Label getEnd() { return end; }
        public Label getHandler() { return handler; }
        public Label getNormalContinuation() { return normalContinuation; }
        public List<Integer> getResourceSlots() { return resourceSlots; }
        public int getResourceSlot() {
            return resourceSlots.isEmpty() ? -1 : resourceSlots.get(0);
        }
        public int getHandlerId() { return handlerId; }
        public ResolvedName getAlias() { return alias; }
    }

    private final Object ownerToken = new Object();
    private final int codeId;
    private final CodeKind kind;
    private final String name;
    private final String qualifiedName;
    private final String sourceFile;
    private final FunctionSignature signature;
    private final List<String> fastLocalNames;
    private final List<String> cellVariableNames;
    private final List<String> freeVariableNames;
    private final ConstantPool.Builder constants = new ConstantPool.Builder();
    private final NamePool.Builder names = new NamePool.Builder();
    private final List<Entry> entries = new ArrayList<>();
    private final IdentityHashMap<Label, Boolean> labels = new IdentityHashMap<>();
    private final List<Label> createdLabels = new ArrayList<>();
    private final IdentityHashMap<Label, Boolean> placedLabels = new IdentityHashMap<>();
    private final List<ExceptionRegionSpec> exceptionRegions = new ArrayList<>();
    private final List<CleanupRegionSpec> cleanupRegions = new ArrayList<>();
    private int nextLabelId;
    private int nextRegionId;
    private int temporarySlotCount;
    private boolean sealed;

    public CodeObjectBuilder(
            int codeId,
            CodeKind kind,
            String name,
            String qualifiedName,
            String sourceFile,
            FunctionSignature signature,
            List<String> fastLocalNames,
            List<String> cellVariableNames,
            List<String> freeVariableNames) {
        if (codeId < 0) {
            throw new IllegalArgumentException("Code id cannot be negative");
        }
        this.codeId = codeId;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.name = requireText(name, "name");
        this.qualifiedName = requireText(qualifiedName, "qualifiedName");
        this.sourceFile = sourceFile == null ? "" : sourceFile;
        this.signature = Objects.requireNonNull(signature, "signature");
        this.fastLocalNames = immutableNames(fastLocalNames);
        this.cellVariableNames = immutableNames(cellVariableNames);
        this.freeVariableNames = immutableNames(freeVariableNames);
    }

    public int addConstant(PyValue value) {
        requireMutable();
        return constants.add(value);
    }

    public int addCodeObject(CodeObject codeObject) {
        requireMutable();
        return constants.add(codeObject);
    }

    public int addCodeObjectBuilder(CodeObjectBuilder codeBuilder) {
        requireMutable();
        if (codeBuilder == this) {
            throw new IllegalArgumentException(
                    "A code object cannot contain its own symbolic builder");
        }
        return constants.add(codeBuilder);
    }

    public int addName(String name) {
        requireMutable();
        return names.add(name);
    }

    public Label newLabel(String hint) {
        requireMutable();
        Label label = new Label(ownerToken, nextLabelId++, hint);
        labels.put(label, Boolean.TRUE);
        createdLabels.add(label);
        return label;
    }

    public void mark(Label label) {
        requireMutable();
        requireOwned(label);
        if (placedLabels.put(label, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Label was placed more than once: " + label);
        }
        entries.add(new LabelEntry(label));
    }

    public void emit(OpCode opCode, SourceSpan span, boolean synthetic) {
        emit(opCode, Operand.NoOperand.INSTANCE, span, synthetic);
    }

    public void emit(
            OpCode opCode, Operand operand, SourceSpan span, boolean synthetic) {
        requireMutable();
        Objects.requireNonNull(opCode, "opCode");
        Objects.requireNonNull(operand, "operand");
        if (opCode.getOperandKind() == Operand.OperandKind.JUMP) {
            throw new IllegalArgumentException(
                    "Symbolic builder jump instructions require emitJump");
        }
        // Validate the schema and count-specific contract at emission time.
        new Instruction(opCode, operand);
        entries.add(new InstructionEntry(
                opCode, operand, null, new InstructionLocation(span, synthetic)));
    }

    public void emitJump(
            OpCode opCode, Label target, SourceSpan span, boolean synthetic) {
        requireMutable();
        Objects.requireNonNull(opCode, "opCode");
        if (opCode.getOperandKind() != Operand.OperandKind.JUMP) {
            throw new IllegalArgumentException(opCode + " is not a jump instruction");
        }
        requireOwned(target);
        entries.add(new InstructionEntry(
                opCode, null, target, new InstructionLocation(span, synthetic)));
    }

    public int addExceptionRegion(Label start, Label end, Label handler) {
        return addExceptionRegion(-1, 0, start, end, handler);
    }

    public int addExceptionRegion(
            int parentBoundaryId,
            int nestingDepth,
            Label start,
            Label end,
            Label handler) {
        requireMutable();
        if (parentBoundaryId < -1 || nestingDepth < 0) {
            throw new IllegalArgumentException("Invalid exception-region nesting metadata");
        }
        requireOwned(start);
        requireOwned(end);
        requireOwned(handler);
        int id = nextRegionId++;
        exceptionRegions.add(new ExceptionRegionSpec(
                id, parentBoundaryId, nestingDepth, start, end, handler));
        return id;
    }

    public int addCleanupRegion(
            CleanupRegion.Kind kind,
            Label start,
            Label end,
            Label handler,
            Label normalContinuation,
            int resourceSlot) {
        List<Integer> slots = resourceSlot < 0
                ? Collections.<Integer>emptyList()
                : Collections.singletonList(resourceSlot);
        return addCleanupRegion(
                -1, 0, kind, start, end, handler, normalContinuation,
                slots, -1, null);
    }

    public int addCleanupRegion(
            int parentBoundaryId,
            int nestingDepth,
            CleanupRegion.Kind kind,
            Label start,
            Label end,
            Label handler,
            Label normalContinuation,
            List<Integer> resourceSlots,
            int handlerId,
            ResolvedName alias) {
        requireMutable();
        Objects.requireNonNull(kind, "kind");
        if (parentBoundaryId < -1 || nestingDepth < 0 || handlerId < -1) {
            throw new IllegalArgumentException("Invalid cleanup-region metadata");
        }
        requireOwned(start);
        requireOwned(end);
        if (handler != null) requireOwned(handler);
        if (normalContinuation != null) requireOwned(normalContinuation);
        int id = nextRegionId++;
        cleanupRegions.add(new CleanupRegionSpec(
                id, parentBoundaryId, nestingDepth, kind, start, end,
                handler, normalContinuation, resourceSlots, handlerId, alias));
        return id;
    }

    public void setTemporarySlotCount(int temporarySlotCount) {
        requireMutable();
        if (temporarySlotCount < 0) {
            throw new IllegalArgumentException("Temporary-slot count cannot be negative");
        }
        this.temporarySlotCount = temporarySlotCount;
    }

    public void seal() {
        sealed = true;
    }

    public boolean isSealed() { return sealed; }
    public int getCodeId() { return codeId; }
    public CodeKind getKind() { return kind; }
    public String getName() { return name; }
    public String getQualifiedName() { return qualifiedName; }
    public String getSourceFile() { return sourceFile; }
    public FunctionSignature getSignature() { return signature; }
    public List<String> getFastLocalNames() { return fastLocalNames; }
    public List<String> getCellVariableNames() { return cellVariableNames; }
    public List<String> getFreeVariableNames() { return freeVariableNames; }
    public int getTemporarySlotCount() { return temporarySlotCount; }
    public List<Entry> getEntries() {
        requireSealed();
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }
    public List<ExceptionRegionSpec> getExceptionRegionSpecs() {
        requireSealed();
        return Collections.unmodifiableList(new ArrayList<>(exceptionRegions));
    }
    public List<Label> getLabels() {
        requireSealed();
        return Collections.unmodifiableList(new ArrayList<>(createdLabels));
    }
    public List<CleanupRegionSpec> getCleanupRegionSpecs() {
        requireSealed();
        return Collections.unmodifiableList(new ArrayList<>(cleanupRegions));
    }
    public ConstantPool buildConstantPool() { requireSealed(); return constants.build(); }
    public ConstantPool buildConstantPool(
            java.util.function.Function<CodeObjectBuilder, CodeObject> resolver) {
        requireSealed();
        return constants.buildResolved(resolver);
    }
    public NamePool buildNamePool() { requireSealed(); return names.build(); }

    private void requireOwned(Label label) {
        Objects.requireNonNull(label, "label");
        if (!label.isOwnedBy(ownerToken) || !labels.containsKey(label)) {
            throw new IllegalArgumentException("Cross-code or unknown label: " + label);
        }
    }

    private void requireMutable() {
        if (sealed) {
            throw new IllegalStateException("CodeObjectBuilder is sealed");
        }
    }

    private void requireSealed() {
        if (!sealed) {
            throw new IllegalStateException("Seal CodeObjectBuilder before assembly");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " cannot be empty");
        }
        return value;
    }

    private static List<String> immutableNames(List<String> source) {
        Objects.requireNonNull(source, "name table");
        List<String> copy = new ArrayList<>(source.size());
        for (String value : source) {
            copy.add(requireText(value, "name"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<Integer> immutableSlots(List<Integer> source) {
        Objects.requireNonNull(source, "resourceSlots");
        List<Integer> copy = new ArrayList<>(source.size());
        for (Integer slot : source) {
            if (slot == null || slot < 0) {
                throw new IllegalArgumentException("Resource slot cannot be negative");
            }
            copy.add(slot);
        }
        return Collections.unmodifiableList(copy);
    }
}
