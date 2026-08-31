import compilers.pipeline.GenerationPipeline;
import compilers.pipeline.GenerationResult;
import compilers.pipeline.ProjectWatcher;
import compilers.server.CompilerWebServer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Real HTTP round-trip tests for persistent app.py CRUD and watcher rebuilds. */
public final class CompilerWebServerHarness {
    private static int passed;
    private static int failed;

    private CompilerWebServerHarness() {
    }

    public static void main(String[] args) {
        run("POST persists a row in app.py and survives recompilation",
                CompilerWebServerHarness::testLiveProductAddition);
        run("GET, PUT, and DELETE persist through watcher rebuilds",
                CompilerWebServerHarness::testReadUpdateDelete);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " web-server test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " compiler-web-server tests passed.");
    }

    private static void testLiveProductAddition() throws Exception {
        Path project = createProject();
        Path appPy = project.resolve("app.py");
        String originalPython = Files.readString(appPy, StandardCharsets.UTF_8);
        GenerationPipeline.ProjectPaths paths =
                GenerationPipeline.ProjectPaths.resolve(project, null, null);

        ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
        PrintStream stdout = new PrintStream(
                stdoutBytes, true, StandardCharsets.UTF_8);
        PrintStream stderr = new PrintStream(
                stderrBytes, true, StandardCharsets.UTF_8);

        CompilerWebServer server = new CompilerWebServer(paths, 0, stdout, stderr);
        try (server;
             RunningWatcher watcher = new RunningWatcher(
                     paths, stdout, stderr, server)) {
            server.start();
            watcher.start();
            waitUntil(() -> pageContains(
                            paths.outputDir.resolve("index.html"), "Phone"),
                    "initial watcher build did not generate the product page");
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            String baseUrl = server.getBaseUrl();

            HttpResponse<String> initial = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/index.html"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            equal(200, initial.statusCode(), "initial GET status");
            check(initial.body().contains("1 products")
                            && initial.body().contains("Phone"),
                    "initial page must contain the compiler snapshot");

            HttpResponse<String> post = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/products"))
                            .header("Content-Type",
                                    "application/x-www-form-urlencoded; charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "name=Keyboard&price=99.5&description=Mechanical+keyboard",
                                    StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            equal(303, post.statusCode(), "POST redirect status");
            equal("/index.html",
                    post.headers().firstValue("Location").orElse(""),
                    "POST redirect location");

            HttpResponse<String> updated = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/index.html"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            equal(200, updated.statusCode(), "updated GET status");
            check(updated.body().contains("2 products")
                            && updated.body().contains("Keyboard")
                            && updated.body().contains("Mechanical keyboard")
                            && updated.body().contains("$99.5"),
                    "updated page must contain the persisted product: " + updated.body());
        }

        String persistedPython = Files.readString(appPy, StandardCharsets.UTF_8);
        check(!originalPython.equals(persistedPython),
                "POST must change app.py");
        check(persistedPython.contains("Keyboard")
                        && persistedPython.contains("def index():")
                        && persistedPython.contains("comment must survive"),
                "POST must persist the row without damaging surrounding Python");

        GenerationResult restarted = new GenerationPipeline().run(paths);
        equal(0, restarted.getExitCode(), "compiler exit code after restart");
        String restartedPage = Files.readString(
                paths.outputDir.resolve("index.html"), StandardCharsets.UTF_8);
        check(restartedPage.contains("2 products")
                        && restartedPage.contains("Keyboard"),
                "persisted POST must survive a fresh compiler run");

        String console = stdoutBytes.toString(StandardCharsets.UTF_8);
        check(console.contains("POST /api/products")
                        && console.contains("app.py saved successfully")
                        && console.contains("PYTHON SOURCE CHANGE DETECTED")
                        && console.contains("Watcher rebuild confirmed")
                        && console.contains("Redirecting browser to /index.html"),
                "server must print committee-friendly POST messages");
        equal("", stderrBytes.toString(StandardCharsets.UTF_8), "server stderr");
    }

    private static void testReadUpdateDelete() throws Exception {
        Path project = createProject();
        Path appPy = project.resolve("app.py");
        String originalPython = Files.readString(appPy, StandardCharsets.UTF_8);
        GenerationPipeline.ProjectPaths paths =
                GenerationPipeline.ProjectPaths.resolve(project, null, null);

        ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
        PrintStream stdout = new PrintStream(
                stdoutBytes, true, StandardCharsets.UTF_8);
        PrintStream stderr = new PrintStream(
                stderrBytes, true, StandardCharsets.UTF_8);

        CompilerWebServer server = new CompilerWebServer(paths, 0, stdout, stderr);
        try (server;
             RunningWatcher watcher = new RunningWatcher(
                     paths, stdout, stderr, server)) {
            server.start();
            watcher.start();
            waitUntil(() -> pageContains(
                            paths.outputDir.resolve("index.html"), "Phone"),
                    "initial watcher build did not generate the product page");
            HttpClient client = HttpClient.newHttpClient();
            String api = server.getBaseUrl() + "/api/products";

            HttpResponse<String> all = client.send(
                    HttpRequest.newBuilder(URI.create(api)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            equal(200, all.statusCode(), "collection GET status");
            check(all.body().contains("\"id\":1")
                            && all.body().contains("\"name\":\"Phone\""),
                    "collection GET must return JSON items: " + all.body());

            HttpResponse<String> one = client.send(
                    HttpRequest.newBuilder(URI.create(api + "/1")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            equal(200, one.statusCode(), "item GET status");
            check(one.body().contains("\"description\":\"Reliable phone\""),
                    "item GET must return the selected product: " + one.body());

            HttpResponse<String> put = client.send(
                    HttpRequest.newBuilder(URI.create(api + "/1"))
                            .header("Content-Type",
                                    "application/x-www-form-urlencoded; charset=UTF-8")
                            .PUT(HttpRequest.BodyPublishers.ofString(
                                    "name=Updated+Phone&price=425&description=Updated+description",
                                    StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            equal(204, put.statusCode(), "PUT status");

            HttpResponse<String> updatedPage = client.send(
                    HttpRequest.newBuilder(URI.create(
                                    server.getBaseUrl() + "/index.html"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            check(updatedPage.body().contains("Updated Phone")
                            && updatedPage.body().contains("Updated description")
                            && updatedPage.body().contains("$425"),
                    "PUT must rerender the generated page: " + updatedPage.body());

            HttpResponse<String> delete = client.send(
                    HttpRequest.newBuilder(URI.create(api + "/1"))
                            .DELETE().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            equal(204, delete.statusCode(), "DELETE status");

            HttpResponse<String> missing = client.send(
                    HttpRequest.newBuilder(URI.create(api + "/1")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            equal(404, missing.statusCode(), "deleted item GET status");

            HttpResponse<String> emptyPage = client.send(
                    HttpRequest.newBuilder(URI.create(
                                    server.getBaseUrl() + "/index.html"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            check(emptyPage.body().contains("0 products")
                            && !emptyPage.body().contains("Updated Phone"),
                    "DELETE must rerender without the removed product");
        }

        String persistedPython = Files.readString(appPy, StandardCharsets.UTF_8);
        check(!originalPython.equals(persistedPython),
                "PUT and DELETE must change app.py");
        check(persistedPython.contains("products = []")
                        && !persistedPython.contains("Updated Phone")
                        && persistedPython.contains("comment must survive"),
                "DELETE must persist an empty collection and preserve surrounding source");

        GenerationResult restarted = new GenerationPipeline().run(paths);
        equal(0, restarted.getExitCode(), "compiler exit code after CRUD restart");
        String restartedPage = Files.readString(
                paths.outputDir.resolve("index.html"), StandardCharsets.UTF_8);
        check(restartedPage.contains("0 products")
                        && !restartedPage.contains("Updated Phone"),
                "PUT/DELETE result must survive a fresh compiler run");

        String console = stdoutBytes.toString(StandardCharsets.UTF_8);
        check(console.contains("PUT /api/products/1")
                        && console.contains("DELETE /api/products/1")
                        && console.contains("Updated item 1 persistently")
                        && console.contains("Deleted item 1 persistently")
                        && occurrences(console, "PYTHON SOURCE CHANGE DETECTED") >= 2,
                "server must print committee-friendly update/delete messages");
        equal("", stderrBytes.toString(StandardCharsets.UTF_8), "server stderr");
    }

    private static Path createProject() throws Exception {
        Path project = Files.createTempDirectory("compiler_server_");
        project.toFile().deleteOnExit();
        Files.writeString(project.resolve("app.py"),
                "from flask import Flask, render_template\n"
                        + "app = Flask(__name__)\n"
                        + "products = [\n"
                        + "    {\"id\": 1, \"name\": \"Phone\", \"price\": 300, "
                        + "\"description\": \"Reliable phone\"}\n"
                        + "]\n"
                        + "# This comment must survive CRUD source updates\n"
                        + "@app.route(\"/\")\n"
                        + "def index():\n"
                        + "    return render_template(\"index.jinja\", "
                        + "products=products)\n"
                        + "@app.route(\"/add\")\n"
                        + "def add_product():\n"
                        + "    return render_template(\"add.jinja\")\n",
                StandardCharsets.UTF_8);

        Path templates = project.resolve("templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("index.jinja"),
                "<h1>{{ products|length }} products</h1>\n"
                        + "{% for product in products %}\n"
                        + "<article><b>{{ product.name }}</b>"
                        + "<span>{{ product.description }}</span>"
                        + "<i>${{ product.price }}</i></article>\n"
                        + "{% endfor %}\n",
                StandardCharsets.UTF_8);
        Files.writeString(templates.resolve("add.jinja"),
                "<form method=\"POST\" action=\"/api/products\">"
                        + "<input name=\"name\"/>"
                        + "<input name=\"price\"/>"
                        + "<input name=\"description\"/>"
                        + "</form>\n",
                StandardCharsets.UTF_8);
        return project;
    }

    private static boolean pageContains(Path page, String expected) throws Exception {
        return Files.isRegularFile(page)
                && Files.readString(page, StandardCharsets.UTF_8).contains(expected);
    }

    private static void waitUntil(CheckedCondition condition, String failureMessage)
            throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.test()) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError(failureMessage);
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

    private static final class RunningWatcher implements AutoCloseable {
        private final ProjectWatcher watcher;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        RunningWatcher(
                GenerationPipeline.ProjectPaths paths,
                PrintStream stdout,
                PrintStream stderr,
                CompilerWebServer server) throws Exception {
            watcher = new ProjectWatcher(paths, stdout, stderr, server::acceptCompilation);
            thread = new Thread(() -> {
                try {
                    watcher.watch();
                } catch (Throwable backgroundFailure) {
                    failure.set(backgroundFailure);
                }
            }, "compiler-web-server-watcher-test");
        }

        void start() {
            thread.start();
        }

        @Override
        public void close() throws Exception {
            watcher.close();
            thread.join(3_000L);
            check(!thread.isAlive(), "watcher thread must stop after close()");
            if (failure.get() != null) {
                throw new AssertionError("watcher background failure", failure.get());
            }
        }
    }
}
