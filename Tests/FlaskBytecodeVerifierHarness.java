import compilers.diagnostics.Diagnostic;
import compilers.flask.ast.nodes.SourceSpan;
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
import compilers.flask.codegen.bytecode.NamePool;
import compilers.flask.codegen.bytecode.OpCode;
import compilers.flask.codegen.bytecode.Operand;
import compilers.flask.codegen.bytecode.SourceMap;
import compilers.flask.codegen.bytecode.StackAnchor;
import compilers.flask.codegen.bytecode.VerifiedBytecodeModule;
import compilers.flask.codegen.disasm.Disassembler;
import compilers.flask.codegen.verify.ControlFlowVerifier;
import compilers.flask.vm.BytecodeVM;
import compilers.flask.vm.VmResult;
import compilers.flask.vm.values.PyBool;
import compilers.flask.vm.values.PyNone;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free, hand-authored verifier tests.
 *
 * <p>These fixtures deliberately construct immutable {@link CodeObject}s
 * directly.  They therefore test the verifier's trust boundary independently
 * of the AST generator and symbolic assembler.</p>
 */
public final class FlaskBytecodeVerifierHarness {
    private static final String SOURCE = "bytecode-verifier.py";
    private static final SourceSpan SPAN =
            new SourceSpan(SOURCE, 1, 0, 1, 1);
    private static int nextCodeId = 1;
    private static int passed;
    private static int failed;

    private FlaskBytecodeVerifierHarness() {
    }

    public static void main(String[] args) {
        run("valid branch computes maxStack and anchors",
                FlaskBytecodeVerifierHarness::testValidBranchAndMaxStack);
        run("format, version, and root-version gate",
                FlaskBytecodeVerifierHarness::testFormatAndVersionGate);
        run("module root layout is executable",
                FlaskBytecodeVerifierHarness::testModuleRootLayout);
        run("operand-stack underflow is rejected",
                FlaskBytecodeVerifierHarness::testStackUnderflow);
        run("incompatible branch join is rejected",
                FlaskBytecodeVerifierHarness::testIncompatibleJoin);
        run("pool and slot indices are checked",
                FlaskBytecodeVerifierHarness::testInvalidIndices);
        run("import names and wildcard scope are checked",
                FlaskBytecodeVerifierHarness::testInvalidImportContracts);
        run("VM normalizes forged malformed import boundaries",
                FlaskBytecodeVerifierHarness::testVmImportBoundaryNormalization);
        run("fallthrough and jump targets are checked",
                FlaskBytecodeVerifierHarness::testFallthroughAndJumpTarget);
        run("malformed and orphan regions are rejected",
                FlaskBytecodeVerifierHarness::testMalformedRegions);
        run("generator-authored anchors are rejected",
                FlaskBytecodeVerifierHarness::testGeneratorSuppliedAnchor);
        run("temporary definite initialization is enforced",
                FlaskBytecodeVerifierHarness::testTemporaryInitialization);
        run("temporary lifetime bounds are enforced",
                FlaskBytecodeVerifierHarness::testTemporaryLifetime);
        run("plain jump cannot cross cleanup boundary",
                FlaskBytecodeVerifierHarness::testCleanupBoundaryCrossing);
        run("unwind jumps cannot target special entries",
                FlaskBytecodeVerifierHarness::testUnwindSpecialEntry);
        run("cleanup handlers cannot escape without END_CLEANUP",
                FlaskBytecodeVerifierHarness::testCleanupHandlerEscape);
        run("three nested cleanup depths are resolved outer-first",
                FlaskBytecodeVerifierHarness::testTripleNestedCleanupDepths);
        run("CodeRef cannot reach a Python-visible consumer",
                FlaskBytecodeVerifierHarness::testInternalCodeRefConsumer);
        run("nested code is verified recursively",
                FlaskBytecodeVerifierHarness::testRecursiveNestedVerification);
        run("invalid unreferenced nested code rejects module",
                FlaskBytecodeVerifierHarness::testInvalidNestedVerification);
        run("VM accepts only a current non-stale verification token",
                FlaskBytecodeVerifierHarness::testVerifiedOnlyAndStaleStamp);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " bytecode-verifier test(s) failed; "
                            + passed + " passed");
        }
        System.out.println("All " + passed
                + " Flask bytecode-verifier tests passed.");
    }

    private static void testValidBranchAndMaxStack() {
        List<Object> constants = Arrays.<Object>asList(
                PyBool.TRUE, PyNone.INSTANCE);
        CodeObject root = rawCode(
                freshId(), CodeKind.MODULE, "valid_branch",
                Arrays.asList(
                        instruction(OpCode.LOAD_CONST, new Operand.ConstOperand(0)),
                        instruction(OpCode.POP_JUMP_IF_FALSE,
                                new Operand.JumpOperand(6)),
                        instruction(OpCode.LOAD_CONST, new Operand.ConstOperand(1)),
                        instruction(OpCode.LOAD_CONST, new Operand.ConstOperand(1)),
                        instruction(OpCode.BUILD_TUPLE, new Operand.CountOperand(2)),
                        instruction(OpCode.JUMP, new Operand.JumpOperand(7)),
                        instruction(OpCode.LOAD_CONST, new Operand.ConstOperand(1)),
                        instruction(OpCode.RETURN_VALUE)),
                constants);

        ControlFlowVerifier.VerificationResult result = verify(module(root));
        check(result.isSuccess(), diagnostics(result));
        check(result.getDiagnostics().isEmpty(), "valid code emitted diagnostics");
        VerifiedBytecodeModule verified = result.getModule();
        check(verified != null && verified.hasValidVerificationStamp(),
                "successful verification did not issue a valid token");
        CodeObject verifiedRoot = verified.getModule().getRootCode();
        equal(2, verifiedRoot.getMaxStack(), "verified maxStack");
        check(verifiedRoot.isVerified(), "verified root was not finalized");
        check(verifiedRoot != root, "verifier mutated/reused the raw root");
        check(!verifiedRoot.getStackAnchors().isEmpty(),
                "verifier did not materialize symbolic anchors");
    }

    private static void testFormatAndVersionGate() {
        CodeObject root = simpleReturnCode(freshId(), CodeKind.MODULE, "format_root");
        BytecodeModule valid = module(root);

        reject(new BytecodeModule(
                        BytecodeFormat.MAGIC ^ 0x10,
                        BytecodeFormat.CURRENT_VERSION,
                        SOURCE, "bad_magic", root,
                        Collections.<String, String>emptyMap()),
                "Invalid bytecode magic");
        reject(new BytecodeModule(
                        BytecodeFormat.MAGIC,
                        BytecodeFormat.CURRENT_VERSION + 1,
                        SOURCE, "bad_version", root,
                        Collections.<String, String>emptyMap()),
                "Unsupported bytecode version");

        CodeObject wrongCodeVersion = rawCode(
                BytecodeFormat.CURRENT_VERSION + 1,
                freshId(), CodeKind.MODULE, "bad_code_version",
                Arrays.asList(
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0,
                Collections.<ExceptionRegion>emptyList(),
                Collections.<CleanupRegion>emptyList(),
                Collections.<StackAnchor>emptyList(),
                -1);
        reject(module(wrongCodeVersion), "does not match module version");
        check(verify(valid).isSuccess(), "current format was rejected");
    }

    private static void testStackUnderflow() {
        CodeObject root = rawCode(
                freshId(), CodeKind.MODULE, "underflow",
                Arrays.asList(
                        instruction(OpCode.POP_TOP),
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE));
        reject(module(root), "underflows the operand stack");
    }

    private static void testModuleRootLayout() {
        CodeObject rootWithFreeVariable = rawCode(
                BytecodeFormat.CURRENT_VERSION,
                freshId(), CodeKind.MODULE, "module_with_free_variable",
                Arrays.asList(
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.singletonList("captured"),
                0,
                Collections.<ExceptionRegion>emptyList(),
                Collections.<CleanupRegion>emptyList(),
                Collections.<StackAnchor>emptyList(),
                -1);
        reject(module(rootWithFreeVariable),
                "Module root may not declare fast, cell, or free-variable slots");
    }

    private static void testIncompatibleJoin() {
        CodeObject root = rawCode(
                freshId(), CodeKind.MODULE, "bad_join",
                Arrays.asList(
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.POP_JUMP_IF_FALSE,
                                new Operand.JumpOperand(4)),
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(1)),
                        instruction(OpCode.JUMP, new Operand.JumpOperand(5)),
                        instruction(OpCode.NOP),
                        instruction(OpCode.RETURN_VALUE)),
                Arrays.<Object>asList(PyBool.TRUE, PyNone.INSTANCE));
        reject(module(root), "Incompatible stack join");
    }

    private static void testInvalidIndices() {
        CodeObject root = rawCode(
                freshId(), CodeKind.MODULE, "bad_indices",
                Arrays.asList(
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.LOAD_NAME,
                                new Operand.NameOperand(0)),
                        instruction(OpCode.LOAD_FAST,
                                new Operand.LocalSlotOperand(0)),
                        instruction(OpCode.LOAD_DEREF,
                                new Operand.DerefSlotOperand(0)),
                        instruction(OpCode.LOAD_TEMP,
                                new Operand.TempSlotOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.emptyList());
        reject(module(root),
                "Invalid constant index",
                "Invalid name index",
                "Invalid fast-local index",
                "Invalid cell/free index",
                "Invalid temporary index");
    }

    private static void testInvalidImportContracts() {
        CodeObject invalidModuleName = rawCode(
                freshId(), CodeKind.MODULE, "invalid_import_module",
                Arrays.asList(
                        instruction(OpCode.IMPORT_NAME,
                                new Operand.ImportSpec("broken..module")),
                        instruction(OpCode.POP_TOP),
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE));
        reject(module(invalidModuleName),
                "Invalid canonical import module name 'broken..module'");

        CodeObject invalidFromName = rawCode(
                BytecodeFormat.CURRENT_VERSION,
                freshId(), CodeKind.MODULE, "invalid_import_from",
                Arrays.asList(
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.IMPORT_FROM,
                                new Operand.NameOperand(0)),
                        instruction(OpCode.POP_TOP),
                        instruction(OpCode.POP_TOP),
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE),
                Collections.singletonList("not.an_identifier"),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0,
                Collections.<ExceptionRegion>emptyList(),
                Collections.<CleanupRegion>emptyList(),
                Collections.<StackAnchor>emptyList(),
                -1);
        reject(module(invalidFromName),
                "Invalid IMPORT_FROM identifier 'not.an_identifier'");

        CodeObject invalidWildcardFunction = rawCode(
                freshId(), CodeKind.FUNCTION, "invalid_import_star",
                Arrays.asList(
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.IMPORT_STAR),
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE));
        CodeObject wildcardOwner = rawCode(
                freshId(), CodeKind.MODULE, "invalid_import_star_owner",
                Arrays.asList(
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(1)),
                        instruction(OpCode.RETURN_VALUE)),
                Arrays.<Object>asList(invalidWildcardFunction, PyNone.INSTANCE));
        reject(module(wildcardOwner),
                "IMPORT_STAR is only valid in MODULE code");
    }

    /**
     * Reflection deliberately bypasses the verifier-owned token solely to
     * exercise the VM's defense-in-depth checks. Ordinary callers cannot
     * obtain these executable wrappers through the supported API.
     */
    private static void testVmImportBoundaryNormalization() throws Exception {
        CodeObject invalidModuleName = rawCode(
                BytecodeFormat.CURRENT_VERSION,
                freshId(), CodeKind.MODULE, "forged_import_module",
                Arrays.asList(
                        instruction(OpCode.IMPORT_NAME,
                                new Operand.ImportSpec("broken..module")),
                        instruction(OpCode.POP_TOP),
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0,
                Collections.<ExceptionRegion>emptyList(),
                Collections.<CleanupRegion>emptyList(),
                Collections.<StackAnchor>emptyList(),
                1);
        assertVmImportFailure(
                forgeVerified(module(invalidModuleName)),
                "ImportError", "invalid module name");

        CodeObject invalidFromName = rawCode(
                BytecodeFormat.CURRENT_VERSION,
                freshId(), CodeKind.MODULE, "forged_import_from",
                Arrays.asList(
                        instruction(OpCode.IMPORT_NAME,
                                new Operand.ImportSpec("flask")),
                        instruction(OpCode.IMPORT_FROM,
                                new Operand.NameOperand(0)),
                        instruction(OpCode.POP_TOP),
                        instruction(OpCode.POP_TOP),
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE),
                Collections.singletonList("not.an_identifier"),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0,
                Collections.<ExceptionRegion>emptyList(),
                Collections.<CleanupRegion>emptyList(),
                Collections.<StackAnchor>emptyList(),
                2);
        assertVmImportFailure(
                forgeVerified(module(invalidFromName)),
                "ImportError", "invalid imported name");

        CodeObject invalidWildcardFunction = rawCode(
                BytecodeFormat.CURRENT_VERSION,
                freshId(), CodeKind.FUNCTION, "forged_import_star_function",
                Arrays.asList(
                        instruction(OpCode.IMPORT_NAME,
                                new Operand.ImportSpec("flask")),
                        instruction(OpCode.IMPORT_STAR),
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0,
                Collections.<ExceptionRegion>emptyList(),
                Collections.<CleanupRegion>emptyList(),
                Collections.<StackAnchor>emptyList(),
                1);
        CodeObject wildcardRoot = rawCode(
                BytecodeFormat.CURRENT_VERSION,
                freshId(), CodeKind.MODULE, "forged_import_star_root",
                Arrays.asList(
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.MAKE_FUNCTION,
                                new Operand.MakeFunctionSpec(
                                        Collections.<String>emptyList(),
                                        Collections.<String>emptyList(),
                                        Collections.<String>emptyList())),
                        instruction(OpCode.CALL,
                                new Operand.CallSpec(
                                        0, Collections.<String>emptyList())),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(invalidWildcardFunction),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0,
                Collections.<ExceptionRegion>emptyList(),
                Collections.<CleanupRegion>emptyList(),
                Collections.<StackAnchor>emptyList(),
                1);
        assertVmImportFailure(
                forgeVerified(module(wildcardRoot)),
                "ImportError", "only valid at module scope");
    }

    private static VerifiedBytecodeModule forgeVerified(BytecodeModule raw)
            throws Exception {
        Constructor<ControlFlowVerifier.VerificationStamp> constructor =
                ControlFlowVerifier.VerificationStamp.class
                        .getDeclaredConstructor(BytecodeModule.class);
        constructor.setAccessible(true);
        return VerifiedBytecodeModule.fromVerifier(
                raw, constructor.newInstance(raw));
    }

    private static void assertVmImportFailure(
            VerifiedBytecodeModule module,
            String expectedType,
            String expectedMessage) {
        VmResult result = new BytecodeVM().execute(module);
        check(result.isFailure(), "malformed import unexpectedly executed");
        equal(expectedType,
                result.getTraceback().getException().getExceptionTypeName(),
                "malformed import VM error type");
        check(result.getTraceback().getException().getMessageText()
                        .contains(expectedMessage),
                "malformed import VM message: "
                        + result.getTraceback().getException().getMessageText());
    }

    private static void testFallthroughAndJumpTarget() {
        CodeObject fallthrough = rawCode(
                freshId(), CodeKind.MODULE, "fallthrough",
                Collections.singletonList(instruction(OpCode.NOP)),
                Collections.emptyList());
        reject(module(fallthrough), "falls through beyond the code array");

        CodeObject badTarget = rawCode(
                freshId(), CodeKind.MODULE, "bad_target",
                Collections.singletonList(instruction(
                        OpCode.JUMP, new Operand.JumpOperand(99))),
                Collections.emptyList());
        reject(module(badTarget), "Invalid jump target 99");
    }

    private static void testMalformedRegions() {
        List<Instruction> instructions = Arrays.asList(
                instruction(OpCode.NOP),
                instruction(OpCode.NOP),
                instruction(OpCode.NOP),
                instruction(OpCode.NOP),
                instruction(OpCode.LOAD_CONST, new Operand.ConstOperand(0)),
                instruction(OpCode.RETURN_VALUE));
        List<ExceptionRegion> overlapping = Arrays.asList(
                new ExceptionRegion(0, 0, 3, 4, null),
                new ExceptionRegion(1, 2, 4, 5, null));
        CodeObject malformed = rawCode(
                BytecodeFormat.CURRENT_VERSION,
                freshId(), CodeKind.MODULE, "partial_regions",
                instructions,
                Collections.<Object>singletonList(PyNone.INSTANCE),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0,
                overlapping,
                Collections.<CleanupRegion>emptyList(),
                Collections.<StackAnchor>emptyList(),
                -1);
        String malformedDisassembly = new Disassembler().disassemble(module(malformed));
        check(malformedDisassembly.contains("exception-regions:"),
                "disassembly omitted exception-region section");
        check(malformedDisassembly.contains("E0 parent=-1 depth=0 protected=[0,3)"),
                "disassembly omitted exception-region metadata");
        reject(module(malformed), "partially overlap");

        CodeObject orphan = rawCode(
                BytecodeFormat.CURRENT_VERSION,
                freshId(), CodeKind.MODULE, "orphan_handler",
                Arrays.asList(
                        instruction(OpCode.NOP),
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0,
                Collections.singletonList(
                        new ExceptionRegion(0, 0, 1, 1, null)),
                Collections.<CleanupRegion>emptyList(),
                Collections.<StackAnchor>emptyList(),
                -1);
        reject(module(orphan),
                "Ordinary edge enters handler/cleanup offset",
                "Handler/cleanup entry is reachable by an ordinary edge");
    }

    private static void testGeneratorSuppliedAnchor() {
        CodeObject root = rawCode(
                BytecodeFormat.CURRENT_VERSION,
                freshId(), CodeKind.MODULE, "raw_anchor",
                Arrays.asList(
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0,
                Collections.<ExceptionRegion>emptyList(),
                Collections.<CleanupRegion>emptyList(),
                Collections.singletonList(new StackAnchor(
                        0, 0, Collections.emptyList())),
                -1);
        reject(module(root), "generator-supplied stack anchors");
    }

    private static void testTemporaryInitialization() {
        List<Instruction> instructions = Arrays.asList(
                instruction(OpCode.LOAD_TEMP, new Operand.TempSlotOperand(0)),
                instruction(OpCode.CLEAR_TEMP, new Operand.TempSlotOperand(0)),
                instruction(OpCode.RETURN_VALUE));
        CleanupRegion lifetime = new CleanupRegion(
                0, CleanupRegion.Kind.TEMP_CLEAR,
                0, 2, -1, -1, 0, null);
        CodeObject root = tempCode(
                "temp_uninitialized", instructions, lifetime);
        reject(module(root), "before definite initialization");
    }

    private static void testTemporaryLifetime() {
        List<Instruction> instructions = Arrays.asList(
                instruction(OpCode.LOAD_CONST, new Operand.ConstOperand(0)),
                instruction(OpCode.STORE_TEMP, new Operand.TempSlotOperand(0)),
                instruction(OpCode.CLEAR_TEMP, new Operand.TempSlotOperand(0)),
                instruction(OpCode.LOAD_TEMP, new Operand.TempSlotOperand(0)),
                instruction(OpCode.RETURN_VALUE));
        CleanupRegion lifetime = new CleanupRegion(
                0, CleanupRegion.Kind.TEMP_CLEAR,
                1, 2, -1, -1, 0, null);
        CodeObject root = tempCode(
                "temp_outside_lifetime", instructions, lifetime);
        reject(module(root), "accessed outside its declared lifetime");
    }

    private static void testCleanupBoundaryCrossing() {
        List<Instruction> instructions = Arrays.asList(
                instruction(OpCode.LOAD_CONST, new Operand.ConstOperand(0)),
                instruction(OpCode.STORE_TEMP, new Operand.TempSlotOperand(0)),
                instruction(OpCode.JUMP, new Operand.JumpOperand(4)),
                instruction(OpCode.CLEAR_TEMP, new Operand.TempSlotOperand(0)),
                instruction(OpCode.LOAD_CONST, new Operand.ConstOperand(0)),
                instruction(OpCode.RETURN_VALUE));
        CleanupRegion lifetime = new CleanupRegion(
                0, CleanupRegion.Kind.TEMP_CLEAR,
                1, 3, -1, -1, 0, null);
        CodeObject root = tempCode("plain_cleanup_jump", instructions, lifetime);
        reject(module(root), "Plain control-flow edge crosses cleanup boundary");
    }

    private static void testUnwindSpecialEntry() {
        CodeObject root = rawCode(
                BytecodeFormat.CURRENT_VERSION,
                freshId(), CodeKind.MODULE, "unwind_special_entry",
                Arrays.asList(
                        instruction(OpCode.NOP),
                        instruction(OpCode.UNWIND_JUMP,
                                new Operand.JumpOperand(4)),
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE),
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0,
                Collections.singletonList(
                        new ExceptionRegion(0, 0, 2, 4, null)),
                Collections.<CleanupRegion>emptyList(),
                Collections.<StackAnchor>emptyList(),
                -1);
        reject(module(root),
                "UNWIND_JUMP targets handler/cleanup entry");
    }

    private static void testCleanupHandlerEscape() {
        CleanupRegion cleanup = new CleanupRegion(
                0,
                CleanupRegion.Kind.FINALLY,
                0,
                2,
                2,
                4,
                -1,
                null);
        CodeObject root = rawCode(
                BytecodeFormat.CURRENT_VERSION,
                freshId(), CodeKind.MODULE, "cleanup_handler_escape",
                Arrays.asList(
                        instruction(OpCode.NOP),
                        instruction(OpCode.ENTER_CLEANUP,
                                new Operand.CleanupSpec(0)),
                        instruction(OpCode.JUMP,
                                new Operand.JumpOperand(4)),
                        instruction(OpCode.END_CLEANUP,
                                new Operand.CleanupSpec(0)),
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0,
                Collections.<ExceptionRegion>emptyList(),
                Collections.singletonList(cleanup),
                Collections.<StackAnchor>emptyList(),
                -1);
        reject(module(root),
                "exits without END_CLEANUP");
    }

    private static void testTripleNestedCleanupDepths() {
        List<Instruction> instructions = Arrays.asList(
                instruction(OpCode.LOAD_CONST, new Operand.ConstOperand(0)),
                instruction(OpCode.STORE_TEMP, new Operand.TempSlotOperand(0)),
                instruction(OpCode.LOAD_CONST, new Operand.ConstOperand(0)),
                instruction(OpCode.STORE_TEMP, new Operand.TempSlotOperand(1)),
                instruction(OpCode.LOAD_CONST, new Operand.ConstOperand(0)),
                instruction(OpCode.STORE_TEMP, new Operand.TempSlotOperand(2)),
                instruction(OpCode.CLEAR_TEMP, new Operand.TempSlotOperand(2)),
                instruction(OpCode.CLEAR_TEMP, new Operand.TempSlotOperand(1)),
                instruction(OpCode.CLEAR_TEMP, new Operand.TempSlotOperand(0)),
                instruction(OpCode.LOAD_CONST, new Operand.ConstOperand(0)),
                instruction(OpCode.RETURN_VALUE));
        List<CleanupRegion> regions = Arrays.asList(
                new CleanupRegion(0, CleanupRegion.Kind.TEMP_CLEAR,
                        1, 9, -1, -1, 0, null),
                new CleanupRegion(1, CleanupRegion.Kind.TEMP_CLEAR,
                        3, 8, -1, -1, 1, null),
                new CleanupRegion(2, CleanupRegion.Kind.TEMP_CLEAR,
                        5, 7, -1, -1, 2, null));
        CodeObject root = rawCode(
                BytecodeFormat.CURRENT_VERSION,
                freshId(), CodeKind.MODULE, "triple_cleanup",
                instructions,
                Collections.<Object>singletonList(PyNone.INSTANCE),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                3,
                Collections.<ExceptionRegion>emptyList(),
                regions,
                Collections.<StackAnchor>emptyList(),
                -1);

        ControlFlowVerifier.VerificationResult result = verify(module(root));
        check(result.isSuccess(), diagnostics(result));
        List<CleanupRegion> verified = result.getModule().getModule()
                .getRootCode().getCleanupRegions();
        equal(0, cleanupById(verified, 0).getNestingDepth(), "outer depth");
        equal(-1, cleanupById(verified, 0).getParentBoundaryId(), "outer parent");
        equal(1, cleanupById(verified, 1).getNestingDepth(), "middle depth");
        equal(0, cleanupById(verified, 1).getParentBoundaryId(), "middle parent");
        equal(2, cleanupById(verified, 2).getNestingDepth(), "inner depth");
        equal(1, cleanupById(verified, 2).getParentBoundaryId(), "inner parent");

        Disassembler disassembler = new Disassembler();
        String first = disassembler.disassemble(result.getModule());
        String second = disassembler.disassemble(result.getModule());
        equal(first, second, "verified disassembly determinism");
        check(first.contains("signature parameters=[] defaults=[]"),
                "disassembly omitted function-signature metadata");
        check(first.contains("cleanup-regions:"),
                "disassembly omitted cleanup-region section");
        check(first.contains("C2 TEMP_CLEAR parent=1 depth=2"),
                "disassembly omitted resolved nested cleanup metadata");
        check(first.contains("anchor=A"),
                "disassembly omitted verifier-owned region anchors");
    }

    private static CleanupRegion cleanupById(
            List<CleanupRegion> regions, int id) {
        for (CleanupRegion region : regions) {
            if (region.getId() == id) {
                return region;
            }
        }
        throw new AssertionError("missing cleanup region " + id);
    }

    private static void testInternalCodeRefConsumer() {
        CodeObject nested = simpleReturnCode(
                freshId(), CodeKind.FUNCTION, "internal_function");
        CodeObject root = rawCode(
                freshId(), CodeKind.MODULE, "code_ref_escape",
                Arrays.asList(
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(nested));
        reject(module(root), "requires PY_VALUE", "CODE_REF");
    }

    private static void testRecursiveNestedVerification() {
        CodeObject nested = rawCode(
                freshId(), CodeKind.FUNCTION, "unreferenced_nested",
                Arrays.asList(
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.BUILD_TUPLE,
                                new Operand.CountOperand(2)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE));
        CodeObject root = rawCode(
                freshId(), CodeKind.MODULE, "deep_root",
                Arrays.asList(
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(1)),
                        instruction(OpCode.RETURN_VALUE)),
                Arrays.<Object>asList(nested, PyNone.INSTANCE));

        ControlFlowVerifier.VerificationResult result = verify(module(root));
        check(result.isSuccess(), diagnostics(result));
        CodeObject verifiedRoot = result.getModule().getModule().getRootCode();
        Object nestedConstant = verifiedRoot.getConstantPool().get(0);
        check(nestedConstant instanceof CodeObject,
                "nested constant lost its code-object type");
        CodeObject verifiedNested = (CodeObject) nestedConstant;
        check(verifiedNested != nested,
                "nested code was not rebuilt by recursive verification");
        equal(2, verifiedNested.getMaxStack(), "nested maxStack");
        check(verifiedNested.isVerified(), "nested code remained unverified");
    }

    private static void testInvalidNestedVerification() {
        CodeObject invalidNested = rawCode(
                freshId(), CodeKind.FUNCTION, "invalid_unreferenced_nested",
                Arrays.asList(
                        instruction(OpCode.POP_TOP),
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE));
        CodeObject root = rawCode(
                freshId(), CodeKind.MODULE, "nested_failure_root",
                Arrays.asList(
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(1)),
                        instruction(OpCode.RETURN_VALUE)),
                Arrays.<Object>asList(invalidNested, PyNone.INSTANCE));
        reject(module(root),
                "invalid_unreferenced_nested",
                "underflows the operand stack");
    }

    private static void testVerifiedOnlyAndStaleStamp() throws Exception {
        BytecodeModule raw = module(simpleReturnCode(
                freshId(), CodeKind.MODULE, "verified_gate"));
        ControlFlowVerifier.VerificationResult result = verify(raw);
        check(result.isSuccess(), diagnostics(result));
        VerifiedBytecodeModule verified = result.getModule();

        boolean rawExecuteOverload = false;
        for (Method method : BytecodeVM.class.getMethods()) {
            if (method.getName().equals("execute")
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == BytecodeModule.class) {
                rawExecuteOverload = true;
            }
        }
        check(!rawExecuteOverload,
                "VM exposes an execute(BytecodeModule) trust-boundary bypass");

        VmResult execution = new BytecodeVM().execute(verified);
        check(execution.isSuccess(), "verified module did not execute");
        check(execution.getReturnValue() == PyNone.INSTANCE,
                "verified module returned an unexpected value");

        Constructor<ControlFlowVerifier.VerificationStamp> constructor =
                ControlFlowVerifier.VerificationStamp.class
                        .getDeclaredConstructor(BytecodeModule.class);
        check(Modifier.isPrivate(constructor.getModifiers()),
                "verification stamp constructor is forgeable");
        constructor.setAccessible(true);
        ControlFlowVerifier.VerificationStamp stamp =
                constructor.newInstance(verified.getModule());

        BytecodeModule staleIdentity = new BytecodeModule(
                verified.getModule().getMagic(),
                verified.getModule().getFormatVersion(),
                verified.getModule().getSourceName(),
                verified.getModule().getModuleName(),
                verified.getModule().getRootCode(),
                verified.getModule().getMetadata());
        expectThrows(IllegalArgumentException.class,
                () -> VerifiedBytecodeModule.fromVerifier(staleIdentity, stamp));
    }

    private static CodeObject tempCode(
            String name,
            List<Instruction> instructions,
            CleanupRegion lifetime) {
        return rawCode(
                BytecodeFormat.CURRENT_VERSION,
                freshId(), CodeKind.MODULE, name,
                instructions,
                Collections.<Object>singletonList(PyNone.INSTANCE),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                1,
                Collections.<ExceptionRegion>emptyList(),
                Collections.singletonList(lifetime),
                Collections.<StackAnchor>emptyList(),
                -1);
    }

    private static CodeObject simpleReturnCode(
            int codeId, CodeKind kind, String name) {
        return rawCode(
                codeId, kind, name,
                Arrays.asList(
                        instruction(OpCode.LOAD_CONST,
                                new Operand.ConstOperand(0)),
                        instruction(OpCode.RETURN_VALUE)),
                Collections.<Object>singletonList(PyNone.INSTANCE));
    }

    private static CodeObject rawCode(
            int codeId,
            CodeKind kind,
            String name,
            List<Instruction> instructions,
            List<?> constants) {
        return rawCode(
                BytecodeFormat.CURRENT_VERSION,
                codeId, kind, name, instructions, constants,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0,
                Collections.<ExceptionRegion>emptyList(),
                Collections.<CleanupRegion>emptyList(),
                Collections.<StackAnchor>emptyList(),
                -1);
    }

    private static CodeObject rawCode(
            int formatVersion,
            int codeId,
            CodeKind kind,
            String name,
            List<Instruction> instructions,
            List<?> constants,
            List<String> names,
            List<String> fastLocals,
            List<String> cellVariables,
            List<String> freeVariables,
            int temporarySlots,
            List<ExceptionRegion> exceptionRegions,
            List<CleanupRegion> cleanupRegions,
            List<StackAnchor> stackAnchors,
            int maxStack) {
        List<InstructionLocation> locations = new ArrayList<>();
        for (int index = 0; index < instructions.size(); index++) {
            locations.add(new InstructionLocation(SPAN, false));
        }
        return new CodeObject(
                formatVersion,
                codeId,
                kind,
                name,
                name,
                SOURCE,
                instructions,
                new ConstantPool(constants),
                new NamePool(names),
                FunctionSignature.EMPTY,
                fastLocals,
                cellVariables,
                freeVariables,
                temporarySlots,
                exceptionRegions,
                cleanupRegions,
                new SourceMap(locations),
                stackAnchors,
                maxStack);
    }

    private static Instruction instruction(OpCode opCode) {
        return new Instruction(opCode);
    }

    private static Instruction instruction(OpCode opCode, Operand operand) {
        return new Instruction(opCode, operand);
    }

    private static BytecodeModule module(CodeObject root) {
        return new BytecodeModule(SOURCE, "verifier_test", root);
    }

    private static ControlFlowVerifier.VerificationResult verify(
            BytecodeModule module) {
        return new ControlFlowVerifier().verify(module);
    }

    private static void reject(BytecodeModule module, String... fragments) {
        ControlFlowVerifier.VerificationResult result = verify(module);
        check(!result.isSuccess(), "invalid bytecode unexpectedly verified");
        check(result.getModule() == null,
                "failed verification leaked an executable module");
        check(!result.getDiagnostics().isEmpty(),
                "failed verification emitted no diagnostic");
        String rendered = diagnostics(result);
        for (String fragment : fragments) {
            check(rendered.contains(fragment),
                    "missing diagnostic fragment '" + fragment
                            + "' in: " + rendered);
        }
    }

    private static String diagnostics(
            ControlFlowVerifier.VerificationResult result) {
        StringBuilder rendered = new StringBuilder();
        for (Diagnostic diagnostic : result.getDiagnostics()) {
            if (rendered.length() != 0) {
                rendered.append(" | ");
            }
            rendered.append(diagnostic.message());
        }
        return rendered.toString();
    }

    private static int freshId() {
        return nextCodeId++;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected " + expected
                    + " but got " + actual);
        }
    }

    private static <T extends Throwable> void expectThrows(
            Class<T> expected,
            ThrowingRunnable action) throws Exception {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) {
                return;
            }
            throw new AssertionError(
                    "expected " + expected.getSimpleName()
                            + " but got " + failure,
                    failure);
        }
        throw new AssertionError(
                "expected " + expected.getSimpleName() + " to be thrown");
    }

    private static void run(String name, ThrowingRunnable test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable failure) {
            failed++;
            System.out.println("FAIL  " + name + " -> " + failure.getMessage());
            failure.printStackTrace(System.out);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
