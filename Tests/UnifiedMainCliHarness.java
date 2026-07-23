import Main.UnifiedMain;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dependency-free tests for the {@link UnifiedMain} command-line boundary.
 * They exercise option parsing, the frozen pipeline gates, and the exit-code
 * contract from reference plan section 15 without spawning a process: the
 * testable {@code run(args, out, err)} entry point returns the exit code and
 * writes to injected streams.
 */
public final class UnifiedMainCliHarness {
    private static int passed;
    private static int failed;

    private UnifiedMainCliHarness() {
    }

    public static void main(String[] args) throws Exception {
        run("missing source argument maps to CLI failure", UnifiedMainCliHarness::testNoArguments);
        run("nonexistent source file maps to CLI failure", UnifiedMainCliHarness::testMissingFile);
        run("unknown option maps to CLI failure", UnifiedMainCliHarness::testUnknownOption);
        run("duplicate option maps to CLI failure", UnifiedMainCliHarness::testDuplicateOption);
        run("malformed route path maps to CLI failure", UnifiedMainCliHarness::testBadRoutePath);
        run("default Python teaching view does not execute VM", UnifiedMainCliHarness::testDefaultTeachingView);
        run("warnings do not block requested artifacts", UnifiedMainCliHarness::testWarningsDoNotBlock);
        run("syntax error gates symbols and bytecode", UnifiedMainCliHarness::testSyntaxErrorGate);
        run("AST structure error gates symbols and bytecode", UnifiedMainCliHarness::testAstStructureGate);
        run("semantic error stops before execution with code 1", UnifiedMainCliHarness::testSemanticError);
        run("clean run-bytecode succeeds with code 0", UnifiedMainCliHarness::testRunBytecodeSuccess);
        run("root module executes once as __main__", UnifiedMainCliHarness::testRootNameAndRunOnce);
        run("runtime failure maps to code 2", UnifiedMainCliHarness::testRuntimeFailure);
        run("filesystem read is denied unless rooted capability is supplied", UnifiedMainCliHarness::testFileSystemReadCapability);
        run("disassemble emits deterministic listing", UnifiedMainCliHarness::testDisassemble);
        run("route invocation executes module and route once", UnifiedMainCliHarness::testInvokeRoute);
        run("route request context is isolated between CLI invocations", UnifiedMainCliHarness::testRouteContextIsolation);
        run("HTML teaching view uses injected output streams", UnifiedMainCliHarness::testHtmlTeachingView);
        run("embedded CSS diagnostics use injected stderr without Java trace", UnifiedMainCliHarness::testHtmlCssDiagnosticStream);
        run("Python-only HTML option is rejected before parsing", UnifiedMainCliHarness::testHtmlRejectsPythonOption);
        run("internal fault is categorized without Java trace", UnifiedMainCliHarness::testInternalFailureWithoutDebug);
        run("debug prints the preserved Java cause for internal faults", UnifiedMainCliHarness::testInternalFailureWithDebug);
        run("terminating-branch program runs end to end via CLI", UnifiedMainCliHarness::testTerminatingBranchCli);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(failed + " CLI test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " UnifiedMain CLI tests passed.");
    }

    private static void testNoArguments() {
        Invocation result = invoke();
        equal(3, result.exitCode, "empty argument exit code");
        contains(result.err, "CLI error", "empty argument diagnostic");
    }

    private static void testMissingFile() {
        Invocation result = invoke("this_file_does_not_exist_12345.py", "--run-bytecode");
        equal(3, result.exitCode, "missing file exit code");
        contains(result.err, "does not exist", "missing file diagnostic");
    }

    private static void testUnknownOption() throws Exception {
        Path file = pyFile("value = 1\n");
        Invocation result = invoke(file.toString(), "--nope");
        equal(3, result.exitCode, "unknown option exit code");
        contains(result.err, "Unknown option", "unknown option diagnostic");
    }

    private static void testDuplicateOption() throws Exception {
        Path file = pyFile("value = 1\n");
        Invocation result = invoke(file.toString(), "--ast", "--ast");
        equal(3, result.exitCode, "duplicate option exit code");
        contains(result.err, "Duplicate option", "duplicate option diagnostic");
    }

    private static void testBadRoutePath() throws Exception {
        Path file = pyFile("value = 1\n");
        Invocation result = invoke(file.toString(), "--invoke-route", "GET", "no-leading-slash");
        equal(3, result.exitCode, "bad route path exit code");
        contains(result.err, "must start with", "bad route path diagnostic");
    }

    private static void testDefaultTeachingView() throws Exception {
        Path file = pyFile("raise RuntimeError(\"VM must not run in teaching view\")\n");
        Invocation result = invoke(file.toString());
        equal(0, result.exitCode, "default teaching-view exit code (" + result.err + ")");
        contains(result.out, "====== Flask AST ======", "default AST output");
        contains(result.out, "====== Flask SYMBOL TABLE ======", "default symbol output");
        notContains(result.err, "Traceback", "default invocation must not execute bytecode");
    }

    private static void testWarningsDoNotBlock() throws Exception {
        Path file = pyFile(
                "value = 1\n",
                "value = \"now text\"\n");
        Invocation result = invoke(file.toString(), "--disassemble");
        equal(0, result.exitCode, "warnings-only exit code (" + result.err + ")");
        contains(result.err, "Type Mismatch", "mandatory warning output");
        contains(result.out, "MODULE __main__", "artifact emitted despite warning");
    }

    private static void testSyntaxErrorGate() throws Exception {
        Path file = pyFile("def broken(:\n", "    pass\n");
        Invocation result = invoke(file.toString(), "--symbols", "--disassemble");
        equal(1, result.exitCode, "syntax-error exit code");
        contains(result.err, "Syntax Error", "syntax diagnostic");
        notContains(result.out, "SYMBOL TABLE", "syntax failure symbol gate");
        notContains(result.out, "MODULE __main__", "syntax failure codegen gate");
    }

    private static void testAstStructureGate() throws Exception {
        Path file = pyFile("break\n");
        Invocation result = invoke(file.toString(), "--symbols", "--disassemble");
        equal(1, result.exitCode, "AST-structure exit code");
        contains(result.err, "Invalid Control Flow", "AST-structure diagnostic");
        notContains(result.out, "SYMBOL TABLE", "AST failure symbol gate");
        notContains(result.out, "MODULE __main__", "AST failure codegen gate");
    }

    private static void testSemanticError() throws Exception {
        Path file = pyFile("print(undefined_name)\n");
        Invocation result = invoke(file.toString(), "--diagnostics");
        equal(1, result.exitCode, "semantic error exit code");
        contains(result.err, "undefined_name", "semantic error diagnostic");
    }

    private static void testRunBytecodeSuccess() throws Exception {
        Path file = pyFile("a = 1 + 2\nb = a * 3\n");
        Invocation result = invoke(file.toString(), "--run-bytecode");
        equal(0, result.exitCode, "clean run exit code (" + result.err + ")");
    }

    private static void testRootNameAndRunOnce() throws Exception {
        Path file = pyFile(
                "print(\"module-run-marker\")\n",
                "print(__name__)\n");
        Invocation result = invoke(file.toString(), "--run-bytecode");
        equal(0, result.exitCode, "root run exit code (" + result.err + ")");
        equal(1, occurrences(result.out, "module-run-marker"),
                "root module execution count");
        contains(result.out, "__main__", "root module canonical name");
    }

    private static void testRuntimeFailure() throws Exception {
        Path file = pyFile("boom = 1 // 0\n");
        Invocation result = invoke(file.toString(), "--run-bytecode");
        equal(2, result.exitCode, "runtime failure exit code");
        contains(result.err, "ZeroDivisionError", "runtime failure traceback");
    }

    private static void testFileSystemReadCapability() throws Exception {
        Path temporaryBase = Path.of(".tmp").toAbsolutePath().normalize();
        Files.createDirectories(temporaryBase);
        Path root = Files.createTempDirectory(temporaryBase, "unifiedmain_fs_root_");
        root.toFile().deleteOnExit();
        Path payload = root.resolve("payload.txt");
        Files.write(payload, "capability-content".getBytes(StandardCharsets.UTF_8));
        payload.toFile().deleteOnExit();
        Path file = pyFile("print(open(\"payload.txt\").read())\n");

        Invocation denied = invoke(file.toString(), "--run-bytecode");
        equal(2, denied.exitCode, "filesystem denied exit code");
        contains(denied.err, "PermissionError", "filesystem denied traceback");

        Invocation allowed = invoke(file.toString(), "--run-bytecode",
                "--allow-fs-read", root.toString());
        equal(0, allowed.exitCode, "filesystem allowed exit code (" + allowed.err + ")");
        contains(allowed.out, "capability-content", "rooted filesystem read output");
    }

    private static void testDisassemble() throws Exception {
        Path file = pyFile("answer = 42\n");
        Invocation first = invoke(file.toString(), "--disassemble");
        Invocation second = invoke(file.toString(), "--disassemble");
        equal(0, first.exitCode, "disassemble exit code");
        contains(first.out, "MODULE __main__", "disassembly module header");
        contains(first.out, "RETURN_VALUE", "disassembly terminator");
        equal(first.out, second.out, "disassembly is deterministic");
    }

    private static void testInvokeRoute() throws Exception {
        Path file = pyFile(
                "from flask import Flask\n",
                "app = Flask(__name__)\n",
                "print(\"module-once\")\n",
                "@app.route(\"/\")\n",
                "def index():\n",
                "    print(\"route-once\")\n",
                "    return \"hello-from-route\"\n");
        Invocation result = invoke(file.toString(), "--invoke-route", "GET", "/");
        equal(0, result.exitCode, "route invocation exit code (" + result.err + ")");
        contains(result.out, "hello-from-route", "route response body");
        equal(1, occurrences(result.out, "module-once"), "route bridge module count");
        equal(1, occurrences(result.out, "route-once"), "route callable count");
    }

    private static void testRouteContextIsolation() throws Exception {
        Path file = pyFile(
                "from flask import Flask, request\n",
                "app = Flask(__name__)\n",
                "@app.route(\"/ctx/<value>\")\n",
                "def context(value):\n",
                "    return request.path\n");
        Invocation first = invoke(file.toString(), "--invoke-route", "GET", "/ctx/first");
        Invocation second = invoke(file.toString(), "--invoke-route", "GET", "/ctx/second");
        equal(0, first.exitCode, "first context invocation (" + first.err + ")");
        equal(0, second.exitCode, "second context invocation (" + second.err + ")");
        contains(first.out, "/ctx/first", "first request context");
        contains(second.out, "/ctx/second", "second request context");
        notContains(second.out, "/ctx/first", "request context leak");
    }

    private static void testHtmlTeachingView() throws Exception {
        Path file = htmlFile("<html><body><h1>Hello</h1></body></html>\n");
        Invocation result = invoke(file.toString());
        equal(0, result.exitCode, "HTML teaching-view exit code (" + result.err + ")");
        contains(result.out, "====== HTML/CSS AST ======", "HTML AST output");
        contains(result.out, "====== HTML/CSS SYMBOL TABLE ======", "HTML symbols output");
        equal("", result.err, "HTML diagnostics stream");
    }

    private static void testHtmlCssDiagnosticStream() throws Exception {
        Path file = htmlFile(
                "<html><head><style>body { color: ; }</style></head><body></body></html>\n");
        Invocation result = invoke(file.toString(), "--ast");
        equal(0, result.exitCode, "embedded CSS diagnostic exit code");
        contains(result.err, "[CSS Error] <style>", "captured CSS diagnostic");
        notContains(result.err, "\tat ", "normal CSS diagnostic Java stack");
    }

    private static void testHtmlRejectsPythonOption() throws Exception {
        Path file = htmlFile("<definitely malformed html");
        Invocation result = invoke(file.toString(), "--run-bytecode");
        equal(3, result.exitCode, "HTML Python-option exit code");
        contains(result.err, "Python bytecode/runtime options", "HTML option diagnostic");
        notContains(result.err, "Parser Error", "HTML pipeline must not run");
        equal("", result.out, "rejected HTML stdout");
    }

    private static void testInternalFailureWithoutDebug() throws Exception {
        Path file = htmlFile("<html><body>internal test</body></html>\n");
        Invocation result = invokeWithOut(
                new FailingPrintStream("synthetic presenter failure"),
                file.toString(), "--ast");
        equal(3, result.exitCode, "internal failure exit code");
        contains(result.err, "[Pipeline Error]", "internal diagnostic phase");
        contains(result.err, "Internal Compiler Error", "internal diagnostic category");
        contains(result.err, "synthetic presenter failure", "internal diagnostic message");
        notContains(result.err, "IllegalStateException", "hidden Java exception class");
        notContains(result.err, "\tat ", "hidden Java stack frames");
    }

    private static void testInternalFailureWithDebug() throws Exception {
        Path file = htmlFile("<html><body>debug test</body></html>\n");
        Invocation result = invokeWithOut(
                new FailingPrintStream("synthetic debug failure"),
                file.toString(), "--ast", "--debug");
        equal(3, result.exitCode, "debug internal failure exit code");
        contains(result.err, "Internal Compiler Error", "debug internal diagnostic");
        contains(result.err, "IllegalStateException", "debug Java exception class");
        contains(result.err, "\tat ", "debug Java stack frames");
    }

    private static void testTerminatingBranchCli() throws Exception {
        Path file = pyFile(
                "def classify(x):\n",
                "    if x:\n",
                "        return 1\n",
                "    else:\n",
                "        return 2\n",
                "result = classify(0)\n");
        Invocation result = invoke(file.toString(), "--run-bytecode");
        equal(0, result.exitCode, "terminating-branch CLI exit code (" + result.err + ")");
    }

    private static Invocation invoke(String... args) {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
        return invokeWithStreams(out, outBuffer, args);
    }

    private static Invocation invokeWithOut(PrintStream out, String... args) {
        return invokeWithStreams(out, new ByteArrayOutputStream(), args);
    }

    private static Invocation invokeWithStreams(
            PrintStream out,
            ByteArrayOutputStream outBuffer,
            String... args) {
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
        int exitCode = UnifiedMain.run(args, out, err);
        out.flush();
        err.flush();
        return new Invocation(
                exitCode,
                outBuffer.toString(StandardCharsets.UTF_8),
                errBuffer.toString(StandardCharsets.UTF_8));
    }

    private static Path pyFile(String... lines) throws Exception {
        Path file = Files.createTempFile("unifiedmain_cli_", ".py");
        file.toFile().deleteOnExit();
        StringBuilder content = new StringBuilder();
        for (String line : lines) {
            content.append(line);
        }
        Files.write(file, content.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static Path htmlFile(String content) throws Exception {
        Path file = Files.createTempFile("unifiedmain_cli_", ".html");
        file.toFile().deleteOnExit();
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
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

    private static void contains(String haystack, String needle, String label) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError(
                    label + ": expected output to contain <" + needle + "> but was <" + haystack + ">");
        }
    }

    private static void notContains(String haystack, String needle, String label) {
        if (haystack != null && haystack.contains(needle)) {
            throw new AssertionError(
                    label + ": expected output not to contain <" + needle + "> but was <"
                            + haystack + ">");
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while (text != null && (offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static void equal(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                    label + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class Invocation {
        private final int exitCode;
        private final String out;
        private final String err;

        private Invocation(int exitCode, String out, String err) {
            this.exitCode = exitCode;
            this.out = out;
            this.err = err;
        }
    }

    private static final class FailingPrintStream extends PrintStream {
        private final String message;

        private FailingPrintStream(String message) {
            super(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
            this.message = message;
        }

        @Override
        public void println(String value) {
            throw new IllegalStateException(message);
        }
    }
}
