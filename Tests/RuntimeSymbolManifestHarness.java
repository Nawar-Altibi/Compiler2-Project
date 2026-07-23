import compilers.flask.runtime.RuntimeSymbolManifest;
import compilers.flask.runtime.RuntimeSymbolSpec;
import compilers.flask.semantic.FlaskFrameworkSymbols;
import compilers.flask.semantic.PythonBuiltins;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free tests for the shared semantic symbol manifest.
 *
 * <p>Trimmed in the generation refactor: the VM provider-parity checks were
 * removed together with the VM itself; what remains is the contract that the
 * semantic views ({@code PythonBuiltins}, {@code FlaskFrameworkSymbols}) are
 * exact views of the single {@code RuntimeSymbolManifest} registry.</p>
 */
public final class RuntimeSymbolManifestHarness {
    private static int passed;
    private static int failed;

    private RuntimeSymbolManifestHarness() {
    }

    public static void main(String[] args) {
        run("builtin semantic view is canonical",
                RuntimeSymbolManifestHarness::testBuiltinView);
        run("unsupported names are not advertised",
                RuntimeSymbolManifestHarness::testRemovedNames);
        run("Flask export view is canonical",
                RuntimeSymbolManifestHarness::testFlaskView);
        run("capability metadata is precise",
                RuntimeSymbolManifestHarness::testCapabilities);
        run("manifest views are immutable",
                RuntimeSymbolManifestHarness::testImmutability);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(failed + " manifest test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " runtime manifest tests passed.");
    }

    private static void testBuiltinView() {
        equal(RuntimeSymbolManifest.builtins().keySet(), PythonBuiltins.symbols().keySet(),
                "PythonBuiltins must be a manifest view");
        check(PythonBuiltins.contains("print"), "print missing");
        check(PythonBuiltins.contains("sorted"), "sorted missing");
        check(PythonBuiltins.contains("open"), "open missing");
        check(PythonBuiltins.contains("ValueError"), "ValueError missing");
    }

    private static void testRemovedNames() {
        String[] removed = {
                "__name__", "super", "property", "staticmethod", "classmethod",
                "bytes", "bytearray", "complex", "frozenset", "memoryview", "slice",
                "NotImplemented", "Ellipsis", "input", "help", "dir", "vars"
        };
        for (String name : removed) {
            check(!PythonBuiltins.contains(name), name + " must not be a builtin");
        }
    }

    private static void testFlaskView() {
        List<String> expected = Arrays.asList(
                "Flask", "Blueprint", "Response", "render_template", "redirect",
                "jsonify", "abort", "make_response", "url_for", "flash", "send_file",
                "send_from_directory", "request", "session", "g", "current_app");
        equal(expected, new ArrayList<>(FlaskFrameworkSymbols.exports().keySet()),
                "Flask export order/content");
        equal(RuntimeSymbolManifest.moduleExports(RuntimeSymbolManifest.FLASK_MODULE).keySet(),
                FlaskFrameworkSymbols.exports().keySet(),
                "FlaskFrameworkSymbols must be a manifest view");
    }

    private static void testCapabilities() {
        equal(RuntimeSymbolSpec.Capability.FILESYSTEM_READ,
                spec("builtins", "open").requiredCapability(), "open capability");
        equal(RuntimeSymbolSpec.Capability.REQUEST_CONTEXT,
                spec("flask", "request").requiredCapability(), "request capability");
        equal(RuntimeSymbolSpec.Capability.APP_CONTEXT,
                spec("flask", "url_for").requiredCapability(), "url_for capability");
        equal(RuntimeSymbolSpec.Capability.FILESYSTEM_READ,
                spec("flask", "send_file").requiredCapability(), "send_file capability");
    }

    private static void testImmutability() {
        expectUnsupported(() -> RuntimeSymbolManifest.builtins().clear());
        expectUnsupported(() -> RuntimeSymbolManifest.modules().clear());
        expectUnsupported(() -> FlaskFrameworkSymbols.exports().clear());
    }

    private static RuntimeSymbolSpec spec(String module, String name) {
        RuntimeSymbolSpec value = RuntimeSymbolManifest.lookup(module, name);
        check(value != null, module + "." + name + " missing");
        return value;
    }

    private static void expectUnsupported(ThrowingRunnable operation) {
        try {
            operation.run();
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // expected
        } catch (Exception other) {
            throw new AssertionError("Unexpected exception: " + other, other);
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

    private static void equal(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
