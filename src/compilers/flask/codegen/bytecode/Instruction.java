package compilers.flask.codegen.bytecode;

import compilers.flask.codegen.bytecode.Operand.CountOperand;

import java.util.Objects;

/** One immutable, fully resolved bytecode instruction. */
public final class Instruction {
    private final OpCode opCode;
    private final Operand operand;

    public Instruction(OpCode opCode) {
        this(opCode, Operand.NoOperand.INSTANCE);
    }

    public Instruction(OpCode opCode, Operand operand) {
        this.opCode = Objects.requireNonNull(opCode, "opCode");
        this.operand = Objects.requireNonNull(operand, "operand");
        if (operand.getKind() != opCode.getOperandKind()) {
            throw new IllegalArgumentException(
                    opCode + " requires " + opCode.getOperandKind()
                            + " but received " + operand.getKind());
        }
        validateSpecialContract(opCode, operand);
    }

    private static void validateSpecialContract(OpCode opCode, Operand operand) {
        if ((opCode == OpCode.COPY || opCode == OpCode.SWAP)
                && ((CountOperand) operand).getCount() < 1) {
            throw new IllegalArgumentException(opCode + " depth is one-based");
        }
        if (opCode == OpCode.RAISE && ((CountOperand) operand).getCount() > 2) {
            throw new IllegalArgumentException("RAISE accepts only counts 0, 1, or 2");
        }
    }

    public OpCode getOpCode() {
        return opCode;
    }

    public Operand getOperand() {
        return operand;
    }

    @Override
    public String toString() {
        String rendered = operand.toString();
        return rendered.isEmpty() ? opCode.name() : opCode.name() + " " + rendered;
    }
}
