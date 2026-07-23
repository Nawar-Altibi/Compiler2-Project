package compilers.flask.codegen.bytecode;

import compilers.flask.codegen.bytecode.Operand.OperandKind;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Frozen instruction inventory and its first-line contract metadata.
 *
 * <p>Edge-sensitive stack transitions live in the verifier, while operand
 * schema, termination, and conservative exception behavior are owned here so
 * an opcode cannot exist without those contracts.</p>
 */
public enum OpCode {
    NOP(OperandKind.NONE, false, false),
    POP_TOP(OperandKind.NONE, false, false),
    COPY(OperandKind.COUNT, false, false),
    SWAP(OperandKind.COUNT, false, false),

    LOAD_CONST(OperandKind.CONST, false, false),
    LOAD_NAME(OperandKind.NAME, true, false),
    STORE_NAME(OperandKind.NAME, false, false),
    DELETE_NAME(OperandKind.NAME, true, false),
    LOAD_FAST(OperandKind.LOCAL_SLOT, true, false),
    STORE_FAST(OperandKind.LOCAL_SLOT, false, false),
    DELETE_FAST(OperandKind.LOCAL_SLOT, true, false),
    LOAD_GLOBAL(OperandKind.NAME, true, false),
    STORE_GLOBAL(OperandKind.NAME, false, false),
    DELETE_GLOBAL(OperandKind.NAME, true, false),
    LOAD_DEREF(OperandKind.DEREF_SLOT, true, false),
    STORE_DEREF(OperandKind.DEREF_SLOT, false, false),
    DELETE_DEREF(OperandKind.DEREF_SLOT, true, false),
    LOAD_CLOSURE(OperandKind.DEREF_SLOT, false, false),
    LOAD_TEMP(OperandKind.TEMP_SLOT, false, false),
    STORE_TEMP(OperandKind.TEMP_SLOT, false, false),
    CLEAR_TEMP(OperandKind.TEMP_SLOT, false, false),

    LOAD_ATTR(OperandKind.NAME, true, false),
    STORE_ATTR(OperandKind.NAME, true, false),
    DELETE_ATTR(OperandKind.NAME, true, false),
    BINARY_SUBSCR(OperandKind.NONE, true, false),
    STORE_SUBSCR(OperandKind.NONE, true, false),
    DELETE_SUBSCR(OperandKind.NONE, true, false),
    BUILD_LIST(OperandKind.COUNT, true, false),
    BUILD_TUPLE(OperandKind.COUNT, true, false),
    BUILD_SET(OperandKind.COUNT, true, false),
    BUILD_MAP(OperandKind.COUNT, true, false),
    UNPACK_SEQUENCE(OperandKind.COUNT, true, false),
    FORMAT_VALUE(OperandKind.NONE, true, false),
    BUILD_STRING(OperandKind.COUNT, true, false),
    GET_ITER(OperandKind.NONE, true, false),
    FOR_ITER(OperandKind.JUMP, true, false),

    UNARY_OP(OperandKind.UNARY_OPERATOR, true, false),
    BINARY_OP(OperandKind.BINARY_OPERATOR, true, false),
    COMPARE_OP(OperandKind.COMPARE_OPERATOR, true, false),

    CALL(OperandKind.CALL_SPEC, true, false),
    MAKE_FUNCTION(OperandKind.MAKE_FUNCTION_SPEC, true, false),
    BUILD_CLASS(OperandKind.BUILD_CLASS_SPEC, true, false),

    IMPORT_NAME(OperandKind.IMPORT_SPEC, true, false),
    IMPORT_FROM(OperandKind.NAME, true, false),
    IMPORT_STAR(OperandKind.NONE, true, false),

    JUMP(OperandKind.JUMP, false, true),
    POP_JUMP_IF_FALSE(OperandKind.JUMP, true, false),
    POP_JUMP_IF_TRUE(OperandKind.JUMP, true, false),
    JUMP_IF_FALSE_OR_POP(OperandKind.JUMP, true, false),
    JUMP_IF_TRUE_OR_POP(OperandKind.JUMP, true, false),
    UNWIND_JUMP(OperandKind.JUMP, false, true),
    RETURN_VALUE(OperandKind.NONE, false, true),
    RAISE(OperandKind.COUNT, false, true),

    LOAD_CURRENT_EXCEPTION(OperandKind.NONE, true, false),
    EXCEPTION_MATCH(OperandKind.NONE, true, false),
    BEGIN_EXCEPT(OperandKind.HANDLER_SPEC, false, false),
    END_EXCEPT(OperandKind.CLEANUP_SPEC, false, false),
    ENTER_CLEANUP(OperandKind.CLEANUP_SPEC, false, true),
    END_CLEANUP(OperandKind.CLEANUP_SPEC, false, true),
    WITH_ENTER(OperandKind.WITH_SPEC, true, false),
    WITH_EXIT(OperandKind.WITH_SPEC, true, false);

    private static final Set<OpCode> ALL = Collections.unmodifiableSet(
            EnumSet.allOf(OpCode.class));

    private final OperandKind operandKind;
    private final boolean mayRaise;
    private final boolean terminator;

    OpCode(OperandKind operandKind, boolean mayRaise, boolean terminator) {
        this.operandKind = operandKind;
        this.mayRaise = mayRaise;
        this.terminator = terminator;
    }

    public OperandKind getOperandKind() {
        return operandKind;
    }

    public boolean mayRaise() {
        return mayRaise;
    }

    public boolean isTerminator() {
        return terminator;
    }

    public static Set<OpCode> allOpcodes() {
        return ALL;
    }
}
