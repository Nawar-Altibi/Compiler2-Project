import compilers.diagnostics.CompilerPhase;
import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticCategory;
import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.SymbolTable.SymbolTableBuilder;
import compilers.flask.antlr_gen.FlaskLexer;
import compilers.flask.antlr_gen.FlaskParser;
import compilers.flask.ast.builder.ASTBuilder;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.Statement;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.expressions.atoms.ListNode;
import compilers.flask.ast.nodes.expressions.atoms.LiteralNode;
import compilers.flask.ast.nodes.expressions.atoms.TupleNode;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.ast.nodes.statements.simple.AssignmentNode;
import compilers.flask.ast.nodes.statements.simple.DelNode;
import compilers.flask.codegen.analysis.BindingAnalysisResult;
import compilers.flask.codegen.analysis.BindingResolver;
import compilers.flask.codegen.bytecode.CleanupRegion;
import compilers.flask.codegen.bytecode.BytecodeModule;
import compilers.flask.codegen.bytecode.CodeObject;
import compilers.flask.codegen.bytecode.CodeObjectBuilder;
import compilers.flask.codegen.bytecode.Instruction;
import compilers.flask.codegen.bytecode.OpCode;
import compilers.flask.codegen.bytecode.Operand;
import compilers.flask.codegen.generator.BytecodeGenerator;
import compilers.flask.codegen.verify.BytecodeAssembler;
import compilers.flask.codegen.verify.ControlFlowVerifier;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyString;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Dependency-free instruction-shape tests for Phase-2/3 Flask lowering. */
public final class FlaskBytecodeGeneratorHarness {
    private static int passed;
    private static int failed;

    private FlaskBytecodeGeneratorHarness() {
    }

    public static void main(String[] args) {
        run("straight-line literals, collections, and operations",
                FlaskBytecodeGeneratorHarness::testStraightLineShapes);
        run("short-circuit and conditional shapes",
                FlaskBytecodeGeneratorHarness::testLogicalAndConditionalShapes);
        run("augmented attribute evaluates receiver once",
                FlaskBytecodeGeneratorHarness::testAugmentedAttributeShape);
        run("augmented subscript evaluates receiver and key once",
                FlaskBytecodeGeneratorHarness::testAugmentedSubscriptShape);
        run("chained comparison preserves middle operand",
                FlaskBytecodeGeneratorHarness::testChainedComparisonShape);
        run("for/while else and loop transfer shapes",
                FlaskBytecodeGeneratorHarness::testLoopShapes);
        run("recursive unpack and delete shapes",
                FlaskBytecodeGeneratorHarness::testRecursiveTargetShapes);
        run("f-string parts and format shape",
                FlaskBytecodeGeneratorHarness::testFStringShape);
        run("assert lowering is lazy and raises explicitly",
                FlaskBytecodeGeneratorHarness::testAssertShape);
        run("executable runtime symbol generates normally",
                FlaskBytecodeGeneratorHarness::testExecutableRuntimeSymbol);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " bytecode-generator test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " Flask bytecode-generator tests passed.");
    }

    private static void testStraightLineShapes() {
        Generated generated = generateSuccess(lines(
                "numbers = [1, 2]",
                "pair = (3, 4)",
                "unique = {5, 6}",
                "mapping = {'key': 7}",
                "total = -1 + 2"), "straight-line.py");
        CodeObject code = generated.code;

        assertCountOperand(code, OpCode.BUILD_LIST, 2, 1);
        assertCountOperand(code, OpCode.BUILD_TUPLE, 2, 1);
        assertCountOperand(code, OpCode.BUILD_SET, 2, 1);
        assertCountOperand(code, OpCode.BUILD_MAP, 1, 1);
        check(hasUnary(code, Operand.UnaryOperator.NEGATIVE),
                "missing NEGATIVE unary operation");
        check(hasBinary(code, Operand.BinaryOperator.ADD),
                "missing ADD binary operation");
        assertNameOperations(code, OpCode.STORE_NAME,
                Arrays.asList("numbers", "pair", "unique", "mapping", "total"));

        List<Instruction> instructions = code.getInstructions();
        equal(OpCode.LOAD_CONST,
                instructions.get(instructions.size() - 2).getOpCode(),
                "implicit None load");
        Operand.ConstOperand noneOperand = (Operand.ConstOperand)
                instructions.get(instructions.size() - 2).getOperand();
        check(code.getConstantPool().get(noneOperand.getIndex()) == PyNone.INSTANCE,
                "implicit return must load PyNone.INSTANCE");
        equal(OpCode.RETURN_VALUE,
                instructions.get(instructions.size() - 1).getOpCode(),
                "explicit module return");
        check(code.getSourceMap().get(instructions.size() - 2).isSynthetic(),
                "implicit None load must be synthetic");
        check(code.getSourceMap().get(instructions.size() - 1).isSynthetic(),
                "implicit return must be synthetic");
    }

    private static void testLogicalAndConditionalShapes() {
        CodeObject code = generateSuccess(lines(
                "left = 1",
                "right = 2",
                "both = left and right",
                "either = left or right",
                "if both:",
                "    selected = 1",
                "elif either:",
                "    selected = 2",
                "else:",
                "    selected = 3"), "logical.py").code;

        equal(1, count(code, OpCode.JUMP_IF_FALSE_OR_POP),
                "and short-circuit jump count");
        equal(1, count(code, OpCode.JUMP_IF_TRUE_OR_POP),
                "or short-circuit jump count");
        equal(2, count(code, OpCode.POP_JUMP_IF_FALSE),
                "if/elif conditional jump count");
        check(count(code, OpCode.JUMP) >= 2,
                "if/elif branches must jump to their shared end");

        int andJump = indexOf(code, OpCode.JUMP_IF_FALSE_OR_POP, 0);
        int orJump = indexOf(code, OpCode.JUMP_IF_TRUE_OR_POP, 0);
        check(jumpTarget(code, andJump) > andJump,
                "and jump must target the join after its RHS");
        check(jumpTarget(code, orJump) > orJump,
                "or jump must target the join after its RHS");
    }

    private static void testAugmentedAttributeShape() {
        CodeObject code = generateSuccess(lines(
                "receiver = 0",
                "receiver.value += 2"), "augmented-attribute.py").code;
        int store = indexOf(code, OpCode.STORE_ATTR, 0);
        int start = store - 6;
        check(start >= 0, "attribute augmented sequence is truncated");

        assertNameInstruction(code, start, OpCode.LOAD_NAME, "receiver");
        assertCountInstruction(code, start + 1, OpCode.COPY, 1);
        assertNameInstruction(code, start + 2, OpCode.LOAD_ATTR, "value");
        equal(OpCode.LOAD_CONST, op(code, start + 3), "attribute RHS load");
        assertBinaryInstruction(
                code, start + 4, Operand.BinaryOperator.INPLACE_ADD);
        assertCountInstruction(code, start + 5, OpCode.SWAP, 2);
        assertNameInstruction(code, start + 6, OpCode.STORE_ATTR, "value");
        equal(1, countNameBetween(
                        code, OpCode.LOAD_NAME, "receiver", start, store + 1),
                "augmented attribute receiver evaluation count");
    }

    private static void testAugmentedSubscriptShape() {
        CodeObject code = generateSuccess(lines(
                "receiver = [1]",
                "receiver[0] += 2"), "augmented-subscript.py").code;
        int store = indexOf(code, OpCode.STORE_SUBSCR, 0);
        int start = store - 9;
        check(start >= 0, "subscript augmented sequence is truncated");

        assertNameInstruction(code, start, OpCode.LOAD_NAME, "receiver");
        equal(OpCode.LOAD_CONST, op(code, start + 1), "subscript key load");
        assertCountInstruction(code, start + 2, OpCode.COPY, 2);
        assertCountInstruction(code, start + 3, OpCode.COPY, 2);
        equal(OpCode.BINARY_SUBSCR, op(code, start + 4), "old item load");
        equal(OpCode.LOAD_CONST, op(code, start + 5), "subscript RHS load");
        assertBinaryInstruction(
                code, start + 6, Operand.BinaryOperator.INPLACE_ADD);
        assertCountInstruction(code, start + 7, OpCode.SWAP, 3);
        assertCountInstruction(code, start + 8, OpCode.SWAP, 2);
        equal(OpCode.STORE_SUBSCR, op(code, start + 9), "subscript store");
        equal(1, countNameBetween(
                        code, OpCode.LOAD_NAME, "receiver", start, store + 1),
                "augmented subscript receiver evaluation count");
        equal(2, count(code, OpCode.COPY),
                "augmented subscript must duplicate only the object/key pair");
    }

    private static void testChainedComparisonShape() {
        CodeObject code = generateSuccess(lines(
                "a = 1",
                "b = 2",
                "c = 3",
                "result = a < b < c"), "chained.py").code;
        int store = indexOfName(code, OpCode.STORE_NAME, "result", 0);
        int start = store - 11;
        check(start >= 0, "chained-comparison sequence is truncated");

        assertNameInstruction(code, start, OpCode.LOAD_NAME, "a");
        assertNameInstruction(code, start + 1, OpCode.LOAD_NAME, "b");
        assertCountInstruction(code, start + 2, OpCode.SWAP, 2);
        assertCountInstruction(code, start + 3, OpCode.COPY, 2);
        assertCompareInstruction(code, start + 4, Operand.CompareOperator.LT);
        equal(OpCode.JUMP_IF_FALSE_OR_POP, op(code, start + 5),
                "chained false-edge instruction");
        assertNameInstruction(code, start + 6, OpCode.LOAD_NAME, "c");
        assertCompareInstruction(code, start + 7, Operand.CompareOperator.LT);
        equal(OpCode.JUMP, op(code, start + 8), "true-path cleanup bypass");
        assertCountInstruction(code, start + 9, OpCode.SWAP, 2);
        equal(OpCode.POP_TOP, op(code, start + 10), "false-path middle cleanup");
        assertNameInstruction(code, start + 11, OpCode.STORE_NAME, "result");
        equal(start + 9, jumpTarget(code, start + 5),
                "false edge must enter middle-operand cleanup");
        equal(store, jumpTarget(code, start + 8),
                "true edge must skip middle-operand cleanup");
        equal(1, countNameBetween(code, OpCode.LOAD_NAME, "b", start, store),
                "middle comparator evaluation count");
    }

    private static void testLoopShapes() {
        CodeObject code = generateSuccess(lines(
                "items = [1, 2]",
                "for item in items:",
                "    if item == 1:",
                "        continue",
                "    break",
                "else:",
                "    exhausted = 1",
                "flag = True",
                "while flag:",
                "    break",
                "else:",
                "    exhausted = 2"), "loops.py").code;

        equal(1, count(code, OpCode.GET_ITER), "GET_ITER count");
        equal(1, count(code, OpCode.STORE_TEMP), "iterator STORE_TEMP count");
        equal(1, count(code, OpCode.LOAD_TEMP), "iterator LOAD_TEMP shape count");
        equal(1, count(code, OpCode.FOR_ITER), "FOR_ITER count");
        equal(1, count(code, OpCode.CLEAR_TEMP), "iterator CLEAR_TEMP count");
        equal(3, count(code, OpCode.UNWIND_JUMP),
                "universal break/continue unwind count");
        equal(1, code.getTemporarySlotCount(), "iterator temporary-slot count");
        equal(1, code.getCleanupRegions().size(), "iterator cleanup-region count");

        CleanupRegion cleanup = code.getCleanupRegions().get(0);
        equal(CleanupRegion.Kind.TEMP_CLEAR, cleanup.getKind(),
                "iterator cleanup kind");
        equal(Collections.singletonList(0), cleanup.getResourceSlots(),
                "iterator cleanup slot");
        int loadTemp = indexOf(code, OpCode.LOAD_TEMP, 0);
        int forIter = indexOf(code, OpCode.FOR_ITER, 0);
        int clearTemp = indexOf(code, OpCode.CLEAR_TEMP, 0);
        equal(clearTemp, jumpTarget(code, forIter),
                "natural exhaustion target");
        equal(loadTemp, cleanup.getStartOffset(), "cleanup lifetime start");
        equal(clearTemp, cleanup.getEndOffset(), "cleanup lifetime end");
        check(hasJumpTo(code, OpCode.JUMP, loadTemp),
                "continue/back-edge must target the for-loop top");
        boolean breakSkipsElse = false;
        for (Instruction instruction : code.getInstructions()) {
            if (instruction.getOpCode() == OpCode.UNWIND_JUMP
                    && ((Operand.JumpOperand) instruction.getOperand())
                            .getTargetOffset() > clearTemp) {
                breakSkipsElse = true;
            }
        }
        check(breakSkipsElse,
                "for break must skip loop else through unwinding");
        check(count(code, OpCode.POP_JUMP_IF_FALSE) >= 2,
                "for-body if and while condition branches are missing");
        check(hasBackwardJump(code), "loop back edge is missing");
    }

    private static void testRecursiveTargetShapes() {
        IdentifierNode aStore = new IdentifierNode("a");
        IdentifierNode bStore = new IdentifierNode("b");
        IdentifierNode cStore = new IdentifierNode("c");
        TupleNode target = new TupleNode(Arrays.<Expression>asList(
                aStore,
                new ListNode(Arrays.<Expression>asList(bStore, cStore))));
        ListNode value = new ListNode(Arrays.<Expression>asList(
                LiteralNode.integer(1),
                new ListNode(Arrays.<Expression>asList(
                        LiteralNode.integer(2), LiteralNode.integer(3)))));
        AssignmentNode assignment = new AssignmentNode(target, "=", value);

        TupleNode deleteTarget = new TupleNode(Arrays.<Expression>asList(
                new IdentifierNode("a"),
                new ListNode(Arrays.<Expression>asList(
                        new IdentifierNode("b"), new IdentifierNode("c")))));
        DelNode delete = new DelNode(deleteTarget);
        ProgramNode program = new ProgramNode(
                Arrays.<Statement>asList(assignment, delete));

        CodeObject code = generateSuccess(program, "recursive-targets.py").code;
        List<Integer> unpackCounts = countOperands(code, OpCode.UNPACK_SEQUENCE);
        equal(Arrays.asList(2, 2), unpackCounts, "recursive unpack counts");
        assertNameOperations(code, OpCode.STORE_NAME, Arrays.asList("a", "b", "c"));
        assertNameOperations(code, OpCode.DELETE_NAME, Arrays.asList("a", "b", "c"));

        int outerUnpack = indexOf(code, OpCode.UNPACK_SEQUENCE, 0);
        int storeA = indexOfName(code, OpCode.STORE_NAME, "a", outerUnpack);
        int innerUnpack = indexOf(code, OpCode.UNPACK_SEQUENCE, outerUnpack + 1);
        int storeB = indexOfName(code, OpCode.STORE_NAME, "b", innerUnpack);
        int storeC = indexOfName(code, OpCode.STORE_NAME, "c", storeB + 1);
        check(outerUnpack < storeA && storeA < innerUnpack
                        && innerUnpack < storeB && storeB < storeC,
                "recursive targets were not stored left-to-right");
    }

    private static void testFStringShape() {
        CodeObject code = generateSuccess(lines(
                "name = 'Ada'",
                "message = f\"Hello {name}!\""), "f-string.py").code;
        equal(1, count(code, OpCode.FORMAT_VALUE), "FORMAT_VALUE count");
        assertCountOperand(code, OpCode.BUILD_STRING, 3, 1);
        assertNameOperations(code, OpCode.LOAD_NAME, Collections.singletonList("name"));
        assertNameOperations(code, OpCode.STORE_NAME, Arrays.asList("name", "message"));

        List<String> strings = new ArrayList<>();
        for (Object constant : code.getConstantPool().getEntries()) {
            if (constant instanceof PyString) {
                strings.add(((PyString) constant).getValue());
            }
        }
        check(strings.contains("Hello "), "missing leading f-string constant");
        check(strings.contains("!"), "missing trailing f-string constant");
    }

    private static void testAssertShape() {
        CodeObject code = generateSuccess(lines(
                "assert True, 'message'"), "assert.py").code;
        equal(1, count(code, OpCode.POP_JUMP_IF_TRUE),
                "assert success branch");
        equal(1, count(code, OpCode.CALL),
                "AssertionError construction");
        equal(1, count(code, OpCode.RAISE), "assert raise count");
        int raise = indexOf(code, OpCode.RAISE, 0);
        equal(1, ((Operand.CountOperand) code.getInstructions().get(raise)
                .getOperand()).getCount(), "assert RAISE arity");
    }

    private static void testExecutableRuntimeSymbol() {
        Generated generated = generateSuccess(
                "captured = len\n", "planned-runtime.py");
        check(generated.builder != null && generated.code != null,
                "executable runtime dependency did not generate");
        assertNameOperations(generated.code, OpCode.LOAD_NAME,
                Collections.singletonList("len"));
    }

    private static Generated generateSuccess(String source, String sourceName) {
        return requireSuccess(generate(source, sourceName));
    }

    private static Generated generateSuccess(ProgramNode program, String sourceName) {
        return requireSuccess(generate(program, sourceName));
    }

    private static Generated requireSuccess(Generated generated) {
        if (generated.builder == null || generated.code == null
                || generated.reporter.hasErrors()) {
            throw new AssertionError(
                    "generation failed: " + generated.reporter.diagnostics());
        }
        return generated;
    }

    private static Generated generate(String source, String sourceName) {
        return generate(build(source, sourceName), sourceName);
    }

    private static Generated generate(ProgramNode program, String sourceName) {
        DiagnosticReporter reporter = new DiagnosticReporter();
        SymbolTableBuilder symbols = new SymbolTableBuilder(reporter, sourceName);
        program.accept(symbols);
        if (reporter.hasErrors()) {
            throw new AssertionError("symbol-table errors: " + reporter.diagnostics());
        }

        BindingAnalysisResult bindings = new BindingResolver().resolve(program);
        CodeObjectBuilder builder = new BytecodeGenerator(
                program, bindings, reporter, sourceName, "generator_test").generate();
        CodeObject code = null;
        if (builder != null && !reporter.hasErrors()) {
            code = new BytecodeAssembler().assemble(builder, reporter);
        }
        if (code != null && !reporter.hasErrors()) {
            BytecodeModule module = new BytecodeModule(
                    sourceName, "generator_test", code);
            compilers.flask.codegen.bytecode.VerifiedBytecodeModule verified =
                    new ControlFlowVerifier().verify(module, reporter);
            code = verified == null
                    ? null
                    : verified.getModule().getRootCode();
        }
        return new Generated(program, bindings, builder, code, reporter);
    }

    private static ProgramNode build(String source, String sourceName) {
        List<String> syntaxIssues = new ArrayList<>();
        FlaskLexer lexer = new FlaskLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new CollectingErrorListener("lexer", syntaxIssues));

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        FlaskParser parser = new FlaskParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new CollectingErrorListener("parser", syntaxIssues));
        FlaskParser.ProgramContext tree = parser.program();
        check(syntaxIssues.isEmpty(),
                "syntax errors: " + syntaxIssues + "\n" + source);

        ASTNode root = new ASTBuilder(sourceName).visit(tree);
        check(root instanceof ProgramNode, "AST root is not ProgramNode");
        return (ProgramNode) root;
    }

    private static void assertOnlyDiagnostic(
            DiagnosticReporter reporter,
            DiagnosticCategory category,
            CompilerPhase phase,
            String sourceFile) {
        equal(1, reporter.diagnostics().size(), "diagnostic count");
        Diagnostic diagnostic = reporter.diagnostics().get(0);
        equal(category, diagnostic.category(), "diagnostic category");
        equal(phase, diagnostic.phase(), "diagnostic phase");
        equal(sourceFile, diagnostic.sourceFile(), "diagnostic source");
        check(diagnostic.isError(), "code-generation diagnostic must be an error");
    }

    private static void assertCountOperand(
            CodeObject code, OpCode opCode, int operand, int expectedOccurrences) {
        int found = 0;
        for (Instruction instruction : code.getInstructions()) {
            if (instruction.getOpCode() == opCode
                    && instruction.getOperand() instanceof Operand.CountOperand
                    && ((Operand.CountOperand) instruction.getOperand()).getCount() == operand) {
                found++;
            }
        }
        equal(expectedOccurrences, found,
                opCode + " " + operand + " occurrence count");
    }

    private static List<Integer> countOperands(CodeObject code, OpCode opCode) {
        List<Integer> counts = new ArrayList<>();
        for (Instruction instruction : code.getInstructions()) {
            if (instruction.getOpCode() == opCode) {
                counts.add(((Operand.CountOperand) instruction.getOperand()).getCount());
            }
        }
        return counts;
    }

    private static boolean hasUnary(
            CodeObject code, Operand.UnaryOperator expected) {
        for (Instruction instruction : code.getInstructions()) {
            if (instruction.getOpCode() == OpCode.UNARY_OP
                    && ((Operand.UnaryOperatorOperand) instruction.getOperand())
                            .getOperator() == expected) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBinary(
            CodeObject code, Operand.BinaryOperator expected) {
        for (Instruction instruction : code.getInstructions()) {
            if (instruction.getOpCode() == OpCode.BINARY_OP
                    && ((Operand.BinaryOperatorOperand) instruction.getOperand())
                            .getOperator() == expected) {
                return true;
            }
        }
        return false;
    }

    private static void assertBinaryInstruction(
            CodeObject code, int offset, Operand.BinaryOperator expected) {
        equal(OpCode.BINARY_OP, op(code, offset), "binary opcode at " + offset);
        equal(expected,
                ((Operand.BinaryOperatorOperand)
                        code.getInstructions().get(offset).getOperand()).getOperator(),
                "binary operator at " + offset);
    }

    private static void assertCompareInstruction(
            CodeObject code, int offset, Operand.CompareOperator expected) {
        equal(OpCode.COMPARE_OP, op(code, offset), "compare opcode at " + offset);
        equal(expected,
                ((Operand.CompareOperatorOperand)
                        code.getInstructions().get(offset).getOperand()).getOperator(),
                "compare operator at " + offset);
    }

    private static void assertCountInstruction(
            CodeObject code, int offset, OpCode opCode, int expected) {
        equal(opCode, op(code, offset), "count opcode at " + offset);
        equal(expected,
                ((Operand.CountOperand)
                        code.getInstructions().get(offset).getOperand()).getCount(),
                opCode + " operand at " + offset);
    }

    private static void assertNameInstruction(
            CodeObject code, int offset, OpCode opCode, String expectedName) {
        equal(opCode, op(code, offset), "name opcode at " + offset);
        Operand.NameOperand operand = (Operand.NameOperand)
                code.getInstructions().get(offset).getOperand();
        equal(expectedName, code.getNamePool().get(operand.getIndex()),
                opCode + " name at " + offset);
    }

    private static void assertNameOperations(
            CodeObject code, OpCode opCode, List<String> expectedNames) {
        List<String> actual = new ArrayList<>();
        for (Instruction instruction : code.getInstructions()) {
            if (instruction.getOpCode() == opCode) {
                Operand.NameOperand operand =
                        (Operand.NameOperand) instruction.getOperand();
                actual.add(code.getNamePool().get(operand.getIndex()));
            }
        }
        equal(expectedNames, actual, opCode + " name order");
    }

    private static int countNameBetween(
            CodeObject code,
            OpCode opCode,
            String name,
            int startInclusive,
            int endExclusive) {
        int found = 0;
        for (int i = startInclusive; i < endExclusive; i++) {
            Instruction instruction = code.getInstructions().get(i);
            if (instruction.getOpCode() == opCode) {
                Operand.NameOperand operand =
                        (Operand.NameOperand) instruction.getOperand();
                if (name.equals(code.getNamePool().get(operand.getIndex()))) {
                    found++;
                }
            }
        }
        return found;
    }

    private static int count(CodeObject code, OpCode opCode) {
        int found = 0;
        for (Instruction instruction : code.getInstructions()) {
            if (instruction.getOpCode() == opCode) {
                found++;
            }
        }
        return found;
    }

    private static int indexOf(CodeObject code, OpCode opCode, int from) {
        for (int i = Math.max(0, from); i < code.getInstructions().size(); i++) {
            if (op(code, i) == opCode) {
                return i;
            }
        }
        throw new AssertionError("missing opcode " + opCode + " after " + from);
    }

    private static int indexOfName(
            CodeObject code, OpCode opCode, String name, int from) {
        for (int i = Math.max(0, from); i < code.getInstructions().size(); i++) {
            Instruction instruction = code.getInstructions().get(i);
            if (instruction.getOpCode() != opCode) {
                continue;
            }
            Operand.NameOperand operand = (Operand.NameOperand) instruction.getOperand();
            if (name.equals(code.getNamePool().get(operand.getIndex()))) {
                return i;
            }
        }
        throw new AssertionError("missing " + opCode + " for name " + name);
    }

    private static OpCode op(CodeObject code, int offset) {
        return code.getInstructions().get(offset).getOpCode();
    }

    private static int jumpTarget(CodeObject code, int offset) {
        return ((Operand.JumpOperand)
                code.getInstructions().get(offset).getOperand()).getTargetOffset();
    }

    private static boolean hasJumpTo(
            CodeObject code, OpCode opCode, int target) {
        for (Instruction instruction : code.getInstructions()) {
            if (instruction.getOpCode() == opCode
                    && instruction.getOperand() instanceof Operand.JumpOperand
                    && ((Operand.JumpOperand) instruction.getOperand())
                            .getTargetOffset() == target) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBackwardJump(CodeObject code) {
        for (int i = 0; i < code.getInstructions().size(); i++) {
            Instruction instruction = code.getInstructions().get(i);
            if (instruction.getOperand() instanceof Operand.JumpOperand
                    && ((Operand.JumpOperand) instruction.getOperand())
                            .getTargetOffset() < i) {
                return true;
            }
        }
        return false;
    }

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static void run(String name, TestCase test) {
        try {
            test.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable failure) {
            failed++;
            System.err.println("[FAIL] " + name + ": " + failure);
            failure.printStackTrace(System.err);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                    label + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static final class Generated {
        private final ProgramNode program;
        private final BindingAnalysisResult bindings;
        private final CodeObjectBuilder builder;
        private final CodeObject code;
        private final DiagnosticReporter reporter;

        private Generated(
                ProgramNode program,
                BindingAnalysisResult bindings,
                CodeObjectBuilder builder,
                CodeObject code,
                DiagnosticReporter reporter) {
            this.program = program;
            this.bindings = bindings;
            this.builder = builder;
            this.code = code;
            this.reporter = reporter;
        }
    }

    private static final class CollectingErrorListener extends BaseErrorListener {
        private final String stage;
        private final List<String> errors;

        private CollectingErrorListener(String stage, List<String> errors) {
            this.stage = stage;
            this.errors = errors;
        }

        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String message,
                RecognitionException exception) {
            errors.add(stage + " " + line + ":" + charPositionInLine + " " + message);
        }
    }

    @FunctionalInterface
    private interface TestCase {
        void run() throws Exception;
    }
}
