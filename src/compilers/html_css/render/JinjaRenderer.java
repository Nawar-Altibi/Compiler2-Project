package compilers.html_css.render;

import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.html_css.Visitor.HtmlAstBuilder;
import compilers.html_css.antlr.HtmlCssLexer;
import compilers.html_css.antlr.HtmlCssParser;
import compilers.html_css.ast.HtmlNode;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The generation-stage template renderer (plan section 6): takes a plain
 * Java context + the parsed Jinja/HTML AST and produces the final HTML text.
 *
 * <p>Frozen policies: escape-by-default output (6.3.3), warn-and-continue
 * for value errors vs. fail for structural errors (6.3.4), url_for via the
 * route map (6.3.5), trim-blocks whitespace (6.3.6).</p>
 */
public final class JinjaRenderer {

    /** Structural rendering failure: the page must not be written. */
    public static final class RenderFailure extends RuntimeException {
        public RenderFailure(String message) {
            super(message);
        }
    }

    private final DiagnosticReporter reporter;
    private final Path templateRoot;
    private final Map<String, String> routeHrefs;
    private final Map<String, JinjaTemplateModel> modelCache = new LinkedHashMap<>();
    private final Map<String, HtmlNode> parsedRoots = new LinkedHashMap<>();
    private final List<String> log = new ArrayList<>();

    /**
     * @param routeHrefs plain-Java url_for target map: endpoint → href
     *                   (the section 6.4 boundary stays engine-type-free)
     */
    public JinjaRenderer(
            DiagnosticReporter reporter,
            Path templateRoot,
            Map<String, String> routeHrefs) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.templateRoot = Objects.requireNonNull(templateRoot, "templateRoot");
        this.routeHrefs = new LinkedHashMap<>(
                Objects.requireNonNull(routeHrefs, "routeHrefs"));
    }

    /** Raw template ASTs parsed so far (for {@code ast_jinja.json}). */
    public Map<String, HtmlNode> getParsedRoots() {
        return new LinkedHashMap<>(parsedRoots);
    }

    /** Ordered renderer log lines ({@code [render]} / {@code [warn]}). */
    public List<String> getLogLines() {
        return new ArrayList<>(log);
    }

    /** Renders one template with one context into final HTML. */
    public String render(String templateName, Map<String, Object> context) {
        JinjaTemplateModel model = load(templateName);
        List<JinjaTemplateModel> chain =
                TemplateInheritance.resolve(model, this::load);
        if (chain.size() > 1) {
            log.add("[render]    " + templateName + " extends "
                    + chain.get(chain.size() - 1).getTemplateName());
        }

        JinjaExprEvaluator evaluator = new JinjaExprEvaluator(this::resolveUrl);
        evaluator.pushFrame(new LinkedHashMap<>(context));

        StringBuilder html = new StringBuilder();
        String doctype = doctypeOf(chain);
        if (doctype != null) {
            html.append(doctype).append('\n');
        }
        RenderState state = new RenderState(chain, evaluator, templateName);
        renderNodes(TemplateInheritance.baseTemplate(chain).getRoot(), state, html);
        return html.toString();
    }

    private String doctypeOf(List<JinjaTemplateModel> chain) {
        // The base template's doctype wins (it owns the document skeleton).
        for (int index = chain.size() - 1; index >= 0; index--) {
            if (chain.get(index).getDoctypeLine() != null) {
                return chain.get(index).getDoctypeLine();
            }
        }
        return null;
    }

    // ==================== tree rendering ====================

    private static final class RenderState {
        final List<JinjaTemplateModel> chain;
        final JinjaExprEvaluator evaluator;
        final String templateName;

        RenderState(
                List<JinjaTemplateModel> chain,
                JinjaExprEvaluator evaluator,
                String templateName) {
            this.chain = chain;
            this.evaluator = evaluator;
            this.templateName = templateName;
        }
    }

    private void renderNodes(
            List<JinjaTemplateModel.Node> nodes, RenderState state, StringBuilder out) {
        for (JinjaTemplateModel.Node node : nodes) {
            renderNode(node, state, out);
        }
    }

    private void renderNode(
            JinjaTemplateModel.Node node, RenderState state, StringBuilder out) {
        if (node instanceof JinjaTemplateModel.Text) {
            out.append(((JinjaTemplateModel.Text) node).text);
            return;
        }
        if (node instanceof JinjaTemplateModel.Output) {
            JinjaTemplateModel.Output output = (JinjaTemplateModel.Output) node;
            try {
                Object value = state.evaluator.evaluate(
                        output.expression, output.line, output.column);
                out.append(JinjaExprEvaluator.escapeHtml(
                        JinjaExprEvaluator.toText(value)));
            } catch (JinjaExprEvaluator.JinjaEvalError failure) {
                warnValue(state, output.line, output.column, failure.getMessage());
            }
            return;
        }
        if (node instanceof JinjaTemplateModel.IfBlock) {
            renderIf((JinjaTemplateModel.IfBlock) node, state, out);
            return;
        }
        if (node instanceof JinjaTemplateModel.ForBlock) {
            renderFor((JinjaTemplateModel.ForBlock) node, state, out);
            return;
        }
        if (node instanceof JinjaTemplateModel.BlockDef) {
            JinjaTemplateModel.BlockDef declared = (JinjaTemplateModel.BlockDef) node;
            JinjaTemplateModel.BlockDef effective =
                    TemplateInheritance.lookupBlock(state.chain, declared.name);
            renderNodes((effective == null ? declared : effective).body, state, out);
            return;
        }
        throw new RenderFailure("Unknown template node " + node.getClass());
    }

    private void renderIf(
            JinjaTemplateModel.IfBlock conditional, RenderState state, StringBuilder out) {
        for (JinjaTemplateModel.IfBlock.Branch branch : conditional.branches) {
            boolean taken;
            if (branch.condition == null) {
                taken = true;
            } else {
                try {
                    taken = JinjaExprEvaluator.truth(state.evaluator.evaluate(
                            branch.condition, conditional.line, conditional.column));
                } catch (JinjaExprEvaluator.JinjaEvalError failure) {
                    warnValue(state, conditional.line, conditional.column,
                            failure.getMessage() + " (condition treated as false)");
                    taken = false;
                }
            }
            if (taken) {
                renderNodes(branch.body, state, out);
                return;
            }
        }
    }

    private void renderFor(
            JinjaTemplateModel.ForBlock loop, RenderState state, StringBuilder out) {
        List<?> items;
        try {
            Object iterable = state.evaluator.evaluate(
                    loop.iterableExpression, loop.line, loop.column);
            items = materialize(iterable);
        } catch (JinjaExprEvaluator.JinjaEvalError failure) {
            warnValue(state, loop.line, loop.column,
                    failure.getMessage() + " (loop skipped)");
            items = new ArrayList<>();
        }

        if (items.isEmpty()) {
            renderNodes(loop.elseBody, state, out);
            return;
        }
        for (int index = 0; index < items.size(); index++) {
            Map<String, Object> frame = new LinkedHashMap<>();
            bindLoopTargets(loop, items.get(index), frame, state);
            Map<String, Object> loopMeta = new LinkedHashMap<>();
            loopMeta.put("index", BigInteger.valueOf(index + 1));
            loopMeta.put("index0", BigInteger.valueOf(index));
            loopMeta.put("first", index == 0);
            loopMeta.put("last", index == items.size() - 1);
            loopMeta.put("length", BigInteger.valueOf(items.size()));
            frame.put("loop", loopMeta);

            state.evaluator.pushFrame(frame);
            try {
                renderNodes(loop.body, state, out);
            } finally {
                state.evaluator.popFrame();
            }
        }
    }

    private void bindLoopTargets(
            JinjaTemplateModel.ForBlock loop,
            Object item,
            Map<String, Object> frame,
            RenderState state) {
        if (loop.targets.size() == 1) {
            frame.put(loop.targets.get(0), item);
            return;
        }
        if (item instanceof List && ((List<?>) item).size() == loop.targets.size()) {
            List<?> parts = (List<?>) item;
            for (int index = 0; index < loop.targets.size(); index++) {
                frame.put(loop.targets.get(index), parts.get(index));
            }
            return;
        }
        warnValue(state, loop.line, loop.column,
                "Cannot unpack loop item into " + loop.targets);
        for (String target : loop.targets) {
            frame.put(target, null);
        }
    }

    private List<?> materialize(Object iterable) {
        if (iterable instanceof List) {
            return (List<?>) iterable;
        }
        if (iterable instanceof Map) {
            return new ArrayList<>(((Map<?, ?>) iterable).keySet());
        }
        if (iterable instanceof String) {
            List<Object> chars = new ArrayList<>();
            for (char c : ((String) iterable).toCharArray()) {
                chars.add(String.valueOf(c));
            }
            return chars;
        }
        throw new JinjaExprEvaluator.JinjaEvalError(
                "Value is not iterable: " + JinjaExprEvaluator.typeName(iterable));
    }

    private void warnValue(RenderState state, int line, int column, String message) {
        reporter.report(Diagnostics.jinjaValueWarning(
                message, line, column, state.templateName));
        log.add("[warn]      " + state.templateName + ":" + line + " " + message);
    }

    // ==================== url_for (frozen 6.3.5) ====================

    private String resolveUrl(
            String endpoint, Map<String, Object> params, int line, int column) {
        if ("static".equals(endpoint)) {
            Object filename = params.get("filename");
            return filename == null ? "" : JinjaExprEvaluator.toText(filename);
        }
        String href = routeHrefs.get(endpoint);
        if (href != null) {
            return href;
        }
        throw new JinjaExprEvaluator.JinjaEvalError(
                "url_for: unknown endpoint '" + endpoint + "'");
    }

    // ==================== loading & parsing ====================

    private JinjaTemplateModel load(String templateName) {
        JinjaTemplateModel cached = modelCache.get(templateName);
        if (cached != null) {
            return cached;
        }
        Path file = templateRoot.resolve(templateName);
        if (!Files.isRegularFile(file)) {
            throw new RenderFailure("Template not found: " + templateName
                    + " (expected at " + file + ")");
        }
        final String raw;
        try {
            raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new RenderFailure("Cannot read template " + templateName
                    + ": " + failure.getMessage());
        }

        // The HTML AST drops the DTD token, so recover the doctype line here.
        String doctype = null;
        String sniff = raw.stripLeading();
        if (sniff.regionMatches(true, 0, "<!doctype", 0, 9)) {
            int end = sniff.indexOf('>');
            if (end > 0) {
                doctype = sniff.substring(0, end + 1);
            }
        }

        HtmlNode astRoot = parseTemplate(templateName, raw);
        parsedRoots.put(templateName, astRoot);
        try {
            JinjaTemplateModel model =
                    JinjaTemplateModel.parse(templateName, astRoot, doctype);
            modelCache.put(templateName, model);
            return model;
        } catch (JinjaTemplateModel.TemplateStructureException failure) {
            reporter.report(Diagnostics.templateStructureError(
                    failure.getMessage(), failure.line, failure.column, templateName));
            throw new RenderFailure(templateName + ": " + failure.getMessage());
        }
    }

    private HtmlNode parseTemplate(String templateName, String raw) {
        SyntaxErrorCollector syntaxErrors = new SyntaxErrorCollector();
        HtmlCssLexer lexer = new HtmlCssLexer(CharStreams.fromString(raw));
        lexer.removeErrorListeners();
        lexer.addErrorListener(syntaxErrors);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        HtmlCssParser parser = new HtmlCssParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(syntaxErrors);
        HtmlCssParser.HtmlDocumentContext tree = parser.htmlDocument();
        if (!syntaxErrors.issues.isEmpty()) {
            for (String issue : syntaxErrors.issues) {
                reporter.report(Diagnostics.templateStructureError(
                        issue, 0, 0, templateName));
            }
            throw new RenderFailure("Template " + templateName
                    + " has syntax errors");
        }

        // Nawar's builder prints tag-mismatch notes; capture them as warnings.
        ByteArrayOutputStream builderNotes = new ByteArrayOutputStream();
        HtmlAstBuilder builder = new HtmlAstBuilder(
                new PrintStream(builderNotes, true, StandardCharsets.UTF_8), false);
        HtmlNode astRoot = builder.visit(tree);
        if (astRoot == null) {
            throw new RenderFailure("Template " + templateName
                    + " produced no document");
        }
        String notes = builderNotes.toString(StandardCharsets.UTF_8).trim();
        if (!notes.isEmpty()) {
            for (String line : notes.split("\\R")) {
                if (!line.trim().isEmpty()) {
                    log.add("[warn]      " + templateName + " " + line.trim());
                }
            }
        }
        return astRoot;
    }

    private static final class SyntaxErrorCollector extends BaseErrorListener {
        final List<String> issues = new ArrayList<>();

        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String message,
                RecognitionException exception) {
            issues.add("Syntax error at " + line + ":"
                    + Math.max(charPositionInLine, 0) + " " + message);
        }
    }

    /** Output page name for a template: {@code index.jinja} → {@code index.html}. */
    public static String outputName(String templateName) {
        String base = templateName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base.toLowerCase(Locale.ROOT).equals(base)
                ? base + ".html"
                : base + ".html";
    }
}
