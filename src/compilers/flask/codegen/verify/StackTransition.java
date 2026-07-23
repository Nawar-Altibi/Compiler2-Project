package compilers.flask.codegen.verify;

import compilers.flask.codegen.bytecode.Instruction;
import compilers.flask.codegen.bytecode.OpCode;
import compilers.flask.codegen.bytecode.Operand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Edge-sensitive, typed operand-stack contract for one bytecode instruction.
 *
 * <p>The transition deliberately describes abstract kinds rather than runtime
 * Java classes.  Exact constant/code/cell provenance is supplied and checked
 * by {@link ControlFlowVerifier}; this class owns the mechanical stack shape
 * and the broad producer/consumer contract for every opcode.</p>
 */
public final class StackTransition {
    /** A normal or abrupt successor leaving an instruction. */
    public enum Edge {
        FALLTHROUGH,
        JUMP_TAKEN,
        ITER_ITEM,
        ITER_EXHAUSTED,
        EXCEPTIONAL,
        ABRUPT_TRANSFER,
        CLEANUP_ENTRY
    }

    /** Broad verifier kind.  {@code ANY} is legal only for stack movement/discard. */
    public enum ValueKind {
        ANY,
        PY_VALUE,
        CELL_REF,
        CODE_REF
    }

    /** Producer whose exact abstract value is resolved by the CFG verifier. */
    public enum Producer {
        PY_VALUE,
        CELL_REF,
        CONSTANT,
        COPY_AT_DEPTH
    }

    /** Non-linear state/stack behavior which the verifier must complete. */
    public enum Special {
        NONE,
        COPY,
        SWAP,
        LOAD_CONST,
        LOAD_CLOSURE,
        LOAD_TEMP,
        STORE_TEMP,
        CLEAR_TEMP,
        MAKE_FUNCTION,
        BUILD_CLASS,
        BEGIN_EXCEPT,
        END_EXCEPT,
        ENTER_CLEANUP,
        END_CLEANUP,
        WITH_ENTER,
        WITH_EXIT,
        RESTORE_REGION_ANCHOR,
        ABRUPT_TRANSFER
    }

    private static final Set<OpCode> HANDLED = Collections.unmodifiableSet(
            EnumSet.allOf(OpCode.class));

    private final Edge edge;
    private final List<ValueKind> poppedFromTop;
    private final List<Producer> pushed;
    private final Special special;

    private StackTransition(
            Edge edge,
            List<ValueKind> poppedFromTop,
            List<Producer> pushed,
            Special special) {
        this.edge = Objects.requireNonNull(edge, "edge");
        this.poppedFromTop = immutableCopy(poppedFromTop);
        this.pushed = immutableCopy(pushed);
        this.special = Objects.requireNonNull(special, "special");
    }

    /**
     * Returns the transition for the selected successor edge.
     *
     * @throws IllegalArgumentException when the requested edge cannot leave
     *         the instruction under the frozen opcode contract
     */
    public static StackTransition transition(Instruction instruction, Edge edge) {
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(edge, "edge");
        OpCode opCode = instruction.getOpCode();

        if (edge == Edge.EXCEPTIONAL) {
            if (!opCode.mayRaise() && opCode != OpCode.RAISE) {
                throw illegalEdge(opCode, edge);
            }
            return of(edge, Special.RESTORE_REGION_ANCHOR);
        }
        if (edge == Edge.CLEANUP_ENTRY) {
            if (opCode != OpCode.ENTER_CLEANUP
                    && opCode != OpCode.UNWIND_JUMP
                    && opCode != OpCode.RETURN_VALUE
                    && opCode != OpCode.RAISE
                    && opCode != OpCode.END_CLEANUP) {
                throw illegalEdge(opCode, edge);
            }
            return of(edge, Special.RESTORE_REGION_ANCHOR);
        }
        if (edge == Edge.ABRUPT_TRANSFER) {
            if (opCode != OpCode.UNWIND_JUMP
                    && opCode != OpCode.RETURN_VALUE
                    && opCode != OpCode.RAISE
                    && opCode != OpCode.END_CLEANUP) {
                throw illegalEdge(opCode, edge);
            }
            return abruptTransition(instruction, edge);
        }

        validateNormalEdge(opCode, edge);

        switch (opCode) {
            case NOP:
                return of(edge);
            case POP_TOP:
                return pop(edge, ValueKind.ANY);
            case COPY:
                return of(edge, list(), list(Producer.COPY_AT_DEPTH), Special.COPY);
            case SWAP:
                return of(edge, list(), list(), Special.SWAP);

            case LOAD_CONST:
                return of(edge, list(), list(Producer.CONSTANT), Special.LOAD_CONST);
            case LOAD_NAME:
            case LOAD_FAST:
            case LOAD_GLOBAL:
            case LOAD_DEREF:
            case LOAD_CURRENT_EXCEPTION:
                return pushPy(edge);
            case STORE_NAME:
            case STORE_FAST:
            case STORE_GLOBAL:
            case STORE_DEREF:
                return pop(edge, ValueKind.PY_VALUE);
            case DELETE_NAME:
            case DELETE_FAST:
            case DELETE_GLOBAL:
            case DELETE_DEREF:
                return of(edge);
            case LOAD_CLOSURE:
                return of(edge, list(), list(Producer.CELL_REF), Special.LOAD_CLOSURE);
            case LOAD_TEMP:
                return of(edge, list(), list(Producer.PY_VALUE), Special.LOAD_TEMP);
            case STORE_TEMP:
                return of(edge, list(ValueKind.PY_VALUE), list(), Special.STORE_TEMP);
            case CLEAR_TEMP:
                return of(edge, list(), list(), Special.CLEAR_TEMP);

            case LOAD_ATTR:
                return replaceWithPy(edge, 1);
            case STORE_ATTR:
                return popPy(edge, 2);
            case DELETE_ATTR:
                return popPy(edge, 1);
            case BINARY_SUBSCR:
                return replaceWithPy(edge, 2);
            case STORE_SUBSCR:
                return popPy(edge, 3);
            case DELETE_SUBSCR:
                return popPy(edge, 2);
            case BUILD_LIST:
            case BUILD_TUPLE:
            case BUILD_SET:
            case BUILD_STRING:
                return popAndPushPy(edge, count(instruction));
            case BUILD_MAP:
                return popAndPushPy(edge, multiplyExact(count(instruction), 2, opCode));
            case UNPACK_SEQUENCE:
                return of(edge,
                        repeated(ValueKind.PY_VALUE, 1),
                        repeated(Producer.PY_VALUE, count(instruction)),
                        Special.NONE);
            case FORMAT_VALUE:
            case GET_ITER:
                return replaceWithPy(edge, 1);
            case FOR_ITER:
                if (edge == Edge.ITER_ITEM) {
                    return replaceWithPy(edge, 1);
                }
                return popPy(edge, 1);

            case UNARY_OP:
                return replaceWithPy(edge, 1);
            case BINARY_OP:
            case COMPARE_OP:
            case EXCEPTION_MATCH:
                return replaceWithPy(edge, 2);

            case CALL: {
                Operand.CallSpec spec = (Operand.CallSpec) instruction.getOperand();
                int arguments = addExact(
                        spec.getPositionalCount(), spec.getKeywordCount(), opCode);
                return popAndPushPy(edge, addExact(arguments, 1, opCode));
            }
            case MAKE_FUNCTION: {
                Operand.MakeFunctionSpec spec =
                        (Operand.MakeFunctionSpec) instruction.getOperand();
                List<ValueKind> popped = new ArrayList<>();
                popped.add(ValueKind.CODE_REF);
                append(popped, ValueKind.CELL_REF, spec.getOrderedFreeVarNames().size());
                append(popped, ValueKind.PY_VALUE, spec.getOrderedAnnotationNames().size());
                append(popped, ValueKind.PY_VALUE,
                        spec.getOrderedDefaultParameterNames().size());
                return of(edge, popped, list(Producer.PY_VALUE), Special.MAKE_FUNCTION);
            }
            case BUILD_CLASS: {
                Operand.BuildClassSpec spec =
                        (Operand.BuildClassSpec) instruction.getOperand();
                List<ValueKind> popped = new ArrayList<>();
                popped.add(ValueKind.CODE_REF);
                append(popped, ValueKind.CELL_REF, spec.getOrderedFreeVarNames().size());
                append(popped, ValueKind.PY_VALUE, spec.getBaseCount());
                return of(edge, popped, list(Producer.PY_VALUE), Special.BUILD_CLASS);
            }

            case IMPORT_NAME:
                return pushPy(edge);
            case IMPORT_FROM:
                return of(edge,
                        list(ValueKind.PY_VALUE),
                        list(Producer.PY_VALUE, Producer.PY_VALUE),
                        Special.NONE);
            case IMPORT_STAR:
                return popPy(edge, 1);

            case JUMP:
                return of(edge);
            case POP_JUMP_IF_FALSE:
            case POP_JUMP_IF_TRUE:
                return popPy(edge, 1);
            case JUMP_IF_FALSE_OR_POP:
            case JUMP_IF_TRUE_OR_POP:
                return edge == Edge.JUMP_TAKEN ? of(edge) : popPy(edge, 1);
            case UNWIND_JUMP:
                return of(edge, list(), list(), Special.ABRUPT_TRANSFER);
            case RETURN_VALUE:
            case RAISE:
                // These opcodes have no ordinary successor.
                throw illegalEdge(opCode, edge);

            case BEGIN_EXCEPT:
                return of(edge, list(), list(), Special.BEGIN_EXCEPT);
            case END_EXCEPT:
                return of(edge, list(), list(), Special.END_EXCEPT);
            case ENTER_CLEANUP:
                return of(edge, list(), list(), Special.ENTER_CLEANUP);
            case END_CLEANUP:
                return of(edge, list(), list(), Special.END_CLEANUP);
            case WITH_ENTER:
                return of(edge,
                        list(ValueKind.PY_VALUE),
                        list(Producer.PY_VALUE),
                        Special.WITH_ENTER);
            case WITH_EXIT:
                return of(edge, list(), list(), Special.WITH_EXIT);
        }
        throw new AssertionError("Unhandled opcode " + opCode);
    }

    /** Exact opcode inventory consumed by transition coverage tests. */
    public static Set<OpCode> handledOpcodes() {
        return HANDLED;
    }

    public Edge getEdge() {
        return edge;
    }

    /** Values popped in TOS-first order. */
    public List<ValueKind> getPoppedFromTop() {
        return poppedFromTop;
    }

    /** Values pushed in bottom-to-TOS order. */
    public List<Producer> getPushed() {
        return pushed;
    }

    public Special getSpecial() {
        return special;
    }

    public int getPopCount() {
        return poppedFromTop.size();
    }

    public int getPushCount() {
        return pushed.size();
    }

    public int getNetEffect() {
        return getPushCount() - getPopCount();
    }

    private static StackTransition abruptTransition(Instruction instruction, Edge edge) {
        switch (instruction.getOpCode()) {
            case UNWIND_JUMP:
            case END_CLEANUP:
                return of(edge, list(), list(), Special.ABRUPT_TRANSFER);
            case RETURN_VALUE:
                return of(edge,
                        list(ValueKind.PY_VALUE), list(), Special.ABRUPT_TRANSFER);
            case RAISE:
                return of(edge,
                        repeated(ValueKind.PY_VALUE, count(instruction)),
                        list(), Special.ABRUPT_TRANSFER);
            default:
                throw illegalEdge(instruction.getOpCode(), edge);
        }
    }

    private static void validateNormalEdge(OpCode opCode, Edge edge) {
        if (opCode == OpCode.UNWIND_JUMP
                || opCode == OpCode.RETURN_VALUE
                || opCode == OpCode.RAISE
                || opCode == OpCode.ENTER_CLEANUP
                || opCode == OpCode.END_CLEANUP) {
            throw illegalEdge(opCode, edge);
        }
        if (opCode == OpCode.FOR_ITER) {
            if (edge != Edge.ITER_ITEM && edge != Edge.ITER_EXHAUSTED) {
                throw illegalEdge(opCode, edge);
            }
            return;
        }
        if (edge == Edge.ITER_ITEM || edge == Edge.ITER_EXHAUSTED) {
            throw illegalEdge(opCode, edge);
        }

        boolean branch = opCode == OpCode.JUMP
                || opCode == OpCode.POP_JUMP_IF_FALSE
                || opCode == OpCode.POP_JUMP_IF_TRUE
                || opCode == OpCode.JUMP_IF_FALSE_OR_POP
                || opCode == OpCode.JUMP_IF_TRUE_OR_POP;
        if (opCode == OpCode.JUMP && edge != Edge.JUMP_TAKEN) {
            throw illegalEdge(opCode, edge);
        }
        if (branch && opCode != OpCode.JUMP
                && edge != Edge.JUMP_TAKEN && edge != Edge.FALLTHROUGH) {
            throw illegalEdge(opCode, edge);
        }
        if (!branch && edge != Edge.FALLTHROUGH) {
            throw illegalEdge(opCode, edge);
        }
    }

    private static StackTransition pushPy(Edge edge) {
        return of(edge, list(), list(Producer.PY_VALUE), Special.NONE);
    }

    private static StackTransition pop(Edge edge, ValueKind kind) {
        return of(edge, list(kind), list(), Special.NONE);
    }

    private static StackTransition popPy(Edge edge, int count) {
        return of(edge, repeated(ValueKind.PY_VALUE, count), list(), Special.NONE);
    }

    private static StackTransition replaceWithPy(Edge edge, int popCount) {
        return popAndPushPy(edge, popCount);
    }

    private static StackTransition popAndPushPy(Edge edge, int popCount) {
        return of(edge,
                repeated(ValueKind.PY_VALUE, popCount),
                list(Producer.PY_VALUE),
                Special.NONE);
    }

    private static StackTransition of(Edge edge) {
        return of(edge, list(), list(), Special.NONE);
    }

    private static StackTransition of(Edge edge, Special special) {
        return of(edge, list(), list(), special);
    }

    private static StackTransition of(
            Edge edge,
            List<ValueKind> popped,
            List<Producer> pushed,
            Special special) {
        return new StackTransition(edge, popped, pushed, special);
    }

    private static int count(Instruction instruction) {
        return ((Operand.CountOperand) instruction.getOperand()).getCount();
    }

    private static int addExact(int left, int right, OpCode opCode) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(opCode + " stack effect overflows", overflow);
        }
    }

    private static int multiplyExact(int left, int right, OpCode opCode) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(opCode + " stack effect overflows", overflow);
        }
    }

    private static IllegalArgumentException illegalEdge(OpCode opCode, Edge edge) {
        return new IllegalArgumentException(opCode + " has no " + edge + " successor");
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    @SafeVarargs
    private static <T> List<T> list(T... values) {
        List<T> result = new ArrayList<>(values.length);
        Collections.addAll(result, values);
        return result;
    }

    private static <T> List<T> repeated(T value, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative stack transition count");
        }
        List<T> result = new ArrayList<>(count);
        append(result, value, count);
        return result;
    }

    private static <T> void append(List<T> target, T value, int count) {
        for (int index = 0; index < count; index++) {
            target.add(value);
        }
    }
}
