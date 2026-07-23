import Main.UnifiedMain;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Plan section 9 end-to-end gate: the rubric sample project turns into the
 * required output/ pages and compiler_output/ reports through the real CLI,
 * golden-compared; plus the frozen negative exit-code paths.
 */
public final class GenerationEndToEndHarness {
    private static final Path SAMPLE = Paths.get("Tests", "generation", "sample_project");
    private static final Path EXPECTED = Paths.get("Tests", "generation", "expected");
    private static int passed;
    private static int failed;

    private GenerationEndToEndHarness() {
    }

    public static void main(String[] args) throws Exception {
        run("sample project generates golden output pages",
                GenerationEndToEndHarness::testGoldenPages);
        run("compiler_output artifacts match the rubric contract",
                GenerationEndToEndHarness::testReportArtifacts);
        run("support files are copied verbatim",
                GenerationEndToEndHarness::testSupportCopies);
        run("semantic error blocks generation with exit 1",
                GenerationEndToEndHarness::testSemanticErrorGate);
        run("unbalanced template fails with exit 2",
                GenerationEndToEndHarness::testStructuralTemplateGate);
        run("unknown url_for endpoint warns but still writes the page",
                GenerationEndToEndHarness::testUnknownEndpointWarns);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " end-to-end test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " generation E2E tests passed.");
    }

    // ==================== positive path ====================

    private static void testGoldenPages() throws Exception {
        Invocation run = generateSample();
        equal(0, run.exitCode, "exit code (stderr: " + run.err + ")");
        for (String page : Arrays.asList(
                "index.html", "add_product.html", "edit_product.html")) {
            String actual = normalize(Files.readString(
                    run.out.resolve(page), StandardCharsets.UTF_8));
            String expected = normalize(Files.readString(
                    EXPECTED.resolve("output").resolve(page), StandardCharsets.UTF_8));
            equal(expected, actual, "golden page " + page);
        }
    }

    private static void testReportArtifacts() throws Exception {
        Invocation run = generateSample();
        equal(0, run.exitCode, "exit code");

        String astPython = normalize(Files.readString(
                run.reports.resolve("ast_python.json"), StandardCharsets.UTF_8));
        equal(normalize(Files.readString(EXPECTED.resolve(
                "compiler_output/ast_python.json"), StandardCharsets.UTF_8)),
                astPython, "golden ast_python.json");
        check(astPython.startsWith("{\n  \"kind\": \"Program\""),
                "python AST json shape");

        String astJinja = normalize(Files.readString(
                run.reports.resolve("ast_jinja.json"), StandardCharsets.UTF_8));
        equal(normalize(Files.readString(EXPECTED.resolve(
                "compiler_output/ast_jinja.json"), StandardCharsets.UTF_8)),
                astJinja, "golden ast_jinja.json");
        for (String template : Arrays.asList(
                "index.jinja", "add_product.jinja", "edit_product.jinja", "base.html")) {
            check(astJinja.contains("\"" + template + "\""),
                    "ast_jinja.json must cover " + template);
        }

        String semantic = Files.readString(
                run.reports.resolve("semantic_report.txt"), StandardCharsets.UTF_8);
        check(semantic.contains("No errors."), "semantic report clean");

        // generation_log.txt is compared by SHAPE (plan section 9): prefixes
        // in pipeline order, one render line per page, and the done summary.
        List<String> log = Files.readAllLines(
                run.reports.resolve("generation_log.txt"), StandardCharsets.UTF_8);
        int parse = indexOfPrefix(log, "[parse]", 0);
        int semanticLine = indexOfPrefix(log, "[semantic]", parse);
        int extract = indexOfPrefix(log, "[extract]", semanticLine);
        int render = indexOfPrefix(log, "[render]", extract);
        int copy = indexOfPrefix(log, "[copy]", render);
        int done = indexOfPrefix(log, "[done]", copy);
        check(done > copy && copy > render && render > extract
                        && extract > semanticLine && semanticLine > parse,
                "log prefixes must appear in pipeline order: " + log);
        for (String page : Arrays.asList(
                "index.html", "add_product.html", "edit_product.html")) {
            check(String.join("\n", log).contains("output/" + page),
                    "log must mention " + page);
        }
        check(log.get(done).contains("3 page(s) generated"),
                "done line counts pages: " + log.get(done));
    }

    private static void testSupportCopies() throws Exception {
        Invocation run = generateSample();
        for (String name : Arrays.asList("app.py", "style.css", "script.js")) {
            byte[] original = Files.readAllBytes(SAMPLE.resolve(name));
            byte[] copy = Files.readAllBytes(run.out.resolve(name));
            check(Arrays.equals(original, copy),
                    name + " must be copied byte-for-byte");
        }
        check(Files.isRegularFile(run.out.resolve("templates/base.html")),
                "templates/ folder must be copied");
    }

    // ==================== negative paths ====================

    private static void testSemanticErrorGate() throws Exception {
        Path project = tempProject(
                "from flask import Flask, render_template\n"
                        + "app = Flask(__name__)\n"
                        + "value = undefined_name\n",
                "page.jinja", "<p>{{ value }}</p>");
        Invocation run = invoke(project);
        equal(1, run.exitCode, "semantic gate exit code");
        check(run.err.contains("undefined_name"), "diagnostic names the symbol");
        check(!Files.exists(run.out.resolve("page.html")),
                "no page may be generated after a semantic error");
        check(Files.readString(run.reports.resolve("semantic_report.txt"))
                        .contains("Generation is blocked"),
                "semantic report explains the gate");
    }

    private static void testStructuralTemplateGate() throws Exception {
        Path project = tempProject(
                "from flask import Flask, render_template\n"
                        + "app = Flask(__name__)\n"
                        + "@app.route(\"/\")\n"
                        + "def index():\n"
                        + "    return render_template(\"broken.jinja\")\n",
                "broken.jinja",
                "{% for item in items %}\n<p>{{ item }}</p>\n{% endif %}\n");
        Invocation run = invoke(project);
        equal(2, run.exitCode, "structural template exit code");
        check(!Files.exists(run.out.resolve("broken.html")),
                "structurally broken page must not be written");
    }

    private static void testUnknownEndpointWarns() throws Exception {
        Path project = tempProject(
                "from flask import Flask, render_template\n"
                        + "app = Flask(__name__)\n"
                        + "@app.route(\"/\")\n"
                        + "def index():\n"
                        + "    return render_template(\"page.jinja\")\n",
                "page.jinja",
                "<a href=\"{{ url_for('nowhere') }}\">x</a>\n");
        Invocation run = invoke(project);
        equal(0, run.exitCode, "warnings must not fail the build");
        check(run.err.contains("nowhere"), "warning names the endpoint");
        String html = Files.readString(run.out.resolve("page.html"));
        check(html.contains("<a href=\"\">x</a>"),
                "page still written with an empty substituted value: " + html);
    }

    // ==================== plumbing ====================

    private static final class Invocation {
        final int exitCode;
        final Path out;
        final Path reports;
        final String stdout;
        final String err;

        Invocation(int exitCode, Path out, Path reports, String stdout, String err) {
            this.exitCode = exitCode;
            this.out = out;
            this.reports = reports;
            this.stdout = stdout;
            this.err = err;
        }
    }

    private static Invocation generateSample() throws Exception {
        return invoke(SAMPLE);
    }

    private static Invocation invoke(Path project) throws Exception {
        Path work = Files.createTempDirectory("gen_e2e_");
        work.toFile().deleteOnExit();
        Path out = work.resolve("output");
        Path reports = work.resolve("compiler_output");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = UnifiedMain.run(
                new String[] {
                        project.toString(),
                        "--out", out.toString(),
                        "--reports", reports.toString(),
                        "--quiet"
                },
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Invocation(exitCode, out, reports,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static Path tempProject(
            String appPy, String templateName, String templateBody) throws Exception {
        Path project = Files.createTempDirectory("gen_project_");
        project.toFile().deleteOnExit();
        Files.writeString(project.resolve("app.py"), appPy, StandardCharsets.UTF_8);
        Path templates = project.resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve(templateName), templateBody,
                StandardCharsets.UTF_8);
        return project;
    }

    private static int indexOfPrefix(List<String> lines, String prefix, int from) {
        for (int index = Math.max(from, 0); index < lines.size(); index++) {
            if (lines.get(index).startsWith(prefix)) {
                return index;
            }
        }
        return -1;
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
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
            throw new AssertionError(label + ":\n--- expected ---\n" + expected
                    + "\n--- actual ---\n" + actual + "\n---");
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
