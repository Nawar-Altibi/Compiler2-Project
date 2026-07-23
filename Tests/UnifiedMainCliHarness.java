import Main.UnifiedMain;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Small CLI-boundary tests for the generation-era {@code UnifiedMain}
 * (plan section 8.2): option parsing, dispatch, and the frozen
 * 0/1/2/3 exit-code contract.
 */
public final class UnifiedMainCliHarness {
    private static int passed;
    private static int failed;

    private UnifiedMainCliHarness() {
    }

    public static void main(String[] args) throws Exception {
        run("missing source argument maps to exit 3", UnifiedMainCliHarness::testNoArguments);
        run("nonexistent source maps to exit 3", UnifiedMainCliHarness::testMissingFile);
        run("unknown option maps to exit 3", UnifiedMainCliHarness::testUnknownOption);
        run("duplicate option maps to exit 3", UnifiedMainCliHarness::testDuplicateOption);
        run("--out without a value maps to exit 3", UnifiedMainCliHarness::testMissingOutValue);
        run("analysis flags keep the teaching views on .py", UnifiedMainCliHarness::testAnalysisViews);
        run(".jinja single file dispatches to the html front end", UnifiedMainCliHarness::testJinjaDispatch);
        run("--quiet suppresses the generation summary", UnifiedMainCliHarness::testQuiet);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(failed + " CLI test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " UnifiedMain CLI tests passed.");
    }

    private static void testNoArguments() {
        Invocation result = invoke();
        equal(3, result.exitCode, "empty args exit code");
        check(result.err.contains("CLI error"), "usage error printed");
    }

    private static void testMissingFile() {
        Invocation result = invoke("no_such_file_9573.py");
        equal(3, result.exitCode, "missing file exit code");
        check(result.err.contains("does not exist"), "missing file message");
    }

    private static void testUnknownOption() throws Exception {
        Path file = pyFile("value = 1\n");
        Invocation result = invoke(file.toString(), "--nope");
        equal(3, result.exitCode, "unknown option exit code");
        check(result.err.contains("Unknown option"), "unknown option message");
    }

    private static void testDuplicateOption() throws Exception {
        Path file = pyFile("value = 1\n");
        Invocation result = invoke(file.toString(), "--ast", "--ast");
        equal(3, result.exitCode, "duplicate option exit code");
        check(result.err.contains("Duplicate option"), "duplicate option message");
    }

    private static void testMissingOutValue() throws Exception {
        Path file = pyFile("value = 1\n");
        Invocation result = invoke(file.toString(), "--out");
        equal(3, result.exitCode, "--out without value exit code");
        check(result.err.contains("requires a directory"), "value error message");
    }

    private static void testAnalysisViews() throws Exception {
        Path file = pyFile("value = 1 + 2\n");
        Invocation result = invoke(file.toString(), "--ast", "--symbols");
        equal(0, result.exitCode, "analysis exit code (" + result.err + ")");
        check(result.out.contains("====== Flask AST ======"), "AST view printed");
        check(result.out.contains("====== Flask SYMBOL TABLE ======"),
                "symbol view printed");
        check(!result.out.contains("====== GENERATION ======"),
                "analysis flags must not trigger generation");
    }

    private static void testJinjaDispatch() throws Exception {
        Path file = Files.createTempFile("cli_", ".jinja");
        file.toFile().deleteOnExit();
        Files.writeString(file, "<p>{{ name }}</p>\n", StandardCharsets.UTF_8);
        Invocation result = invoke(file.toString(), "--ast");
        equal(0, result.exitCode, "jinja analysis exit code (" + result.err + ")");
        check(result.out.contains("====== HTML/CSS AST ======"), "html AST printed");
    }

    private static void testQuiet() throws Exception {
        Path project = Files.createTempDirectory("cli_project_");
        project.toFile().deleteOnExit();
        Files.writeString(project.resolve("app.py"),
                "from flask import Flask, render_template\n"
                        + "app = Flask(__name__)\n"
                        + "@app.route(\"/\")\n"
                        + "def index():\n"
                        + "    return render_template(\"page.jinja\")\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(project.resolve("templates"));
        Files.writeString(project.resolve("templates/page.jinja"),
                "<p>ok</p>\n", StandardCharsets.UTF_8);

        Path work = Files.createTempDirectory("cli_out_");
        work.toFile().deleteOnExit();
        Invocation result = invoke(project.toString(),
                "--out", work.resolve("output").toString(),
                "--reports", work.resolve("reports").toString(),
                "--quiet");
        equal(0, result.exitCode, "quiet generation exit code (" + result.err + ")");
        check(result.out.isEmpty(), "quiet run must print nothing to stdout");
        check(Files.isRegularFile(work.resolve("output/page.html")),
                "page generated in --out dir");
    }

    // ==================== plumbing ====================

    private static final class Invocation {
        final int exitCode;
        final String out;
        final String err;

        Invocation(int exitCode, String out, String err) {
            this.exitCode = exitCode;
            this.out = out;
            this.err = err;
        }
    }

    private static Invocation invoke(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = UnifiedMain.run(args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Invocation(exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static Path pyFile(String content) throws Exception {
        Path file = Files.createTempFile("cli_", ".py");
        file.toFile().deleteOnExit();
        Files.writeString(file, content, StandardCharsets.UTF_8);
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

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
