import compilers.pipeline.GenerationPipeline;
import compilers.pipeline.ProjectWatcher;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Integration test: saving app.py reruns the full pipeline and refreshes HTML. */
public final class ProjectWatcherHarness {
    private static int passed;
    private static int failed;

    private ProjectWatcherHarness() {
    }

    public static void main(String[] args) {
        run("app.py change regenerates HTML from the Python lexer",
                ProjectWatcherHarness::testPythonChangeRegeneratesHtml);
        run("template, CSS, and asset changes regenerate without output loops",
                ProjectWatcherHarness::testProjectSourcesRegenerate);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " watcher test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " project-watcher tests passed.");
    }

    private static void testPythonChangeRegeneratesHtml() throws Exception {
        Path project = Files.createTempDirectory("watch_project_");
        project.toFile().deleteOnExit();
        Path appPy = project.resolve("app.py");
        writeApp(appPy, 300);
        Path templates = project.resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("index.jinja"),
                "<p id=\"price\">{{ price }}</p>\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
        PrintStream stdout = new PrintStream(
                stdoutBytes, true, StandardCharsets.UTF_8);
        PrintStream stderr = new PrintStream(
                stderrBytes, true, StandardCharsets.UTF_8);
        GenerationPipeline.ProjectPaths paths =
                GenerationPipeline.ProjectPaths.resolve(project, null, null);
        ProjectWatcher watcher = new ProjectWatcher(paths, stdout, stderr);
        AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                watcher.watch();
            } catch (Throwable failure) {
                backgroundFailure.set(failure);
            }
        }, "project-watcher-test");
        thread.start();

        try {
            Path generatedPage = project.resolve("output/index.html");
            waitUntil(() -> pageContains(generatedPage, ">300<"),
                    "initial watcher build did not generate price 300");

            writeApp(appPy, 975);
            waitUntil(() -> pageContains(generatedPage, ">975<"),
                    "saving app.py did not regenerate price 975");
            waitUntil(() -> output(stdoutBytes).contains(
                            "PYTHON SOURCE CHANGE DETECTED")
                            && output(stdoutBytes).contains("Run #2 - AUTOMATIC REBUILD")
                            && output(stdoutBytes).contains(
                            "Restart point: Flask/Python Lexer")
                            && output(stdoutBytes).contains("RESULT: SUCCESS"),
                    "watcher presentation messages are incomplete");

            String console = output(stdoutBytes);
            check(console.contains("index.html"),
                    "success summary must name the generated page");
            equal("", output(stderrBytes), "watcher stderr");
        } finally {
            watcher.close();
            thread.join(3000L);
        }

        check(!thread.isAlive(), "watcher thread must stop after close()");
        if (backgroundFailure.get() != null) {
            throw new AssertionError("watcher background failure",
                    backgroundFailure.get());
        }
    }

    private static void testProjectSourcesRegenerate() throws Exception {
        Path project = Files.createTempDirectory("watch_sources_");
        project.toFile().deleteOnExit();
        Files.writeString(project.resolve("app.py"),
                "from flask import Flask, render_template\n"
                        + "app = Flask(__name__)\n"
                        + "@app.route(\"/\")\n"
                        + "def index():\n"
                        + "    return render_template(\"index.jinja\")\n",
                StandardCharsets.UTF_8);
        Path templates = project.resolve("templates");
        Files.createDirectories(templates);
        Path template = templates.resolve("index.jinja");
        Files.writeString(template, "<h1>Version one</h1>\n", StandardCharsets.UTF_8);
        Path stylesheet = project.resolve("style.css");
        Files.writeString(stylesheet, "body { color: black; }\n", StandardCharsets.UTF_8);

        ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
        PrintStream stdout = new PrintStream(
                stdoutBytes, true, StandardCharsets.UTF_8);
        PrintStream stderr = new PrintStream(
                stderrBytes, true, StandardCharsets.UTF_8);
        GenerationPipeline.ProjectPaths paths =
                GenerationPipeline.ProjectPaths.resolve(project, null, null);
        ProjectWatcher watcher = new ProjectWatcher(paths, stdout, stderr);
        AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
        Thread thread = watcherThread(watcher, backgroundFailure);
        thread.start();

        try {
            Path output = project.resolve("output");
            Path page = output.resolve("index.html");
            waitUntil(() -> pageContains(page, "Version one"),
                    "initial template was not generated");
            Thread.sleep(700L);
            equal(0, occurrences(output(stdoutBytes), "AUTOMATIC REBUILD"),
                    "generated output must not trigger the watcher");

            Files.writeString(template, "<h1>Version two</h1>\n",
                    StandardCharsets.UTF_8);
            waitUntil(() -> pageContains(page, "Version two"),
                    "Jinja change did not regenerate HTML");

            Files.writeString(stylesheet, "body { color: green; }\n",
                    StandardCharsets.UTF_8);
            waitUntil(() -> pageContains(output.resolve("style.css"), "green"),
                    "CSS change was not copied to output");

            Path assets = project.resolve("assets/icons");
            Files.createDirectories(assets);
            Files.writeString(assets.resolve("logo.svg"),
                    "<svg><title>Compiler logo</title></svg>\n",
                    StandardCharsets.UTF_8);
            waitUntil(() -> pageContains(
                            output.resolve("assets/icons/logo.svg"), "Compiler logo"),
                    "new asset was not copied recursively");
            waitUntil(() -> output(stdoutBytes).contains(
                            "PROJECT SOURCE CHANGE DETECTED")
                            && output(stdoutBytes).contains("templates")
                            && output(stdoutBytes).contains("style.css")
                            && output(stdoutBytes).contains("assets"),
                    "watcher did not report all project-source changes");
            equal("", output(stderrBytes), "project-source watcher stderr");
        } finally {
            watcher.close();
            thread.join(3000L);
        }

        check(!thread.isAlive(), "project-source watcher thread must stop");
        if (backgroundFailure.get() != null) {
            throw new AssertionError("project-source watcher background failure",
                    backgroundFailure.get());
        }
    }

    private static Thread watcherThread(
            ProjectWatcher watcher, AtomicReference<Throwable> backgroundFailure) {
        return new Thread(() -> {
            try {
                watcher.watch();
            } catch (Throwable failure) {
                backgroundFailure.set(failure);
            }
        }, "project-watcher-test");
    }

    private static int occurrences(String text, String target) {
        int count = 0;
        int position = 0;
        while ((position = text.indexOf(target, position)) >= 0) {
            count++;
            position += target.length();
        }
        return count;
    }

    private static void writeApp(Path appPy, int price) throws Exception {
        Files.writeString(appPy,
                "from flask import Flask, render_template\n"
                        + "app = Flask(__name__)\n"
                        + "price = " + price + "\n"
                        + "@app.route(\"/\")\n"
                        + "def index():\n"
                        + "    return render_template(\"index.jinja\", price=price)\n",
                StandardCharsets.UTF_8);
    }

    private static boolean pageContains(Path page, String expected) throws Exception {
        return Files.isRegularFile(page)
                && Files.readString(page, StandardCharsets.UTF_8).contains(expected);
    }

    private static String output(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void waitUntil(CheckedCondition condition, String failureMessage)
            throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.test()) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError(failureMessage);
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
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected <" + expected
                    + "> but was <" + actual + ">");
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean test() throws Exception;
    }
}
