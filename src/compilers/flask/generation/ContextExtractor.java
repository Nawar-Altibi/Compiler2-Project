package compilers.flask.generation;

import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.Visitor.ASTBaseVisitor;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.Statement;
import compilers.flask.ast.nodes.expressions.access.AttributeAccessNode;
import compilers.flask.ast.nodes.expressions.access.FunctionCallNode;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.helpers.CallArgument;
import compilers.flask.ast.nodes.helpers.DecoratorNode;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.ast.nodes.statements.compound.ClassDefNode;
import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;
import compilers.flask.ast.nodes.statements.imports.FromImportNode;
import compilers.flask.ast.nodes.statements.imports.ImportNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pass over the Python AST that produces the {@link ProjectContext}:
 * evaluated module globals, the route map, and one {@link RenderJob} per
 * rendered page (plan section 5).
 *
 * <p>Deliberately symbol-table-free (frozen rule 1.5). Unsupported
 * constructs become diagnostics or logged skips — never silent values.</p>
 */
public final class ContextExtractor {

    /** Opaque marker for a name bound by {@code import x} / unsupported froms. */
    private static final class ModuleRef {
        final String moduleName;

        ModuleRef(String moduleName) {
            this.moduleName = moduleName;
        }

        @Override
        public String toString() {
            return "<module '" + moduleName + "'>";
        }
    }

    /** Opaque marker for the Flask application object. */
    private static final class FlaskApp {
        @Override
        public String toString() {
            return "<Flask app>";
        }
    }

    /** Marker for flask names whose runtime is out of generation scope. */
    private static final class UnsupportedRef {
        final String name;

        UnsupportedRef(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return "<unsupported '" + name + "'>";
        }
    }

    private final DiagnosticReporter reporter;
    private final String sourceFile;
    private final Map<String, Object> moduleEnv = new LinkedHashMap<>();
    private final Map<String, RouteInfo> routes = new LinkedHashMap<>();
    private final List<RenderJob> renderJobs = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final List<RenderJob> capturedInRoute = new ArrayList<>();
    private String currentEndpoint;
    private boolean appRunNoted;
    private PyEval eval;

    public ContextExtractor(DiagnosticReporter reporter, String sourceFile) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.sourceFile = Objects.requireNonNull(sourceFile, "sourceFile");
    }

    /** Runs extraction; always returns a context (diagnostics carry failures). */
    public ProjectContext extract(ProgramNode program) {
        moduleEnv.put("__name__", "__main__");
        moduleEnv.put("__package__", "");
        eval = new PyEval(moduleEnv, new ExtractionCalls());

        // -------- pass A: module top level, in source order --------
        for (Statement statement : program.getStatements()) {
            try {
                executeTopLevel(statement);
            } catch (PyEval.EvalError failure) {
                reporter.report(Diagnostics.invalidCodegenContext(
                        failure.getMessage(),
                        failure.line(),
                        failure.column(),
                        sourceFile));
            }
        }
        logGlobals();

        // -------- pass B: evaluate each route's view --------
        for (RouteInfo route : routes.values()) {
            evaluateRoute(route);
        }
        return new ProjectContext(publicGlobals(), routes, renderJobs, log);
    }

    // ==================== module level ====================

    private void executeTopLevel(Statement statement) {
        if (statement instanceof ImportNode) {
            ImportNode node = (ImportNode) statement;
            moduleEnv.put(node.getEffectiveName(), new ModuleRef(node.getModuleName()));
            log.add("[extract]   import " + node.getModuleName());
            return;
        }
        if (statement instanceof FromImportNode) {
            bindFromImport((FromImportNode) statement);
            return;
        }
        if (statement instanceof FunctionDefNode) {
            registerFunction((FunctionDefNode) statement);
            return;
        }
        if (statement instanceof ClassDefNode) {
            ClassDefNode node = (ClassDefNode) statement;
            log.add("[warn]      skipped unsupported class '" + node.getName()
                    + "' (out of generation scope)");
            return;
        }
        eval.exec(statement);
    }

    private void bindFromImport(FromImportNode node) {
        if (node.isImportAll()) {
            log.add("[warn]      'from " + node.getModuleName()
                    + " import *' is not expanded during generation");
            return;
        }
        for (FromImportNode.ImportItem item : node.getItems()) {
            String bound = item.getEffectiveName();
            if ("flask".equals(node.getModuleName())) {
                moduleEnv.put(bound, new UnsupportedRef(item.getName()));
            } else {
                moduleEnv.put(bound, new UnsupportedRef(
                        node.getModuleName() + "." + item.getName()));
            }
        }
        log.add("[extract]   from " + node.getModuleName() + " import "
                + node.getItems().size() + " name(s)");
    }

    private void registerFunction(FunctionDefNode node) {
        List<RouteInfo> discovered = new ArrayList<>();
        for (DecoratorNode decorator : node.getDecorators()) {
            RouteInfo route = tryRoute(decorator, node);
            if (route != null) {
                discovered.add(route);
            } else {
                log.add("[warn]      decorator on '" + node.getName()
                        + "' is not a route and is ignored during generation");
            }
        }
        for (RouteInfo route : discovered) {
            if (routes.containsKey(route.getEndpoint())) {
                reporter.report(Diagnostics.invalidCodegenContext(
                        "Duplicate route endpoint '" + route.getEndpoint() + "'",
                        node.getLine(),
                        node.getColumn(),
                        sourceFile));
            } else {
                routes.put(route.getEndpoint(), route);
                log.add("[extract]   route  " + String.join(",", route.getMethods())
                        + " " + route.getPath() + " -> view " + route.getEndpoint());
            }
        }
        try {
            eval.registerFunction(node);
        } catch (PyEval.EvalError failure) {
            reporter.report(Diagnostics.invalidCodegenContext(
                    "Default value of '" + node.getName() + "' failed: "
                            + failure.getMessage(),
                    failure.line(),
                    failure.column(),
                    sourceFile));
        }
    }

    /** Recognizes {@code @<app>.route(path, methods=[...])} / {@code .get} / {@code .post}. */
    private RouteInfo tryRoute(DecoratorNode decorator, FunctionDefNode view) {
        Expression expression = decorator.getExpression();
        if (!(expression instanceof FunctionCallNode)) {
            return null;
        }
        FunctionCallNode call = (FunctionCallNode) expression;
        if (!(call.getFunction() instanceof AttributeAccessNode)) {
            return null;
        }
        AttributeAccessNode access = (AttributeAccessNode) call.getFunction();
        String kind = access.getAttribute();
        if (!(access.getObject() instanceof IdentifierNode)
                || (!"route".equals(kind) && !"get".equals(kind) && !"post".equals(kind))) {
            return null;
        }

        String path = null;
        List<String> methods = new ArrayList<>();
        for (CallArgument argument : call.getArguments()) {
            try {
                Object value = eval.eval(argument.getValue());
                if (argument.isPositional() && path == null) {
                    if (!(value instanceof String)) {
                        return null;
                    }
                    path = (String) value;
                } else if (argument.isKeyword()
                        && "methods".equals(argument.getKeywordName())
                        && value instanceof List) {
                    for (Object method : (List<?>) value) {
                        methods.add(String.valueOf(method));
                    }
                }
            } catch (PyEval.EvalError failure) {
                reporter.report(Diagnostics.invalidCodegenContext(
                        "Route decorator argument failed: " + failure.getMessage(),
                        failure.line(),
                        failure.column(),
                        sourceFile));
                return null;
            }
        }
        if (path == null) {
            return null;
        }
        if (methods.isEmpty()) {
            methods.add("get".equals(kind) ? "GET" : "post".equals(kind) ? "POST" : "GET");
        }
        return new RouteInfo(view.getName(), path, methods, view);
    }

    // ==================== routes ====================

    private void evaluateRoute(RouteInfo route) {
        capturedInRoute.clear();
        currentEndpoint = route.getEndpoint();
        eval.setLenientConditions(true, log);
        try {
            eval.execBlock(routeBodyFrameShim(route));
        } catch (PyEval.EvalError failure) {
            log.add("[warn]      view '" + route.getEndpoint()
                    + "' not fully evaluable (" + failure.getMessage()
                    + "); falling back to static render_template scan");
            staticFallback(route);
        } finally {
            eval.setLenientConditions(false, null);
            currentEndpoint = null;
        }

        if (capturedInRoute.isEmpty()) {
            log.add("[extract]   route  " + route.getPath()
                    + " renders no template (skipped)");
            return;
        }
        if (capturedInRoute.size() > 1) {
            log.add("[note]      view '" + route.getEndpoint() + "' rendered "
                    + capturedInRoute.size() + " templates; keeping the last one");
        }
        RenderJob job = capturedInRoute.get(capturedInRoute.size() - 1);
        renderJobs.add(job);
        log.add("[extract]   " + route.getMethods().get(0) + " " + route.getPath()
                + " -> render \"" + job.getTemplateName() + "\" context="
                + job.getContext().keySet());
    }

    /**
     * Runs a view body in a fresh local frame by calling it like a
     * zero-argument user function (views in the supported subset take no
     * parameters; URL parameters are out of the current grammar's scope).
     */
    private List<Statement> routeBodyFrameShim(RouteInfo route) {
        // Execute via the registered function so locals stay isolated.
        // registerFunction() already ran; call with no arguments.
        if (eval.hasFunction(route.getEndpoint())) {
            FunctionCallShim.call(eval, route.getEndpoint(), route.getFunction());
            return java.util.Collections.emptyList();
        }
        return route.getFunction().getBody();
    }

    /** Tiny indirection so the shim reads clearly above. */
    private static final class FunctionCallShim {
        static void call(PyEval eval, String name, FunctionDefNode site) {
            FunctionCallNode synthetic = new FunctionCallNode(
                    new IdentifierNode(name), new ArrayList<CallArgument>());
            synthetic.setLine(site.getLine());
            synthetic.setColumn(site.getColumn());
            eval.eval(synthetic);
        }
    }

    /**
     * Plan section 5.4 fallback: the body was not fully evaluable (request
     * data, unsupported statements). Find render_template calls statically
     * and evaluate their arguments against the module environment; kwargs
     * that fail are omitted with a warning.
     */
    private void staticFallback(RouteInfo route) {
        List<FunctionCallNode> calls = new ArrayList<>();
        route.getFunction().accept(new ASTBaseVisitor<Void>() {
            @Override
            public Void visitFunctionCall(FunctionCallNode node) {
                if (node.getFunction() instanceof IdentifierNode
                        && "render_template".equals(
                                ((IdentifierNode) node.getFunction()).getName())) {
                    calls.add(node);
                }
                return super.visitFunctionCall(node);
            }
        });
        if (calls.isEmpty()) {
            return;
        }
        FunctionCallNode call = calls.get(calls.size() - 1);
        String templateName = null;
        Map<String, Object> context = new LinkedHashMap<>();
        for (CallArgument argument : call.getArguments()) {
            if (argument.isPositional()) {
                try {
                    Object value = eval.eval(argument.getValue());
                    if (value instanceof String && templateName == null) {
                        templateName = (String) value;
                    }
                } catch (PyEval.EvalError failure) {
                    reporter.report(Diagnostics.invalidCodegenContext(
                            "Template name is not statically evaluable: "
                                    + failure.getMessage(),
                            failure.line(),
                            failure.column(),
                            sourceFile));
                    return;
                }
            } else {
                try {
                    context.put(argument.getKeywordName(), eval.eval(argument.getValue()));
                } catch (PyEval.EvalError failure) {
                    log.add("[warn]      context '" + argument.getKeywordName()
                            + "' for view '" + route.getEndpoint()
                            + "' is request-dependent; omitted from the static page");
                }
            }
        }
        if (templateName == null) {
            return;
        }
        capturedInRoute.add(new RenderJob(
                templateName, context, route.getEndpoint(),
                call.getLine(), call.getColumn()));
    }

    // ==================== intercepted calls ====================

    private final class ExtractionCalls implements PyEval.CallInterceptor {
        @Override
        public Object intercept(
                String functionName,
                List<Object> positional,
                Map<String, Object> keyword,
                ASTNode site) {
            Object bound = moduleEnv.get(functionName);
            String original = bound instanceof UnsupportedRef
                    ? ((UnsupportedRef) bound).name
                    : functionName;

            if ("render_template".equals(original)) {
                if (positional.isEmpty() || !(positional.get(0) instanceof String)) {
                    throw new PyEval.EvalError(
                            "render_template() needs a literal template name", site);
                }
                String template = (String) positional.get(0);
                Map<String, Object> context = new LinkedHashMap<>(keyword);
                if (currentEndpoint == null) {
                    log.add("[warn]      render_template(\"" + template
                            + "\") outside a route is ignored");
                    return null;
                }
                RenderJob job = new RenderJob(template, context, currentEndpoint,
                        site.getLine(), site.getColumn());
                capturedInRoute.add(job);
                return job;
            }
            if ("url_for".equals(original)) {
                if (positional.isEmpty() || !(positional.get(0) instanceof String)) {
                    throw new PyEval.EvalError(
                            "url_for() needs a literal endpoint name", site);
                }
                Map<String, Object> params = new LinkedHashMap<>(keyword);
                return new ProjectContext.UrlRef((String) positional.get(0), params);
            }
            if ("Flask".equals(original)) {
                return new FlaskApp();
            }
            if (bound instanceof UnsupportedRef) {
                throw new PyEval.EvalError("'" + original
                        + "' is outside the generation subset", site);
            }
            return PyEval.SKIP;
        }

        @Override
        public Object interceptMethod(
                Object receiver,
                String methodName,
                List<Object> positional,
                Map<String, Object> keyword,
                ASTNode site) {
            if (receiver instanceof FlaskApp) {
                if ("run".equals(methodName)) {
                    if (!appRunNoted) {
                        appRunNoted = true;
                        log.add("[extract]   app.run(...) is a no-op during generation");
                    }
                    return null;
                }
                throw new PyEval.EvalError("Flask app method '." + methodName
                        + "' is outside the generation subset", site);
            }
            if (receiver instanceof ModuleRef) {
                throw new PyEval.EvalError("module '" + ((ModuleRef) receiver).moduleName
                        + "' has no evaluable functions during generation", site);
            }
            if (receiver instanceof UnsupportedRef) {
                throw new PyEval.EvalError("'" + ((UnsupportedRef) receiver).name
                        + "' is outside the generation subset", site);
            }
            return PyEval.SKIP;
        }
    }

    // ==================== reporting helpers ====================

    private Map<String, Object> publicGlobals() {
        Map<String, Object> visible = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : moduleEnv.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof ModuleRef || value instanceof FlaskApp
                    || value instanceof UnsupportedRef) {
                continue;
            }
            if ("__name__".equals(entry.getKey()) || "__package__".equals(entry.getKey())) {
                continue;
            }
            visible.put(entry.getKey(), value);
        }
        return visible;
    }

    private void logGlobals() {
        Map<String, Object> visible = publicGlobals();
        if (visible.isEmpty()) {
            log.add("[extract]   globals: (none)");
            return;
        }
        StringBuilder line = new StringBuilder("[extract]   globals: ");
        boolean first = true;
        for (Map.Entry<String, Object> entry : visible.entrySet()) {
            if (!first) {
                line.append(", ");
            }
            line.append(entry.getKey()).append('(').append(describe(entry.getValue()))
                    .append(')');
            first = false;
        }
        log.add(line.toString());
    }

    private String describe(Object value) {
        if (value instanceof List) {
            return "list[" + ((List<?>) value).size() + "]";
        }
        if (value instanceof Map) {
            return "dict[" + ((Map<?, ?>) value).size() + "]";
        }
        return PyEval.typeName(value);
    }
}
