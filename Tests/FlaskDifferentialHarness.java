import compilers.flask.pipeline.FlaskCompilationResult;
import compilers.flask.pipeline.FlaskCompilerPipeline;
import compilers.flask.vm.BytecodeVM;
import compilers.flask.vm.VmResult;
import compilers.flask.vm.values.PyValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Optional CPython oracle tests for the pure supported language subset. */
public final class FlaskDifferentialHarness {
    private static int passed;
    private static int failed;
    private static List<String> pythonCommand;

    private FlaskDifferentialHarness() {
    }

    public static void main(String[] args) throws Exception {
        pythonCommand = findPython();
        if (pythonCommand == null) {
            System.out.println("SKIP  no compatible CPython executable was found");
            return;
        }

        run("loop stdout and selected globals match CPython",
                FlaskDifferentialHarness::testLoopAndGlobals);
        run("function branches match CPython",
                FlaskDifferentialHarness::testFunctions);
        run("exception-driven finally bare raise matches CPython",
                FlaskDifferentialHarness::testBareRaiseThroughFinally);
        run("uncaught exception type and relevant message match CPython",
                FlaskDifferentialHarness::testException);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " differential test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " CPython differential tests passed.");
    }

    private static void testLoopAndGlobals() throws Exception {
        String source = lines(
                "values = [1, 2, 3]",
                "total = 0",
                "for value in values:",
                "    total += value",
                "print(total)");
        VmResult vm = execute(source);
        success(vm);
        Oracle oracle = runPython(source + lines(
                "print('__TOTAL__=' + repr(total))",
                "print('__VALUES__=' + repr(values))"));
        equal(0, oracle.exitCode, "CPython loop exit code");
        equal(oracle.stdoutWithoutMarkers(), vm.getStdout(), "loop stdout");
        equal("6", oracle.marker("__TOTAL__="), "CPython total");
        equal(vm.getGlobals().get("total").repr(), oracle.marker("__TOTAL__="),
                "selected total global");
        equal(vm.getGlobals().get("values").repr(), oracle.marker("__VALUES__="),
                "selected values global");
    }

    private static void testFunctions() throws Exception {
        String source = lines(
                "def classify(value):",
                "    if value > 2:",
                "        return 'big'",
                "    else:",
                "        return 'small'",
                "first = classify(1)",
                "second = classify(4)",
                "print(first + ':' + second)");
        VmResult vm = execute(source);
        success(vm);
        Oracle oracle = runPython(source);
        equal(0, oracle.exitCode, "CPython function exit code");
        equal(oracle.stdout, vm.getStdout(), "function stdout");
    }

    private static void testBareRaiseThroughFinally() throws Exception {
        String source = lines(
                "def reraiser():",
                "    raise",
                "caught = False",
                "try:",
                "    try:",
                "        raise ValueError('outer')",
                "    finally:",
                "        reraiser()",
                "except ValueError:",
                "    caught = True",
                "print(caught)");
        VmResult vm = execute(source);
        success(vm);
        Oracle oracle = runPython(source);
        equal(0, oracle.exitCode, "CPython bare-raise exit code");
        equal(oracle.stdout, vm.getStdout(), "bare raise stdout");
        PyValue caught = vm.getGlobals().get("caught");
        equal("True", caught.repr(), "bare raise caught flag");
    }

    private static void testException() throws Exception {
        String source = lines(
                "def explode():",
                "    return 1 // 0",
                "explode()");
        VmResult vm = execute(source);
        check(vm.isFailure(), "custom VM unexpectedly accepted division by zero");
        Oracle oracle = runPython(source);
        check(oracle.exitCode != 0, "CPython unexpectedly accepted division by zero");
        equal("ZeroDivisionError",
                vm.getTraceback().getException().getExceptionTypeName(),
                "custom exception type");
        check(oracle.stderr.contains("ZeroDivisionError"),
                "CPython exception type differs: " + oracle.stderr);
        check(vm.getTraceback().getException().getMessageText()
                        .toLowerCase().contains("division"),
                "custom exception lost relevant division message");
        check(oracle.stderr.toLowerCase().contains("division"),
                "CPython exception lost relevant division message");
    }

    private static VmResult execute(String source) {
        FlaskCompilationResult compilation = new FlaskCompilerPipeline()
                .compileSource(source, "differential.py", "__main__");
        check(compilation.isSuccess(), diagnostics(compilation));
        return new BytecodeVM().execute(compilation.requireVerifiedModule());
    }

    private static Oracle runPython(String source) throws Exception {
        List<String> command = new ArrayList<>(pythonCommand);
        command.add("-c");
        command.add(source);
        Process process = new ProcessBuilder(command).start();
        StreamCollector stdoutCollector = new StreamCollector(process.getInputStream());
        StreamCollector stderrCollector = new StreamCollector(process.getErrorStream());
        Thread stdoutThread = new Thread(stdoutCollector, "cpython-stdout-reader");
        Thread stderrThread = new Thread(stderrCollector, "cpython-stderr-reader");
        stdoutThread.start();
        stderrThread.start();
        int exit = process.waitFor();
        stdoutThread.join();
        stderrThread.join();
        String stdout = stdoutCollector.text().replace("\r\n", "\n");
        String stderr = stderrCollector.text().replace("\r\n", "\n");
        return new Oracle(exit, stdout, stderr);
    }

    private static List<String> findPython() {
        List<List<String>> candidates = Arrays.asList(
                Collections.singletonList("python"),
                Collections.singletonList("python3"),
                Arrays.asList("py", "-3"));
        for (List<String> candidate : candidates) {
            try {
                List<String> probe = new ArrayList<>(candidate);
                probe.add("-c");
                probe.add("import platform,sys; "
                        + "print(platform.python_implementation() + ':' "
                        + "+ str(sys.version_info[0]))");
                Process process = new ProcessBuilder(probe)
                        .redirectErrorStream(true)
                        .start();
                String identity = new String(
                        process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                        .trim();
                if (process.waitFor() == 0 && "CPython:3".equals(identity)) {
                    return Collections.unmodifiableList(new ArrayList<>(candidate));
                }
            } catch (IOException failure) {
                // Try the next common executable name.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static final class StreamCollector implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private IOException failure;

        private StreamCollector(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            try {
                input.transferTo(output);
            } catch (IOException exception) {
                failure = exception;
            }
        }

        private String text() throws IOException {
            if (failure != null) throw failure;
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static String diagnostics(FlaskCompilationResult compilation) {
        StringBuilder result = new StringBuilder();
        compilation.getReporter().diagnostics()
                .forEach(diagnostic -> result.append(diagnostic).append('\n'));
        return result.toString();
    }

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static void success(VmResult result) {
        check(result.isSuccess(), result.isFailure()
                ? result.getTraceback().format() : "VM execution failed");
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
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected <" + expected
                    + "> but was <" + actual + ">");
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class Oracle {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private Oracle(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        private String marker(String prefix) {
            for (String line : stdout.split("\n")) {
                if (line.startsWith(prefix)) return line.substring(prefix.length());
            }
            throw new AssertionError("CPython output has no marker " + prefix
                    + ": " + stdout);
        }

        private String stdoutWithoutMarkers() {
            StringBuilder result = new StringBuilder();
            String[] lines = stdout.split("\n", -1);
            for (int index = 0; index < lines.length; index++) {
                String line = lines[index];
                if (line.startsWith("__TOTAL__=")
                        || line.startsWith("__VALUES__=")) {
                    continue;
                }
                if (index < lines.length - 1) {
                    result.append(line).append('\n');
                } else {
                    result.append(line);
                }
            }
            return result.toString();
        }
    }
}
