import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.antlr_gen.FlaskLexer;
import compilers.flask.antlr_gen.FlaskParser;
import compilers.flask.ast.builder.ASTBuilder;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.generation.ContextExtractor;
import compilers.flask.generation.ProjectContext;
import compilers.flask.generation.RenderJob;
import compilers.flask.generation.RouteInfo;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Dependency-free tests for plan section 5 — Python context extraction. */
public final class ContextExtractionHarness {
    private static final String SOURCE_FILE = "app.py";
    private static int passed;
    private static int failed;

    private ContextExtractionHarness() {
    }

    public static void main(String[] args) {
        run("rubric app: globals, routes, and index context",
                ContextExtractionHarness::testRubricApp);
        run("data preparation subset evaluates correctly",
                ContextExtractionHarness::testDataPreparation);
        run("request-dependent view falls back to its GET view",
                ContextExtractionHarness::testGetViewFallback);
        run("url_for stays symbolic inside extracted context",
                ContextExtractionHarness::testSymbolicUrlFor);
        run("unsupported module statement becomes a diagnostic",
                ContextExtractionHarness::testUnsupportedModuleStatement);
        run("app.get/app.post and methods kwarg populate the route map",
                ContextExtractionHarness::testMethods);
        run("route without render_template renders no page",
                ContextExtractionHarness::testNoTemplateRoute);
        run("user-defined helper functions run during extraction",
                ContextExtractionHarness::testUserFunctions);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " extraction test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " context-extraction tests passed.");
    }

    private static void testRubricApp() {
        Extraction extraction = extract(lines(
                "from flask import Flask, render_template, url_for",
                "",
                "app = Flask(__name__)",
                "",
                "products = [",
                "    {\"name\": \"Phone\", \"price\": 300},",
                "    {\"name\": \"Laptop\", \"price\": 800}",
                "]",
                "",
                "@app.route(\"/\")",
                "def index():",
                "    return render_template(\"index.jinja\", products=products, title=\"Shop\")",
                "",
                "@app.route(\"/add\", methods=[\"GET\", \"POST\"])",
                "def add_product():",
                "    return render_template(\"add_product.jinja\")",
                "",
                "if __name__ == \"__main__\":",
                "    app.run(debug=True)"));
        check(!extraction.reporter.hasErrors(), diagnostics(extraction.reporter));

        ProjectContext context = extraction.context;
        check(context.getGlobals().containsKey("products"), "products global missing");
        List<?> products = (List<?>) context.getGlobals().get("products");
        equal(2, products.size(), "products length");
        Map<?, ?> phone = (Map<?, ?>) products.get(0);
        equal("Phone", phone.get("name"), "first product name");
        equal(BigInteger.valueOf(300), phone.get("price"), "first product price");

        equal(Arrays.asList("index", "add_product"),
                new ArrayList<>(context.getRoutes().keySet()), "route order");
        RouteInfo add = context.getRoutes().get("add_product");
        equal("/add", add.getPath(), "add path");
        equal(Arrays.asList("GET", "POST"), add.getMethods(), "add methods");

        equal(2, context.getRenderJobs().size(), "render job count");
        RenderJob index = context.getRenderJobs().get(0);
        equal("index.jinja", index.getTemplateName(), "index template");
        equal("index.html", index.getOutputFileName(), "index output name");
        check(index.getContext().get("products") == context.getGlobals().get("products"),
                "context must reference the same products list");
        equal("Shop", index.getContext().get("title"), "index title");
    }

    private static void testDataPreparation() {
        Extraction extraction = extract(lines(
                "from flask import Flask, render_template",
                "app = Flask(__name__)",
                "",
                "base = 10",
                "values = []",
                "for i in range(4):",
                "    if i % 2 == 0:",
                "        values.append(i * base)",
                "total = 0",
                "count = 0",
                "while count < len(values):",
                "    total += values[count]",
                "    count += 1",
                "label = f\"sum={total}\"",
                "settings = {\"debug\": True}",
                "settings[\"title\"] = \"Store\".upper()",
                "",
                "@app.route(\"/\")",
                "def index():",
                "    return render_template(\"index.jinja\", total=total, label=label,",
                "                           title=settings[\"title\"], values=values)"));
        check(!extraction.reporter.hasErrors(), diagnostics(extraction.reporter));

        RenderJob job = extraction.context.getRenderJobs().get(0);
        equal(BigInteger.valueOf(20), job.getContext().get("total"), "total 0+20");
        equal("sum=20", job.getContext().get("label"), "f-string label");
        equal("STORE", job.getContext().get("title"), "str.upper via subscript store");
        equal("[0, 20]", compilers.flask.generation.PyEval.pyRepr(
                job.getContext().get("values")), "values list");
    }

    private static void testGetViewFallback() {
        Extraction extraction = extract(lines(
                "from flask import Flask, render_template, request",
                "app = Flask(__name__)",
                "message = \"\"",
                "",
                "@app.route(\"/login\", methods=[\"GET\", \"POST\"])",
                "def login():",
                "    if request.method == \"POST\":",
                "        return render_template(\"login.jinja\", message=\"submitted\")",
                "    return render_template(\"login.jinja\", message=message)"));
        check(!extraction.reporter.hasErrors(), diagnostics(extraction.reporter));

        equal(1, extraction.context.getRenderJobs().size(), "one login job");
        RenderJob job = extraction.context.getRenderJobs().get(0);
        equal("login.jinja", job.getTemplateName(), "login template");
        equal("", job.getContext().get("message"), "GET view context value");
        check(joined(extraction.context.getLogLines()).contains("treated as False"),
                "lenient-condition warning must be logged");
    }

    private static void testSymbolicUrlFor() {
        Extraction extraction = extract(lines(
                "from flask import Flask, render_template, url_for",
                "app = Flask(__name__)",
                "",
                "@app.route(\"/\")",
                "def index():",
                "    home = url_for(\"index\")",
                "    return render_template(\"index.jinja\", home=home)"));
        check(!extraction.reporter.hasErrors(), diagnostics(extraction.reporter));
        Object home = extraction.context.getRenderJobs().get(0).getContext().get("home");
        check(home instanceof ProjectContext.UrlRef, "url_for must stay symbolic");
        equal("index", ((ProjectContext.UrlRef) home).getEndpoint(), "url endpoint");
    }

    private static void testUnsupportedModuleStatement() {
        Extraction extraction = extract(lines(
                "from flask import Flask",
                "app = Flask(__name__)",
                "with open(\"x\") as f:",
                "    data = f"));
        check(extraction.reporter.hasErrors(),
                "unsupported module statement must be a diagnostic");
        check(diagnostics(extraction.reporter).contains("Unsupported statement"),
                "diagnostic names the unsupported construct");
    }

    private static void testMethods() {
        Extraction extraction = extract(lines(
                "from flask import Flask, render_template",
                "app = Flask(__name__)",
                "",
                "@app.get(\"/ping\")",
                "def ping():",
                "    return render_template(\"ping.jinja\")",
                "",
                "@app.post(\"/submit\")",
                "def submit():",
                "    return render_template(\"submit.jinja\")"));
        check(!extraction.reporter.hasErrors(), diagnostics(extraction.reporter));
        equal(Arrays.asList("GET"),
                extraction.context.getRoutes().get("ping").getMethods(), "get methods");
        equal(Arrays.asList("POST"),
                extraction.context.getRoutes().get("submit").getMethods(), "post methods");
    }

    private static void testNoTemplateRoute() {
        Extraction extraction = extract(lines(
                "from flask import Flask",
                "app = Flask(__name__)",
                "",
                "@app.route(\"/count\")",
                "def count():",
                "    total = 0",
                "    for n in [1, 2, 3]:",
                "        total += n",
                "    return str(total)"));
        check(!extraction.reporter.hasErrors(), diagnostics(extraction.reporter));
        equal(0, extraction.context.getRenderJobs().size(), "no render jobs");
        check(joined(extraction.context.getLogLines()).contains("renders no template"),
                "skipped-route note must be logged");
    }

    private static void testUserFunctions() {
        Extraction extraction = extract(lines(
                "from flask import Flask, render_template",
                "app = Flask(__name__)",
                "",
                "def total(values, bonus=0):",
                "    result = 0",
                "    for value in values:",
                "        result += value",
                "    return result + bonus",
                "",
                "amount = total([5, 6, 7], bonus=2)",
                "",
                "@app.route(\"/\")",
                "def index():",
                "    return render_template(\"index.jinja\", amount=amount)"));
        check(!extraction.reporter.hasErrors(), diagnostics(extraction.reporter));
        equal(BigInteger.valueOf(20),
                extraction.context.getRenderJobs().get(0).getContext().get("amount"),
                "helper function result");
    }

    // ==================== plumbing ====================

    private static final class Extraction {
        final ProjectContext context;
        final DiagnosticReporter reporter;

        Extraction(ProjectContext context, DiagnosticReporter reporter) {
            this.context = context;
            this.reporter = reporter;
        }
    }

    private static Extraction extract(String source) {
        ProgramNode program = build(source);
        DiagnosticReporter reporter = new DiagnosticReporter();
        ProjectContext context =
                new ContextExtractor(reporter, SOURCE_FILE).extract(program);
        return new Extraction(context, reporter);
    }

    private static ProgramNode build(String source) {
        List<String> syntaxIssues = new ArrayList<>();
        FlaskLexer lexer = new FlaskLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new CollectingErrorListener("lexer", syntaxIssues));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        FlaskParser parser = new FlaskParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new CollectingErrorListener("parser", syntaxIssues));
        FlaskParser.ProgramContext tree = parser.program();
        check(syntaxIssues.isEmpty(), "syntax errors: " + syntaxIssues + "\n" + source);
        ASTNode root = new ASTBuilder(SOURCE_FILE).visit(tree);
        check(root instanceof ProgramNode, "AST root is not ProgramNode");
        return (ProgramNode) root;
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    private static String diagnostics(DiagnosticReporter reporter) {
        StringBuilder text = new StringBuilder();
        for (Diagnostic diagnostic : reporter.diagnostics()) {
            text.append(diagnostic).append('\n');
        }
        return text.toString();
    }

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
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
            throw new AssertionError(
                    label + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class CollectingErrorListener extends BaseErrorListener {
        private final String phase;
        private final List<String> issues;

        private CollectingErrorListener(String phase, List<String> issues) {
            this.phase = phase;
            this.issues = issues;
        }

        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String message,
                RecognitionException exception) {
            issues.add(phase + " " + line + ":" + charPositionInLine + " " + message);
        }
    }
}
