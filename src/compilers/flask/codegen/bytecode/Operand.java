package compilers.flask.codegen.bytecode;

import compilers.flask.codegen.analysis.ResolvedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Closed family of typed instruction operands. */
public interface Operand {
    OperandKind getKind();

    enum OperandKind {
        NONE, CONST, NAME, LOCAL_SLOT, DEREF_SLOT, TEMP_SLOT, JUMP, COUNT,
        UNARY_OPERATOR, BINARY_OPERATOR, COMPARE_OPERATOR, CALL_SPEC,
        MAKE_FUNCTION_SPEC, BUILD_CLASS_SPEC, IMPORT_SPEC, CLEANUP_SPEC,
        HANDLER_SPEC, WITH_SPEC
    }

    final class NoOperand implements Operand {
        public static final NoOperand INSTANCE = new NoOperand();
        private NoOperand() { }
        @Override public OperandKind getKind() { return OperandKind.NONE; }
        @Override public String toString() { return ""; }
    }

    abstract class IndexedOperand implements Operand {
        private final int index;
        IndexedOperand(int index) {
            if (index < 0) throw new IllegalArgumentException("Index cannot be negative");
            this.index = index;
        }
        public final int getIndex() { return index; }
        @Override public String toString() { return String.valueOf(index); }
    }

    final class ConstOperand extends IndexedOperand {
        public ConstOperand(int index) { super(index); }
        @Override public OperandKind getKind() { return OperandKind.CONST; }
    }
    final class NameOperand extends IndexedOperand {
        public NameOperand(int index) { super(index); }
        @Override public OperandKind getKind() { return OperandKind.NAME; }
    }
    final class LocalSlotOperand extends IndexedOperand {
        public LocalSlotOperand(int index) { super(index); }
        @Override public OperandKind getKind() { return OperandKind.LOCAL_SLOT; }
    }
    final class DerefSlotOperand extends IndexedOperand {
        public DerefSlotOperand(int index) { super(index); }
        @Override public OperandKind getKind() { return OperandKind.DEREF_SLOT; }
    }
    final class TempSlotOperand extends IndexedOperand {
        public TempSlotOperand(int index) { super(index); }
        @Override public OperandKind getKind() { return OperandKind.TEMP_SLOT; }
    }

    final class JumpOperand implements Operand {
        private final int targetOffset;
        public JumpOperand(int targetOffset) {
            if (targetOffset < 0) throw new IllegalArgumentException("Jump target cannot be negative");
            this.targetOffset = targetOffset;
        }
        public int getTargetOffset() { return targetOffset; }
        @Override public OperandKind getKind() { return OperandKind.JUMP; }
        @Override public String toString() { return String.valueOf(targetOffset); }
    }

    final class CountOperand implements Operand {
        private final int count;
        public CountOperand(int count) {
            if (count < 0) throw new IllegalArgumentException("Count cannot be negative");
            this.count = count;
        }
        public int getCount() { return count; }
        @Override public OperandKind getKind() { return OperandKind.COUNT; }
        @Override public String toString() { return String.valueOf(count); }
    }

    enum UnaryOperator { POSITIVE, NEGATIVE, NOT }
    enum BinaryOperator {
        ADD, SUBTRACT, MULTIPLY, TRUE_DIVIDE, FLOOR_DIVIDE, MODULO, POWER,
        INPLACE_ADD, INPLACE_SUBTRACT, INPLACE_MULTIPLY, INPLACE_TRUE_DIVIDE
    }
    enum CompareOperator { EQ, NE, LT, LE, GT, GE, IN, IS }

    final class UnaryOperatorOperand implements Operand {
        private final UnaryOperator operator;
        public UnaryOperatorOperand(UnaryOperator operator) { this.operator = Objects.requireNonNull(operator); }
        public UnaryOperator getOperator() { return operator; }
        @Override public OperandKind getKind() { return OperandKind.UNARY_OPERATOR; }
        @Override public String toString() { return operator.name(); }
    }
    final class BinaryOperatorOperand implements Operand {
        private final BinaryOperator operator;
        public BinaryOperatorOperand(BinaryOperator operator) { this.operator = Objects.requireNonNull(operator); }
        public BinaryOperator getOperator() { return operator; }
        @Override public OperandKind getKind() { return OperandKind.BINARY_OPERATOR; }
        @Override public String toString() { return operator.name(); }
    }
    final class CompareOperatorOperand implements Operand {
        private final CompareOperator operator;
        public CompareOperatorOperand(CompareOperator operator) { this.operator = Objects.requireNonNull(operator); }
        public CompareOperator getOperator() { return operator; }
        @Override public OperandKind getKind() { return OperandKind.COMPARE_OPERATOR; }
        @Override public String toString() { return operator.name(); }
    }

    final class CallSpec implements Operand {
        private final int positionalCount;
        private final List<String> orderedKeywordNames;
        public CallSpec(int positionalCount, List<String> orderedKeywordNames) {
            if (positionalCount < 0) throw new IllegalArgumentException("Positional count cannot be negative");
            this.positionalCount = positionalCount;
            this.orderedKeywordNames = immutableNames(orderedKeywordNames);
        }
        public int getPositionalCount() { return positionalCount; }
        public List<String> getOrderedKeywordNames() { return orderedKeywordNames; }
        public int getKeywordCount() { return orderedKeywordNames.size(); }
        @Override public OperandKind getKind() { return OperandKind.CALL_SPEC; }
        @Override public String toString() { return "p=" + positionalCount + " kw=" + orderedKeywordNames; }
    }

    final class MakeFunctionSpec implements Operand {
        private final List<String> orderedDefaultParameterNames;
        private final List<String> orderedAnnotationNames;
        private final List<String> orderedFreeVarNames;
        public MakeFunctionSpec(List<String> defaults, List<String> annotations, List<String> freeVars) {
            this.orderedDefaultParameterNames = immutableNames(defaults);
            this.orderedAnnotationNames = immutableNames(annotations);
            this.orderedFreeVarNames = immutableNames(freeVars);
        }
        public List<String> getOrderedDefaultParameterNames() { return orderedDefaultParameterNames; }
        public List<String> getOrderedAnnotationNames() { return orderedAnnotationNames; }
        public List<String> getOrderedFreeVarNames() { return orderedFreeVarNames; }
        @Override public OperandKind getKind() { return OperandKind.MAKE_FUNCTION_SPEC; }
        @Override public String toString() {
            return "defaults=" + orderedDefaultParameterNames
                    + " annotations=" + orderedAnnotationNames
                    + " free=" + orderedFreeVarNames;
        }
    }

    final class BuildClassSpec implements Operand {
        private final String className;
        private final int baseCount;
        private final List<String> orderedFreeVarNames;
        public BuildClassSpec(String className, int baseCount, List<String> freeVars) {
            this.className = requireName(className);
            if (baseCount < 0) throw new IllegalArgumentException("Base count cannot be negative");
            this.baseCount = baseCount;
            this.orderedFreeVarNames = immutableNames(freeVars);
        }
        public String getClassName() { return className; }
        public int getBaseCount() { return baseCount; }
        public List<String> getOrderedFreeVarNames() { return orderedFreeVarNames; }
        @Override public OperandKind getKind() { return OperandKind.BUILD_CLASS_SPEC; }
        @Override public String toString() {
            return "name=" + className + " bases=" + baseCount
                    + " free=" + orderedFreeVarNames;
        }
    }

    final class ImportSpec implements Operand {
        public enum ResultMode {
            LEAF,
            TOP_LEVEL
        }

        private final String moduleName;
        private final ResultMode resultMode;
        public ImportSpec(String moduleName) {
            this(moduleName, ResultMode.LEAF);
        }
        public ImportSpec(String moduleName, ResultMode resultMode) {
            this.moduleName = requireName(moduleName);
            this.resultMode = Objects.requireNonNull(resultMode, "resultMode");
        }
        public String getModuleName() { return moduleName; }
        public ResultMode getResultMode() { return resultMode; }
        @Override public OperandKind getKind() { return OperandKind.IMPORT_SPEC; }
        @Override public String toString() {
            return moduleName + " result=" + resultMode;
        }
    }

    final class CleanupSpec implements Operand {
        private final int cleanupId;
        public CleanupSpec(int cleanupId) {
            if (cleanupId < 0) throw new IllegalArgumentException("Cleanup id cannot be negative");
            this.cleanupId = cleanupId;
        }
        public int getCleanupId() { return cleanupId; }
        @Override public OperandKind getKind() { return OperandKind.CLEANUP_SPEC; }
        @Override public String toString() { return "cleanup=" + cleanupId; }
    }

    final class HandlerSpec implements Operand {
        private final int handlerId;
        private final int cleanupRegionId;
        private final ResolvedName alias;
        public HandlerSpec(int handlerId, int cleanupRegionId) {
            this(handlerId, cleanupRegionId, null);
        }
        public HandlerSpec(
                int handlerId,
                int cleanupRegionId,
                ResolvedName alias) {
            if (handlerId < 0 || cleanupRegionId < 0) throw new IllegalArgumentException("Handler ids cannot be negative");
            this.handlerId = handlerId;
            this.cleanupRegionId = cleanupRegionId;
            this.alias = alias;
        }
        public int getHandlerId() { return handlerId; }
        public int getCleanupRegionId() { return cleanupRegionId; }
        public ResolvedName getAlias() { return alias; }
        @Override public OperandKind getKind() { return OperandKind.HANDLER_SPEC; }
        @Override public String toString() {
            return "handler=" + handlerId + " cleanup=" + cleanupRegionId
                    + " alias=" + (alias == null ? "<none>" : alias.toString());
        }
    }

    final class WithSpec implements Operand {
        private final int cleanupId;
        private final int resourceSlot;
        public WithSpec(int cleanupId, int resourceSlot) {
            if (cleanupId < 0 || resourceSlot < 0) throw new IllegalArgumentException("With ids cannot be negative");
            this.cleanupId = cleanupId;
            this.resourceSlot = resourceSlot;
        }
        public int getCleanupId() { return cleanupId; }
        public int getResourceSlot() { return resourceSlot; }
        @Override public OperandKind getKind() { return OperandKind.WITH_SPEC; }
        @Override public String toString() {
            return "cleanup=" + cleanupId + " resource=t" + resourceSlot;
        }
    }

    static List<String> immutableNames(List<String> names) {
        if (names == null) return Collections.emptyList();
        List<String> copy = new ArrayList<>();
        for (String name : names) copy.add(requireName(name));
        return Collections.unmodifiableList(copy);
    }

    static String requireName(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Name cannot be empty");
        return name;
    }

    /** Matches the identifier grammar accepted by the Flask/Python front end. */
    static boolean isIdentifier(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    /** A canonical absolute module name is one or more dotted identifiers. */
    static boolean isCanonicalModuleName(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String[] components = value.split("\\.", -1);
        for (String component : components) {
            if (!isIdentifier(component)) {
                return false;
            }
        }
        return true;
    }
}
