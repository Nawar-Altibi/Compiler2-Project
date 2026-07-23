import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.codegen.bytecode.BytecodeModule;
import compilers.flask.codegen.bytecode.CodeKind;
import compilers.flask.codegen.bytecode.CodeObjectBuilder;
import compilers.flask.codegen.bytecode.FunctionSignature;
import compilers.flask.codegen.bytecode.OpCode;
import compilers.flask.codegen.bytecode.Operand;
import compilers.flask.codegen.bytecode.VerifiedBytecodeModule;
import compilers.flask.codegen.verify.BytecodeAssembler;
import compilers.flask.codegen.verify.ControlFlowVerifier;
import compilers.flask.vm.Namespace;
import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.VmRuntimeException;
import compilers.flask.vm.modules.ModuleLoader;
import compilers.flask.vm.modules.ModuleRegistry;
import compilers.flask.vm.values.PyInt;
import compilers.flask.vm.values.PyList;
import compilers.flask.vm.values.PyModule;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyValue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free behavioral gate for the Phase-6 module protocol. */
public final class FlaskModuleLoaderHarness {
    private static int passed;
    private static int failed;

    private FlaskModuleLoaderHarness() {
    }

    public static void main(String[] args) {
        run("native metadata, state, and stable cache",
                FlaskModuleLoaderHarness::testNativeMetadataAndCache);
        run("dotted import leaf/top-level and parent linkage",
                FlaskModuleLoaderHarness::testDottedImports);
        run("controlled circular import preserves identity",
                FlaskModuleLoaderHarness::testCircularImport);
        run("failed initialization rolls back and retries fresh",
                FlaskModuleLoaderHarness::testRollbackAndRetry);
        run("failed parent rolls back descendants created by its transaction",
                FlaskModuleLoaderHarness::testDescendantRollback);
        run("failed importer retains initialized unrelated dependencies",
                FlaskModuleLoaderHarness::testUnrelatedDependencySurvivesRollback);
        run("from-import value, submodule, and missing-name behavior",
                FlaskModuleLoaderHarness::testImportFrom);
        run("star import follows __all__ and underscore rules",
                FlaskModuleLoaderHarness::testImportStar);
        run("verified bytecode uses injected initialization bridge",
                FlaskModuleLoaderHarness::testVerifiedBytecodeBridge);
        run("registry rejects invalid/duplicate definitions and is immutable",
                FlaskModuleLoaderHarness::testRegistryContracts);
        run("parent initializer may import requested child reentrantly",
                FlaskModuleLoaderHarness::testReentrantParentInitialization);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " module-loader test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " module-loader tests passed.");
    }

    private static void testNativeMetadataAndCache() {
        AtomicInteger initializations = new AtomicInteger();
        ModuleRegistry registry = ModuleRegistry.builder()
                .registerNative("alpha", (module, loader) -> {
                    initializations.incrementAndGet();
                    equal(new PyString("alpha"), module.find("__name__").orElse(null),
                            "preinitialized __name__");
                    equal(PyString.EMPTY, module.find("__package__").orElse(null),
                            "preinitialized __package__");
                    equal(ModuleLoader.State.IN_PROGRESS,
                            loader.state("alpha").orElse(null), "initialization state");
                    module.put("answer", PyInt.valueOf(42));
                })
                .build();
        ModuleLoader loader = new ModuleLoader(registry);

        PyModule first = loader.load("alpha");
        PyModule second = loader.load("alpha");
        same(first, second, "repeated native import identity");
        equal(1, initializations.get(), "native initialization count");
        equal(ModuleLoader.State.INITIALIZED,
                loader.state("alpha").orElse(null), "completed state");
        equal(PyInt.valueOf(42), first.find("answer").orElse(null), "module export");
        expectThrows(UnsupportedOperationException.class,
                () -> loader.cacheSnapshot().clear(), "cache snapshot immutability");
    }

    private static void testDottedImports() {
        ModuleRegistry registry = ModuleRegistry.builder()
                .registerNativePackage("pkg")
                .registerNative("pkg.child", (module, loader) ->
                        module.put("value", new PyString("leaf")))
                .build();
        ModuleLoader loader = new ModuleLoader(registry);

        PyModule leaf = loader.importModule("pkg.child", ModuleLoader.ImportResult.LEAF);
        PyModule root = loader.importModule(
                "pkg.child", ModuleLoader.ImportResult.TOP_LEVEL);
        check(leaf != root, "leaf import unexpectedly returned root package");
        equal("pkg", leaf.getPackageName(), "leaf __package__ metadata");
        equal("pkg", root.getPackageName(), "package __package__ metadata");
        same(leaf, root.find("child").orElse(null), "parent child-module attribute");
        same(root, loader.loadTopLevel("pkg.child"), "top-level convenience result");
        equal(Arrays.asList("pkg", "pkg.child"),
                Arrays.asList(loader.cacheSnapshot().keySet().toArray(new String[0])),
                "deterministic cache order");
    }

    private static void testCircularImport() {
        AtomicReference<PyModule> seenAFromB = new AtomicReference<>();
        ModuleRegistry registry = ModuleRegistry.builder()
                .registerNative("cycle_a", (module, loader) -> {
                    same(module, loader.load("cycle_a"), "self import while in progress");
                    module.put("b", loader.load("cycle_b"));
                })
                .registerNative("cycle_b", (module, loader) -> {
                    PyModule a = loader.load("cycle_a");
                    seenAFromB.set(a);
                    module.put("a", a);
                })
                .build();
        ModuleLoader loader = new ModuleLoader(registry);

        PyModule a = loader.load("cycle_a");
        PyModule b = loader.load("cycle_b");
        same(a, seenAFromB.get(), "circular import returned a second A module");
        same(b, a.find("b").orElse(null), "A -> B link");
        same(a, b.find("a").orElse(null), "B -> A link");
        equal(ModuleLoader.State.INITIALIZED,
                loader.state("cycle_a").orElse(null), "A final state");
        equal(ModuleLoader.State.INITIALIZED,
                loader.state("cycle_b").orElse(null), "B final state");
    }

    private static void testRollbackAndRetry() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<PyModule> failedIdentity = new AtomicReference<>();
        VmRuntimeException original = RuntimeOps.error("ValueError", "initialization failed");
        ModuleRegistry registry = ModuleRegistry.builder()
                .registerNative("retryable", (module, loader) -> {
                    if (attempts.incrementAndGet() == 1) {
                        failedIdentity.set(module);
                        throw original;
                    }
                    module.put("ready", PyInt.ONE);
                })
                .build();
        ModuleLoader loader = new ModuleLoader(registry);

        try {
            loader.load("retryable");
            throw new AssertionError("failed initialization unexpectedly succeeded");
        } catch (VmRuntimeException failure) {
            same(original, failure, "loader did not preserve original failure");
        }
        check(!loader.isLoaded("retryable"), "failed module remained cached");
        check(!loader.state("retryable").isPresent(), "failed module retained state");

        PyModule retried = loader.load("retryable");
        check(retried != failedIdentity.get(), "retry reused failed module object");
        equal(2, attempts.get(), "retry initialization count");
        equal(PyInt.ONE, retried.find("ready").orElse(null), "retry result");
    }

    private static void testDescendantRollback() {
        AtomicInteger parentAttempts = new AtomicInteger();
        AtomicInteger childAttempts = new AtomicInteger();
        AtomicReference<PyModule> failedChild = new AtomicReference<>();
        ModuleRegistry registry = ModuleRegistry.builder()
                .registerNativePackage("transactional", (module, loader) -> {
                    PyModule child = loader.load("transactional.child");
                    if (parentAttempts.incrementAndGet() == 1) {
                        failedChild.set(child);
                        throw RuntimeOps.error("ValueError", "parent failed");
                    }
                })
                .registerNative("transactional.child", (module, loader) -> {
                    childAttempts.incrementAndGet();
                    module.put("ready", PyInt.ONE);
                })
                .build();
        ModuleLoader loader = new ModuleLoader(registry);

        expectVmError("ValueError", () -> loader.load("transactional"));
        check(!loader.isLoaded("transactional"), "failed parent remained cached");
        check(!loader.isLoaded("transactional.child"),
                "descendant from failed parent transaction remained cached");

        PyModule parent = loader.load("transactional");
        PyModule child = loader.load("transactional.child");
        check(parent != null, "retry did not initialize parent");
        check(child != failedChild.get(), "retry reused stale descendant identity");
        equal(2, parentAttempts.get(), "parent retry count");
        equal(2, childAttempts.get(), "child retry count");
        same(child, parent.find("child").orElse(null), "fresh parent/child linkage");
    }

    private static void testUnrelatedDependencySurvivesRollback() {
        AtomicInteger importerAttempts = new AtomicInteger();
        AtomicInteger dependencyAttempts = new AtomicInteger();
        AtomicReference<PyModule> firstDependency = new AtomicReference<>();
        ModuleRegistry registry = ModuleRegistry.builder()
                .registerNative("independent", (module, loader) -> {
                    dependencyAttempts.incrementAndGet();
                    module.put("ready", PyInt.ONE);
                })
                .registerNative("failing_importer", (module, loader) -> {
                    PyModule dependency = loader.load("independent");
                    firstDependency.compareAndSet(null, dependency);
                    if (importerAttempts.incrementAndGet() == 1) {
                        throw RuntimeOps.error("ValueError", "importer failed");
                    }
                    module.put("dependency", dependency);
                })
                .build();
        ModuleLoader loader = new ModuleLoader(registry);

        expectVmError("ValueError", () -> loader.load("failing_importer"));
        check(!loader.isLoaded("failing_importer"),
                "failed importer remained cached");
        check(loader.isLoaded("independent"),
                "successful unrelated dependency was evicted");
        equal(ModuleLoader.State.INITIALIZED,
                loader.state("independent").orElse(null),
                "unrelated dependency final state");

        PyModule retried = loader.load("failing_importer");
        same(firstDependency.get(), retried.find("dependency").orElse(null),
                "retry did not reuse stable unrelated dependency");
        equal(2, importerAttempts.get(), "importer retry count");
        equal(1, dependencyAttempts.get(), "unrelated dependency init count");
    }

    private static void testImportFrom() {
        ModuleRegistry registry = ModuleRegistry.builder()
                .registerNativePackage("api", (module, loader) ->
                        module.put("direct", new PyString("value")))
                .registerNative("api.child", (module, loader) ->
                        module.put("nested", PyInt.ONE))
                .build();
        ModuleLoader loader = new ModuleLoader(registry);
        PyModule api = loader.load("api");

        equal(new PyString("value"), loader.importFrom(api, "direct"),
                "direct from-import");
        PyValue child = loader.importFrom(api, "child");
        check(child instanceof PyModule, "submodule from-import did not return module");
        same(child, api.find("child").orElse(null), "loaded submodule was not linked");
        expectVmError("ImportError", () -> loader.importFrom(api, "missing"));
        expectVmError("ImportError", () -> loader.load("does_not_exist"));
    }

    private static void testImportStar() {
        ModuleRegistry registry = ModuleRegistry.builder()
                .registerNative("fallback_exports", (module, loader) -> {
                    module.put("visible", PyInt.ONE);
                    module.put("_hidden", PyInt.ZERO);
                })
                .registerNative("declared_exports", (module, loader) -> {
                    module.put("visible", PyInt.ONE);
                    module.put("_selected", PyInt.valueOf(2));
                    module.put("__all__", new PyList(Collections.singletonList(
                            new PyString("_selected"))));
                })
                .registerNative("invalid_exports", (module, loader) -> {
                    module.put("value", PyInt.ONE);
                    module.put("__all__", new PyList(Collections.singletonList(PyInt.ONE)));
                })
                .build();
        ModuleLoader loader = new ModuleLoader(registry);

        Namespace fallback = new Namespace();
        loader.importStar(loader.load("fallback_exports"), fallback);
        equal(Collections.singleton("visible"), fallback.snapshot().keySet(),
                "fallback public exports");

        Namespace declared = new Namespace();
        loader.importStar(loader.load("declared_exports"), declared);
        equal(Collections.singleton("_selected"), declared.snapshot().keySet(),
                "explicit __all__ exports");
        equal(PyInt.valueOf(2), declared.find("_selected").orElse(null),
                "explicit underscore export");

        expectVmError("TypeError", () -> loader.importStar(
                loader.load("invalid_exports"), new Namespace()));
    }

    private static void testVerifiedBytecodeBridge() {
        VerifiedBytecodeModule verified = verifiedModule("compiled.mod");
        AtomicInteger bridgeCalls = new AtomicInteger();
        ModuleRegistry registry = ModuleRegistry.builder()
                .registerNativePackage("compiled")
                .registerBytecode("compiled.mod", verified)
                .build();
        ModuleLoader loader = new ModuleLoader(
                registry,
                (bytecode, module, activeLoader) -> {
                    same(verified, bytecode, "bridge verified-bytecode identity");
                    equal(new PyString("compiled.mod"),
                            module.find("__name__").orElse(null),
                            "bytecode module preinitialized __name__");
                    equal(new PyString("compiled"),
                            module.find("__package__").orElse(null),
                            "bytecode module preinitialized __package__");
                    equal(ModuleLoader.State.IN_PROGRESS,
                            activeLoader.state("compiled.mod").orElse(null),
                            "bytecode initialization state");
                    bridgeCalls.incrementAndGet();
                    module.put("executed", PyInt.ONE);
                });

        PyModule module = loader.load("compiled.mod");
        same(module, loader.load("compiled.mod"), "bytecode module cache identity");
        equal(1, bridgeCalls.get(), "bytecode bridge call count");
        equal(PyInt.ONE, module.find("executed").orElse(null), "bytecode bridge export");

        ModuleLoader missingBridge = new ModuleLoader(registry);
        expectVmError("ImportError", () -> missingBridge.load("compiled.mod"));
        check(!missingBridge.isLoaded("compiled.mod"),
                "missing bytecode bridge left a cached child module");
    }

    private static void testRegistryContracts() {
        ModuleRegistry.Builder builder = ModuleRegistry.builder()
                .registerNative("stable", (module, loader) -> { });
        expectThrows(IllegalArgumentException.class,
                () -> builder.registerNative("stable", (module, loader) -> { }),
                "duplicate module registration");
        expectThrows(IllegalArgumentException.class,
                () -> ModuleRegistry.builder().registerNative(
                        "bad-name", (module, loader) -> { }),
                "invalid module name");

        ModuleRegistry registry = builder.build();
        expectThrows(UnsupportedOperationException.class,
                () -> registry.definitions().clear(), "registry immutability");

        VerifiedBytecodeModule wrongName = verifiedModule("actual_name");
        expectThrows(IllegalArgumentException.class,
                () -> ModuleRegistry.builder().registerBytecode(
                        "different_name", wrongName),
                "bytecode/registry name mismatch");
    }

    private static void testReentrantParentInitialization() {
        AtomicReference<PyModule> childSeenByParent = new AtomicReference<>();
        ModuleRegistry registry = ModuleRegistry.builder()
                .registerNativePackage("tree", (module, loader) ->
                        childSeenByParent.set(loader.load("tree.leaf")))
                .registerNative("tree.leaf", (module, loader) ->
                        module.put("ok", PyInt.ONE))
                .build();
        ModuleLoader loader = new ModuleLoader(registry);

        PyModule requested = loader.load("tree.leaf");
        same(requested, childSeenByParent.get(),
                "outer child load did not reuse reentrant child");
        same(requested, loader.load("tree").find("leaf").orElse(null),
                "reentrant child parent linkage");
        equal(2, loader.cacheSnapshot().size(), "reentrant cache size");
    }

    private static VerifiedBytecodeModule verifiedModule(String moduleName) {
        CodeObjectBuilder builder = new CodeObjectBuilder(
                0,
                CodeKind.MODULE,
                "<module>",
                moduleName,
                moduleName + ".py",
                FunctionSignature.EMPTY,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList());
        int none = builder.addConstant(PyNone.INSTANCE);
        builder.emit(OpCode.LOAD_CONST, new Operand.ConstOperand(none),
                SourceSpan.UNKNOWN, true);
        builder.emit(OpCode.RETURN_VALUE, SourceSpan.UNKNOWN, true);
        builder.seal();

        DiagnosticReporter reporter = new DiagnosticReporter();
        BytecodeModule assembled = new BytecodeAssembler().assembleModule(
                moduleName + ".py", moduleName, builder, reporter);
        check(assembled != null && !reporter.hasErrors(),
                "test bytecode assembly failed: " + reporter.diagnostics());
        VerifiedBytecodeModule verified = new ControlFlowVerifier().verify(
                assembled, reporter);
        check(verified != null && !reporter.hasErrors(),
                "test bytecode verification failed: " + reporter.diagnostics());
        return verified;
    }

    private static void expectVmError(String type, ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("Expected " + type);
        } catch (VmRuntimeException failure) {
            equal(type, failure.getExceptionValue().getExceptionTypeName(),
                    "VM error type");
        } catch (Exception other) {
            throw new AssertionError("Expected VM " + type + " but got " + other, other);
        }
    }

    private static void expectThrows(
            Class<? extends Throwable> type,
            ThrowingRunnable action,
            String label) {
        try {
            action.run();
            throw new AssertionError(label + ": expected " + type.getSimpleName());
        } catch (Throwable failure) {
            if (!type.isInstance(failure)) {
                throw new AssertionError(label + ": unexpected " + failure, failure);
            }
        }
    }

    private static void run(String name, ThrowingRunnable test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS: " + name);
        } catch (Throwable failure) {
            failed++;
            System.err.println("FAIL: " + name + " -> " + failure.getMessage());
            failure.printStackTrace(System.err);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void same(Object expected, Object actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": identities differ");
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                    label + ": expected <" + expected + "> but got <" + actual + ">");
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
