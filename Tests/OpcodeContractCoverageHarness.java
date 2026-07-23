import compilers.flask.codegen.bytecode.Instruction;
import compilers.flask.codegen.bytecode.OpCode;
import compilers.flask.codegen.bytecode.Operand;
import compilers.flask.codegen.verify.ControlFlowVerifier;
import compilers.flask.codegen.verify.StackTransition;
import compilers.flask.vm.BytecodeVM;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Build gate ensuring an opcode cannot be added without every core contract. */
public final class OpcodeContractCoverageHarness {
    private static int passed;
    private static int failed;

    private OpcodeContractCoverageHarness() {
    }

    public static void main(String[] args) {
        run("all inventories exactly match OpCode",
                OpcodeContractCoverageHarness::testInventories);
        run("every opcode constructs with exactly its typed schema",
                OpcodeContractCoverageHarness::testTypedConstruction);
        run("every opcode has edge-sensitive stack handling",
                OpcodeContractCoverageHarness::testStackTransitions);
        run("mayRaise whitelist is exact",
                OpcodeContractCoverageHarness::testMayRaise);
        run("inventories are immutable",
                OpcodeContractCoverageHarness::testInventoryImmutability);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " opcode-contract test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " opcode-contract tests passed for "
                + OpCode.values().length + " opcodes.");
    }

    private static void testInventories() {
        Set<OpCode> expected = EnumSet.allOf(OpCode.class);
        equal(expected, OpCode.allOpcodes(), "opcode inventory");
        equal(expected, StackTransition.handledOpcodes(), "stack inventory");
        equal(expected, ControlFlowVerifier.handledOpcodes(), "verifier inventory");
        equal(expected, BytecodeVM.dispatchedOpcodes(), "VM inventory");
    }

    private static void testTypedConstruction() {
        for (OpCode opCode : OpCode.values()) {
            Operand operand = sampleOperand(opCode);
            Instruction instruction = new Instruction(opCode, operand);
            equal(opCode.getOperandKind(), instruction.getOperand().getKind(),
                    opCode + " schema");
            Operand wrong = opCode.getOperandKind() == Operand.OperandKind.NONE
                    ? new Operand.CountOperand(0)
                    : Operand.NoOperand.INSTANCE;
            expectIllegal(() -> new Instruction(opCode, wrong),
                    opCode + " accepted wrong operand kind");
        }
    }

    private static void testStackTransitions() {
        for (OpCode opCode : OpCode.values()) {
            Instruction instruction = new Instruction(opCode, sampleOperand(opCode));
            switch (opCode) {
                case FOR_ITER:
                    StackTransition.transition(
                            instruction, StackTransition.Edge.ITER_ITEM);
                    StackTransition.transition(
                            instruction, StackTransition.Edge.ITER_EXHAUSTED);
                    break;
                case JUMP:
                    StackTransition.transition(
                            instruction, StackTransition.Edge.JUMP_TAKEN);
                    break;
                case POP_JUMP_IF_FALSE:
                case POP_JUMP_IF_TRUE:
                case JUMP_IF_FALSE_OR_POP:
                case JUMP_IF_TRUE_OR_POP:
                    StackTransition.transition(
                            instruction, StackTransition.Edge.JUMP_TAKEN);
                    StackTransition.transition(
                            instruction, StackTransition.Edge.FALLTHROUGH);
                    break;
                case UNWIND_JUMP:
                case RETURN_VALUE:
                case RAISE:
                case END_CLEANUP:
                    StackTransition.transition(
                            instruction, StackTransition.Edge.ABRUPT_TRANSFER);
                    expectIllegal(() -> StackTransition.transition(
                                    instruction, StackTransition.Edge.FALLTHROUGH),
                            opCode + " accepted fallthrough");
                    break;
                case ENTER_CLEANUP:
                    StackTransition.transition(
                            instruction, StackTransition.Edge.CLEANUP_ENTRY);
                    expectIllegal(() -> StackTransition.transition(
                                    instruction, StackTransition.Edge.FALLTHROUGH),
                            opCode + " accepted fallthrough");
                    break;
                default:
                    StackTransition.transition(
                            instruction, StackTransition.Edge.FALLTHROUGH);
                    break;
            }
            if (opCode.mayRaise()) {
                StackTransition.transition(
                        instruction, StackTransition.Edge.EXCEPTIONAL);
            }
        }
        Instruction nop = new Instruction(OpCode.NOP);
        expectIllegal(() -> StackTransition.transition(
                        nop, StackTransition.Edge.JUMP_TAKEN),
                "NOP accepted a jump edge");
    }

    private static void testMayRaise() {
        Set<OpCode> pure = EnumSet.of(
                OpCode.NOP, OpCode.POP_TOP, OpCode.COPY, OpCode.SWAP,
                OpCode.LOAD_CONST, OpCode.LOAD_CLOSURE,
                OpCode.STORE_NAME, OpCode.STORE_FAST,
                OpCode.STORE_GLOBAL, OpCode.STORE_DEREF,
                OpCode.LOAD_TEMP, OpCode.STORE_TEMP, OpCode.CLEAR_TEMP,
                OpCode.JUMP, OpCode.UNWIND_JUMP, OpCode.RETURN_VALUE,
                OpCode.BEGIN_EXCEPT, OpCode.END_EXCEPT,
                OpCode.ENTER_CLEANUP, OpCode.END_CLEANUP);
        for (OpCode opCode : OpCode.values()) {
            boolean expected = !pure.contains(opCode) && opCode != OpCode.RAISE;
            equal(expected, opCode.mayRaise(), opCode + " mayRaise");
        }
    }

    private static void testInventoryImmutability() {
        expectUnsupported(() -> OpCode.allOpcodes().clear(), "opcode inventory mutable");
        expectUnsupported(() -> StackTransition.handledOpcodes().clear(),
                "stack inventory mutable");
        expectUnsupported(() -> ControlFlowVerifier.handledOpcodes().clear(),
                "verifier inventory mutable");
        expectUnsupported(() -> BytecodeVM.dispatchedOpcodes().clear(),
                "VM inventory mutable");
    }

    private static Operand sampleOperand(OpCode opCode) {
        switch (opCode.getOperandKind()) {
            case NONE:
                return Operand.NoOperand.INSTANCE;
            case CONST:
                return new Operand.ConstOperand(0);
            case NAME:
                return new Operand.NameOperand(0);
            case LOCAL_SLOT:
                return new Operand.LocalSlotOperand(0);
            case DEREF_SLOT:
                return new Operand.DerefSlotOperand(0);
            case TEMP_SLOT:
                return new Operand.TempSlotOperand(0);
            case JUMP:
                return new Operand.JumpOperand(0);
            case COUNT:
                return new Operand.CountOperand(
                        opCode == OpCode.COPY || opCode == OpCode.SWAP ? 1 : 0);
            case UNARY_OPERATOR:
                return new Operand.UnaryOperatorOperand(
                        Operand.UnaryOperator.POSITIVE);
            case BINARY_OPERATOR:
                return new Operand.BinaryOperatorOperand(
                        Operand.BinaryOperator.ADD);
            case COMPARE_OPERATOR:
                return new Operand.CompareOperatorOperand(
                        Operand.CompareOperator.EQ);
            case CALL_SPEC:
                return new Operand.CallSpec(0, Collections.<String>emptyList());
            case MAKE_FUNCTION_SPEC:
                return new Operand.MakeFunctionSpec(
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList());
            case BUILD_CLASS_SPEC:
                return new Operand.BuildClassSpec(
                        "Sample", 0, Collections.<String>emptyList());
            case IMPORT_SPEC:
                return new Operand.ImportSpec("sample");
            case CLEANUP_SPEC:
                return new Operand.CleanupSpec(0);
            case HANDLER_SPEC:
                return new Operand.HandlerSpec(0, 0);
            case WITH_SPEC:
                return new Operand.WithSpec(0, 0);
            default:
                throw new AssertionError("Unhandled operand kind "
                        + opCode.getOperandKind());
        }
    }

    private static void run(String name, CheckedRunnable test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable failure) {
            failed++;
            System.err.println("FAIL  " + name + " -> " + failure.getMessage());
            failure.printStackTrace(System.err);
        }
    }

    private static void expectIllegal(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void expectUnsupported(Runnable action, String message) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
