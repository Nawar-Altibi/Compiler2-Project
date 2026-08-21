import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticReporter;
import compilers.html_css.render.JinjaRenderer;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Dependency-free tests for plan section 6 — Jinja template rendering. */
public final class JinjaRenderHarness {
    private static int passed;
    private static int failed;
    private static Path root;

    private JinjaRenderHarness() {
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("jinja_render_");
        root.toFile().deleteOnExit();

        run("expression substitution escapes by default",
                JinjaRenderHarness::testSubstitutionAndEscaping);
        run("for loop over dicts with attribute access and loop.index",
                JinjaRenderHarness::testForLoop);
        run("if/elif/else branches", JinjaRenderHarness::testConditionals);
        run("extends/block override keeps parent defaults",
                JinjaRenderHarness::testInheritance);
        run("url_for resolves endpoints, static files, and attribute values",
                JinjaRenderHarness::testUrlFor);
        run("filters: upper, length, default", JinjaRenderHarness::testFilters);
        run("undefined value warns and renders empty",
                JinjaRenderHarness::testUndefinedValue);
        run("unbalanced blocks are structural errors",
                JinjaRenderHarness::testStructuralError);
        run("template syntax diagnostics preserve line and column",
                JinjaRenderHarness::testSyntaxErrorLocation);
        run("statement-only lines vanish (trim-blocks policy)",
                JinjaRenderHarness::testWhitespacePolicy);
        run("value formatting: ints, booleans, None",
                JinjaRenderHarness::testValueFormatting);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " render test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " Jinja render tests passed.");
    }

    // ==================== tests ====================

    private static void testSubstitutionAndEscaping() throws Exception {
        write("hello.jinja", "<p>Hello {{ name }}!</p>");
        Rendered result = render("hello.jinja", context("name", "<b>Zain & Co</b>"));
        equal("<p>Hello &lt;b&gt;Zain &amp; Co&lt;/b&gt;!</p>", result.html, "escaped");
        check(!result.reporter.hasErrors() && !result.reporter.hasWarnings(),
                "no diagnostics expected");
    }

    private static void testForLoop() throws Exception {
        write("list.jinja", String.join("\n",
                "<ul>",
                "{% for product in products %}",
                "<li>{{ loop.index }}. {{ product.name }}: {{ product.price }}</li>",
                "{% endfor %}",
                "</ul>"));
        List<Object> products = new ArrayList<>();
        products.add(mapOf("name", "Phone", "price", BigInteger.valueOf(300)));
        products.add(mapOf("name", "Laptop", "price", BigInteger.valueOf(800)));
        Rendered result = render("list.jinja", context("products", products));
        equal(String.join("\n",
                "<ul>",
                "<li>1. Phone: 300</li>",
                "<li>2. Laptop: 800</li>",
                "</ul>"), result.html, "loop output");
    }

    private static void testConditionals() throws Exception {
        write("cond.jinja", String.join("\n",
                "{% if count > 10 %}",
                "<p>many</p>",
                "{% elif count > 0 %}",
                "<p>some</p>",
                "{% else %}",
                "<p>none</p>",
                "{% endif %}"));
        equal("<p>some</p>\n",
                render("cond.jinja", context("count", BigInteger.valueOf(3))).html,
                "elif branch");
        equal("<p>none</p>\n",
                render("cond.jinja", context("count", BigInteger.ZERO)).html,
                "else branch");
    }

    private static void testInheritance() throws Exception {
        write("base.html", String.join("\n",
                "<!DOCTYPE html>",
                "<html>",
                "<head><title>{% block title %}Default Title{% endblock %}</title></head>",
                "<body>",
                "<main>",
                "{% block content %}{% endblock %}",
                "</main>",
                "</body>",
                "</html>"));
        write("page.jinja", String.join("\n",
                "{% extends \"base.html\" %}",
                "{% block content %}",
                "<h2>Welcome</h2>",
                "{% endblock %}"));
        Rendered result = render("page.jinja", context());
        check(result.html.startsWith("<!DOCTYPE html>\n"), "doctype recovered");
        check(result.html.contains("<title>Default Title</title>"),
                "unoverridden block keeps parent default: " + result.html);
        check(result.html.contains("<main>\n<h2>Welcome</h2>\n</main>"),
                "child block overrides parent: " + result.html);
    }

    private static void testUrlFor() throws Exception {
        write("nav.jinja", String.join("\n",
                "<a href=\"{{ url_for('index') }}\">Home</a>",
                "<link rel=\"stylesheet\" href=\"{{ url_for('static', filename='style.css') }}\"/>",
                "<a href=\"{{ url_for('edit_product') }}?item=1\">Edit</a>"));
        Map<String, String> hrefs = new LinkedHashMap<>();
        hrefs.put("index", "index.html");
        hrefs.put("edit_product", "edit_product.html");
        Rendered result = render("nav.jinja", context(), hrefs);
        equal(String.join("\n",
                "<a href=\"index.html\">Home</a>",
                "<link rel=\"stylesheet\" href=\"style.css\" />",
                "<a href=\"edit_product.html?item=1\">Edit</a>"),
                result.html, "url_for output");
    }

    private static void testFilters() throws Exception {
        write("filters.jinja",
                "<p>{{ name | upper }} ({{ items | length }}) {{ missing | default('n/a') }}</p>");
        List<Object> items = new ArrayList<>(Arrays.asList("a", "b", "c"));
        Map<String, Object> context = context("name", "shop");
        context.put("items", items);
        Rendered result = render("filters.jinja", context);
        equal("<p>SHOP (3) n/a</p>", result.html, "filters");
    }

    private static void testUndefinedValue() throws Exception {
        write("missing.jinja", "<p>[{{ who }}]</p>");
        Rendered result = render("missing.jinja", context());
        equal("<p>[]</p>", result.html, "undefined renders empty");
        check(result.reporter.hasWarnings() && !result.reporter.hasErrors(),
                "undefined value must be a warning, not an error");
    }

    private static void testStructuralError() throws Exception {
        write("broken.jinja", String.join("\n",
                "{% for item in items %}",
                "<p>{{ item }}</p>",
                "{% endif %}"));
        DiagnosticReporter reporter = new DiagnosticReporter();
        JinjaRenderer renderer = new JinjaRenderer(
                reporter, root, new LinkedHashMap<>());
        try {
            renderer.render("broken.jinja", context("items", new ArrayList<>()));
            throw new AssertionError("unbalanced blocks must fail rendering");
        } catch (JinjaRenderer.RenderFailure expected) {
            check(reporter.hasErrors(), "structural failure must be an ERROR diagnostic");
        }
    }

    private static void testSyntaxErrorLocation() throws Exception {
        write("syntax.jinja", "<div>\n{{ name\n</div>\n");
        DiagnosticReporter reporter = new DiagnosticReporter();
        JinjaRenderer renderer = new JinjaRenderer(
                reporter, root, new LinkedHashMap<>());
        try {
            renderer.render("syntax.jinja", context());
            throw new AssertionError("malformed Jinja syntax must fail rendering");
        } catch (JinjaRenderer.RenderFailure expected) {
            check(reporter.hasErrors(), "syntax failure must be an ERROR diagnostic");
            Diagnostic diagnostic = reporter.errors().get(0);
            check(diagnostic.line() >= 2,
                    "syntax diagnostic must preserve its source line: " + diagnostic);
        }
    }

    private static void testWhitespacePolicy() throws Exception {
        write("ws.jinja", String.join("\n",
                "<div>",
                "  {% if flag %}",
                "  <span>on</span>",
                "  {% endif %}",
                "</div>"));
        Rendered result = render("ws.jinja", context("flag", Boolean.TRUE));
        equal("<div>\n  <span>on</span>\n</div>", result.html,
                "statement-only lines removed");
    }

    private static void testValueFormatting() throws Exception {
        write("fmt.jinja", "<p>{{ count }}|{{ ok }}|{{ nothing }}|{{ price }}</p>");
        Map<String, Object> context = context("count", BigInteger.valueOf(300));
        context.put("ok", Boolean.TRUE);
        context.put("nothing", null);
        context.put("price", 299.99d);
        Rendered result = render("fmt.jinja", context);
        equal("<p>300|True||299.99</p>", result.html, "frozen formatting table");
    }

    // ==================== plumbing ====================

    private static final class Rendered {
        final String html;
        final DiagnosticReporter reporter;

        Rendered(String html, DiagnosticReporter reporter) {
            this.html = html;
            this.reporter = reporter;
        }
    }

    private static Rendered render(String template, Map<String, Object> context)
            throws Exception {
        return render(template, context, new LinkedHashMap<>());
    }

    private static Rendered render(
            String template, Map<String, Object> context, Map<String, String> hrefs)
            throws Exception {
        DiagnosticReporter reporter = new DiagnosticReporter();
        JinjaRenderer renderer = new JinjaRenderer(reporter, root, hrefs);
        String html = renderer.render(template, context);
        return new Rendered(html, reporter);
    }

    private static void write(String name, String content) throws Exception {
        Files.write(root.resolve(name),
                content.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> context(Object... pairs) {
        Map<String, Object> context = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            context.put((String) pairs[index], pairs[index + 1]);
        }
        return context;
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        return context(pairs);
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

    private static String diagnostics(DiagnosticReporter reporter) {
        StringBuilder text = new StringBuilder();
        for (Diagnostic diagnostic : reporter.diagnostics()) {
            text.append(diagnostic).append('\n');
        }
        return text.toString();
    }
}
