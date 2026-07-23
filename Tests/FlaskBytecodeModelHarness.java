import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticCategory;
import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.codegen.bytecode.BytecodeFormat;
import compilers.flask.codegen.bytecode.BytecodeModule;
import compilers.flask.codegen.bytecode.CodeKind;
import compilers.flask.codegen.bytecode.CodeObject;
import compilers.flask.codegen.bytecode.CodeObjectBuilder;
import compilers.flask.codegen.bytecode.ConstantPool;
import compilers.flask.codegen.bytecode.FunctionSignature;
import compilers.flask.codegen.bytecode.Instruction;
import compilers.flask.codegen.bytecode.Label;
import compilers.flask.codegen.bytecode.OpCode;
import compilers.flask.codegen.bytecode.Operand;
import compilers.flask.codegen.disasm.Disassembler;
import compilers.flask.codegen.verify.BytecodeAssembler;
import compilers.flask.vm.values.PyBool;
import compilers.flask.vm.values.PyInt;
import compilers.flask.vm.values.PyList;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyTuple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Dependency-free executable tests for the immutable Flask bytecode model. */
public final class FlaskBytecodeModelHarness {
    private static int passed;
    private static int failed;

    private FlaskBytecodeModelHarness() {
    }

    public static void main(String[] args) {
        if (args.length == 1
                && "--emit-deterministic-disassembly".equals(args[0])) {
            System.out.print(deterministicRawDisassembly());
            return;
        }
        run("exact format identity", FlaskBytecodeModelHarness::testFormatIdentity);
        run("type-aware immutable constant pool",
                FlaskBytecodeModelHarness::testConstantPoolContracts);
        run("code constants use identity",
                FlaskBytecodeModelHarness::testCodeConstantIdentity);
        run("typed operand schemas", FlaskBytecodeModelHarness::testTypedOperands);
        run("label ownership and placement failures",
                FlaskBytecodeModelHarness::testLabelOwnershipAndPlacement);
        run("unresolved label assembly diagnostic",
                FlaskBytecodeModelHarness::testUnresolvedLabelDiagnostic);
        run("dense source map and synthetic locations",
                FlaskBytecodeModelHarness::testSourceMap);
        run("finalized model is defensively immutable",
                FlaskBytecodeModelHarness::testFinalizedImmutability);
        run("raw disassembly is deterministic",
                FlaskBytecodeModelHarness::testDeterministicRawDisassembly);
        run("disassembly is deterministic across fresh JVM processes",
                FlaskBytecodeModelHarness::testFreshProcessDisassembly);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " bytecode-model test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " Flask bytecode-model tests passed.");
    }

    private static void testFormatIdentity() {
        equal(0x46424300, BytecodeFormat.MAGIC, "format magic");
        equal(1, BytecodeFormat.CURRENT_VERSION, "current version");
        check(BytecodeFormat.isSupported(0x46424300, 1),
                "exact format must be supported");
        check(!BytecodeFormat.isSupported(0x46424301, 1),
                "wrong magic was accepted");
        check(!BytecodeFormat.isSupported(0x46424300, 0),
                "older version was accepted without a migration policy");
        check(!BytecodeFormat.isSupported(0x46424300, 2),
                "future version was accepted");
        expectThrows(IllegalArgumentException.class,
                () -> BytecodeFormat.requireSupported(0x46424301, 1),
                "wrong magic rejection");
        expectThrows(IllegalArgumentException.class,
                () -> BytecodeFormat.requireSupported(0x46424300, 2),
                "wrong version rejection");
    }

    private static void testConstantPoolContracts() {
        ConstantPool.Builder builder = new ConstantPool.Builder();
        int intOne = builder.add(PyInt.valueOf(1));
        int sameIntOne = builder.add(PyInt.valueOf(1));
        int trueValue = builder.add(PyBool.TRUE);
        int firstString = builder.add(new PyString("same"));
        int sameString = builder.add(new PyString("same"));
        int tuple = builder.add(new PyTuple(Arrays.asList(
                PyInt.valueOf(1), new PyString("immutable"))));

        equal(intOne, sameIntOne, "equal integer constants must deduplicate");
        equal(firstString, sameString, "equal string constants must deduplicate");
        check(trueValue != intOne, "True must not merge with integer 1");
        check(tuple > trueValue, "deeply immutable tuple was not inserted");
        expectThrows(IllegalArgumentException.class,
                () -> builder.add(new PyList()),
                "mutable list constant rejection");

        ConstantPool pool = builder.build();
        equal(4, pool.size(), "deduplicated pool size");
        check(pool.get(intOne) instanceof PyInt, "integer constant type");
        check(pool.get(trueValue) instanceof PyBool, "boolean constant type");
        expectThrows(UnsupportedOperationException.class,
                () -> pool.getEntries().add(PyNone.INSTANCE),
                "constant entries must be immutable");
        expectThrows(IllegalArgumentException.class,
                () -> new ConstantPool(Collections.singletonList(new PyList())),
                "constructor must reject mutable constants too");
        expectThrows(NullPointerException.class,
                () -> new ConstantPool(Collections.singletonList(null)),
                "Java null constant rejection");
    }

    private static void testCodeConstantIdentity() {
        CodeObject first = minimalCode(17, "nested", "nested.py");
        CodeObject second = minimalCode(17, "nested", "nested.py");
        ConstantPool.Builder constants = new ConstantPool.Builder();

        int firstIndex = constants.add(first);
        int sameIdentityIndex = constants.add(first);
        int secondIndex = constants.add(second);

        equal(firstIndex, sameIdentityIndex, "same code identity must deduplicate");
        check(secondIndex != firstIndex,
                "structurally similar code objects must retain distinct identity");
        equal(Arrays.asList(first, second), constants.build().getNestedCodeObjects(),
                "lexical code-constant order");
    }

    private static void testTypedOperands() {
        Instruction load = new Instruction(
                OpCode.LOAD_CONST, new Operand.ConstOperand(0));
        equal(Operand.OperandKind.CONST, load.getOperand().getKind(),
                "LOAD_CONST operand kind");
        new Instruction(OpCode.NOP);
        new Instruction(OpCode.COPY, new Operand.CountOperand(1));
        new Instruction(OpCode.RAISE, new Operand.CountOperand(2));

        expectThrows(IllegalArgumentException.class,
                () -> new Instruction(OpCode.LOAD_CONST, new Operand.NameOperand(0)),
                "opcode/operand mismatch");
        expectThrows(IllegalArgumentException.class,
                () -> new Instruction(OpCode.NOP, new Operand.CountOperand(0)),
                "unexpected operand");
        expectThrows(IllegalArgumentException.class,
                () -> new Instruction(OpCode.COPY, new Operand.CountOperand(0)),
                "COPY zero depth");
        expectThrows(IllegalArgumentException.class,
                () -> new Instruction(OpCode.SWAP, new Operand.CountOperand(0)),
                "SWAP zero depth");
        expectThrows(IllegalArgumentException.class,
                () -> new Instruction(OpCode.RAISE, new Operand.CountOperand(3)),
                "RAISE count above two");
        expectThrows(IllegalArgumentException.class,
                () -> new Operand.ConstOperand(-1),
                "negative constant index");

        List<String> keywords = new ArrayList<>(Arrays.asList("left", "right"));
        Operand.CallSpec call = new Operand.CallSpec(1, keywords);
        keywords.clear();
        equal(Arrays.asList("left", "right"), call.getOrderedKeywordNames(),
                "CallSpec defensive copy");
        expectThrows(UnsupportedOperationException.class,
                () -> call.getOrderedKeywordNames().add("third"),
                "CallSpec names must be immutable");
        equal("defaults=[x] annotations=[x, return] free=[captured]",
                new Operand.MakeFunctionSpec(
                        Collections.singletonList("x"),
                        Arrays.asList("x", "return"),
                        Collections.singletonList("captured")).toString(),
                "MakeFunctionSpec deterministic rendering");
        equal("name=Sample bases=2 free=[captured]",
                new Operand.BuildClassSpec(
                        "Sample", 2,
                        Collections.singletonList("captured")).toString(),
                "BuildClassSpec deterministic rendering");
        equal("cleanup=3",
                new Operand.CleanupSpec(3).toString(),
                "CleanupSpec deterministic rendering");
        equal("handler=4 cleanup=3 alias=<none>",
                new Operand.HandlerSpec(4, 3).toString(),
                "HandlerSpec deterministic rendering");
        equal("cleanup=3 resource=t2",
                new Operand.WithSpec(3, 2).toString(),
                "WithSpec deterministic rendering");
        equal(OpCode.values().length, OpCode.allOpcodes().size(),
                "opcode metadata coverage inventory");
        for (OpCode opCode : OpCode.values()) {
            check(opCode.getOperandKind() != null,
                    "missing operand schema for " + opCode);
        }
    }

    private static void testLabelOwnershipAndPlacement() {
        CodeObjectBuilder owner = newBuilder(0, "owner", "labels.py");
        CodeObjectBuilder other = newBuilder(1, "other", "labels.py");
        Label ownerLabel = owner.newLabel("target");

        expectThrows(IllegalArgumentException.class,
                () -> other.emitJump(
                        OpCode.JUMP, ownerLabel, SourceSpan.UNKNOWN, true),
                "cross-code jump");

        owner.mark(ownerLabel);
        expectThrows(IllegalArgumentException.class,
                () -> owner.mark(ownerLabel),
                "duplicate label placement");
        owner.emit(OpCode.NOP, SourceSpan.UNKNOWN, true);
        owner.seal();
        expectThrows(IllegalStateException.class,
                () -> owner.emit(OpCode.NOP, SourceSpan.UNKNOWN, true),
                "sealed builder mutation");
    }

    private static void testUnresolvedLabelDiagnostic() {
        CodeObjectBuilder builder = newBuilder(0, "unresolved", "labels.py");
        Label missing = builder.newLabel("missing");
        builder.emitJump(OpCode.JUMP, missing, SourceSpan.UNKNOWN, true);
        builder.seal();

        DiagnosticReporter reporter = new DiagnosticReporter();
        CodeObject assembled = new BytecodeAssembler().assemble(builder, reporter);
        check(assembled == null, "unresolved label produced code");
        equal(1, reporter.diagnostics().size(), "unresolved-label diagnostic count");
        Diagnostic diagnostic = reporter.diagnostics().get(0);
        equal(DiagnosticCategory.UNRESOLVED_LABEL, diagnostic.category(),
                "unresolved-label category");
        check(diagnostic.message().contains("missing"),
                "unresolved-label diagnostic must identify its label");
    }

    private static void testSourceMap() {
        SourceSpan real = new SourceSpan("source-map.py", 3, 4, 3, 7);
        CodeObjectBuilder builder = newBuilder(0, "source_map", "source-map.py");
        int none = builder.addConstant(PyNone.INSTANCE);
        builder.emit(OpCode.LOAD_CONST, new Operand.ConstOperand(none), real, false);
        builder.emit(OpCode.RETURN_VALUE, real, true);
        builder.seal();

        CodeObject code = assemble(builder);
        equal(code.getInstructions().size(), code.getSourceMap().size(),
                "source map density");
        equal(real, code.getSourceMap().get(0).getSpan(), "real source span");
        check(!code.getSourceMap().get(0).isSynthetic(),
                "real instruction marked synthetic");
        check(code.getSourceMap().get(1).isSynthetic(),
                "implicit return must be synthetic");
    }

    private static void testFinalizedImmutability() {
        CodeObject code = minimalCode(0, "immutable", "immutable.py");
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("dialect", "flask-python");
        BytecodeModule module = new BytecodeModule(
                BytecodeFormat.MAGIC,
                BytecodeFormat.CURRENT_VERSION,
                "immutable.py",
                "immutable",
                code,
                metadata);
        metadata.put("later", "must-not-leak");

        check(!module.getMetadata().containsKey("later"),
                "module metadata was not defensively copied");
        expectThrows(UnsupportedOperationException.class,
                () -> module.getMetadata().put("x", "y"),
                "module metadata mutability");
        expectThrows(UnsupportedOperationException.class,
                () -> code.getInstructions().add(new Instruction(OpCode.NOP)),
                "instruction-list mutability");
        expectThrows(UnsupportedOperationException.class,
                () -> code.getNamePool().getNames().add("late"),
                "name-pool mutability");
        check(!code.isVerified(), "assembled raw code must not claim verification");
        equal(-1, code.getMaxStack(), "unverified maxStack sentinel");
    }

    private static void testDeterministicRawDisassembly() {
        String first = deterministicRawDisassembly();
        String second = deterministicRawDisassembly();
        equal(first, second, "repeat disassembly");
        check(first.startsWith(
                        "FBC\\0 magic=0x46424300 version=1 module=deterministic"),
                "stable format header: " + first);
        check(first.contains("source=deterministic.py"),
                "source path must be normalized to its basename");
        check(!first.contains("absolute/workspace"),
                "absolute workspace path leaked into disassembly");
        check(first.contains("JUMP L0002 (2)"),
                "resolved symbolic jump missing from disassembly");
        check(first.contains("L0002:"), "target label missing from disassembly");
        check(first.contains("[synthetic]"),
                "synthetic source marker missing from disassembly");
        check(!first.contains("\r"), "disassembly must use LF line endings");
        check(first.endsWith("\n"), "disassembly must end with LF");
    }

    private static String deterministicRawDisassembly() {
        SourceSpan source = new SourceSpan(
                "C:\\absolute\\workspace\\deterministic.py", 1, 0, 1, 1);
        CodeObjectBuilder builder = newBuilder(
                0, "deterministic", "C:\\absolute\\workspace\\deterministic.py");
        int value = builder.addConstant(PyInt.valueOf(7));
        Label done = builder.newLabel("done");
        builder.emit(OpCode.LOAD_CONST, new Operand.ConstOperand(value), source, false);
        builder.emitJump(OpCode.JUMP, done, source, true);
        builder.mark(done);
        builder.emit(OpCode.RETURN_VALUE, source, true);
        builder.seal();
        CodeObject code = assemble(builder);
        BytecodeModule module = new BytecodeModule(
                "C:\\absolute\\workspace\\deterministic.py",
                "deterministic",
                code);

        Disassembler disassembler = new Disassembler();
        return disassembler.disassemble(module);
    }

    private static void testFreshProcessDisassembly() throws Exception {
        String first = runDisassemblyProbe();
        String second = runDisassemblyProbe();
        equal(first, second, "fresh-process disassembly");
        equal(deterministicRawDisassembly(), first,
                "in-process/fresh-process disassembly");
    }

    private static String runDisassemblyProbe() throws Exception {
        Path java = Paths.get(
                System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win")
                        ? "java.exe"
                        : "java");
        Process process = new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                FlaskBytecodeModelHarness.class.getName(),
                "--emit-deterministic-disassembly")
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new AssertionError(
                    "fresh disassembly process exited " + exitCode
                            + ": " + output);
        }
        return output;
    }

    private static CodeObject minimalCode(int codeId, String name, String sourceFile) {
        CodeObjectBuilder builder = newBuilder(codeId, name, sourceFile);
        int none = builder.addConstant(PyNone.INSTANCE);
        builder.emit(
                OpCode.LOAD_CONST,
                new Operand.ConstOperand(none),
                SourceSpan.UNKNOWN,
                true);
        builder.emit(OpCode.RETURN_VALUE, SourceSpan.UNKNOWN, true);
        builder.seal();
        return assemble(builder);
    }

    private static CodeObjectBuilder newBuilder(
            int codeId, String name, String sourceFile) {
        return new CodeObjectBuilder(
                codeId,
                CodeKind.MODULE,
                "<module>",
                name,
                sourceFile,
                FunctionSignature.EMPTY,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList());
    }

    private static CodeObject assemble(CodeObjectBuilder builder) {
        DiagnosticReporter reporter = new DiagnosticReporter();
        CodeObject code = new BytecodeAssembler().assemble(builder, reporter);
        if (code == null || reporter.hasErrors()) {
            throw new AssertionError("assembly failed: " + reporter.diagnostics());
        }
        return code;
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

    private static void expectThrows(
            Class<? extends Throwable> expected,
            TestCase action,
            String label) {
        try {
            action.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError(
                    label + ": expected " + expected.getSimpleName()
                            + " but got " + actual,
                    actual);
        }
        throw new AssertionError(
                label + ": expected " + expected.getSimpleName() + " but nothing was thrown");
    }

    @FunctionalInterface
    private interface TestCase {
        void run() throws Exception;
    }
}
