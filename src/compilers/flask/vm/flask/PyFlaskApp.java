package compilers.flask.vm.flask;

import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.VmCallContext;
import compilers.flask.vm.values.PyAttributeProvider;
import compilers.flask.vm.values.PyCallable;
import compilers.flask.vm.values.PyDict;
import compilers.flask.vm.values.PyFunction;
import compilers.flask.vm.values.PyList;
import compilers.flask.vm.values.PyNativeFunction;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Non-networking Flask teaching adapter with deterministic route storage. */
public final class PyFlaskApp implements PyValue, PyAttributeProvider {
    /** Immutable result of matching an HTTP method and request path. */
    public static final class RouteMatch {
        private final PyFlaskApp owner;
        private final Route route;
        private final String method;
        private final String requestPath;
        private final List<String> parameterValues;
        private final Map<String, String> pathParameters;

        private RouteMatch(
                PyFlaskApp owner,
                Route route,
                String method,
                String requestPath,
                List<String> parameterValues,
                Map<String, String> pathParameters) {
            this.owner = owner;
            this.route = route;
            this.method = method;
            this.requestPath = requestPath;
            this.parameterValues = Collections.unmodifiableList(
                    new ArrayList<>(parameterValues));
            this.pathParameters = Collections.unmodifiableMap(
                    new LinkedHashMap<>(pathParameters));
        }

        public String getEndpoint() { return route.endpoint; }
        public String getRegisteredPath() { return route.path; }
        public String getMethod() { return method; }
        public String getRequestPath() { return requestPath; }
        public Map<String, String> getPathParameters() { return pathParameters; }
    }

    static final class Route {
        private final String path;
        private final List<String> methods;
        private final String endpoint;
        private final PyValue callable;

        private Route(String path, List<String> methods, String endpoint, PyValue callable) {
            this.path = path;
            this.methods = methods;
            this.endpoint = endpoint;
            this.callable = callable;
        }
    }

    private final String name;
    private final LinkedHashMap<String, Route> endpoints = new LinkedHashMap<>();

    public PyFlaskApp(String name) {
        if (name == null || name.isEmpty()) {
            throw RuntimeOps.error("ValueError", "Flask import name cannot be empty");
        }
        this.name = name;
    }

    public String getName() { return name; }

    public Map<String, PyValue> routeSnapshot() {
        LinkedHashMap<String, PyValue> result = new LinkedHashMap<>();
        for (Map.Entry<String, Route> route : endpoints.entrySet()) {
            result.put(route.getKey(), route.getValue().callable);
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Optional<PyValue> findAttribute(String attribute) {
        switch (attribute) {
            case "name": return Optional.<PyValue>of(new PyString(name));
            case "route": return Optional.<PyValue>of(routeFunction());
            case "register_blueprint":
                return Optional.<PyValue>of(new PyNativeFunction(
                        "register_blueprint", this::registerBlueprint));
            case "app_context":
                return Optional.<PyValue>of(new PyNativeFunction(
                        "app_context", (context, positional, keywords) -> {
                            requireArity("app_context", positional, keywords, 0);
                            return new PyAppContextManager(this);
                        }));
            case "run":
                return Optional.<PyValue>of(new PyNativeFunction(
                        "run", (context, positional, keywords) -> PyNone.INSTANCE));
            default: return Optional.empty();
        }
    }

    private PyNativeFunction routeFunction() {
        return new PyNativeFunction("route", (context, positional, keywords) -> {
            if (positional.size() != 1) {
                throw RuntimeOps.error("TypeError", "route() expects one path argument");
            }
            String path = string(positional.get(0), "route path");
            rejectUnknownKeywords("route", keywords, Collections.singleton("methods"));
            List<String> methods = parseMethods(keywords.get("methods"));
            return new PyNativeFunction("route_decorator", (callContext, values, named) -> {
                requireArity("route decorator", values, named, 1);
                PyValue callable = values.get(0);
                if (!(callable instanceof PyCallable)) {
                    throw RuntimeOps.error("TypeError", "route decorator requires a callable");
                }
                registerRoute(path, methods, callableName(callable), callable);
                return callable;
            });
        });
    }

    private PyValue registerBlueprint(
            VmCallContext context, List<PyValue> positional, Map<String, PyValue> keywords) {
        if (positional.size() != 1 || !(positional.get(0) instanceof PyBlueprint)) {
            throw RuntimeOps.error("TypeError",
                    "register_blueprint() expects one Blueprint");
        }
        rejectUnknownKeywords("register_blueprint", keywords,
                Collections.singleton("url_prefix"));
        String prefix = keywords.containsKey("url_prefix")
                ? string(keywords.get("url_prefix"), "url_prefix") : "";
        ((PyBlueprint) positional.get(0)).registerInto(this, prefix);
        return PyNone.INSTANCE;
    }

    void registerRoute(
            String path, List<String> methods, String endpoint, PyValue callable) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') {
            throw RuntimeOps.error("ValueError", "route path must start with '/'");
        }
        if (endpoints.containsKey(endpoint)) {
            throw RuntimeOps.error("RuntimeError", "duplicate endpoint '" + endpoint + "'");
        }
        endpoints.put(endpoint, new Route(
                path, Collections.unmodifiableList(new ArrayList<>(methods)),
                endpoint, callable));
    }

    public PyValue invokeRoute(
            VmCallContext context,
            String endpoint,
            PyRequest request,
            PyDict session) {
        Route route = endpoints.get(endpoint);
        if (route == null) {
            throw RuntimeOps.error("KeyError", new PyString(endpoint).repr());
        }
        PyRequest effectiveRequest = request == null
                ? new PyRequest("GET", route.path) : request;
        String requestMethod = ((PyString) effectiveRequest.findAttribute("method")
                .orElseThrow(() -> new IllegalStateException("Request has no method")))
                .getValue();
        if (!route.methods.contains(requestMethod)) {
            throw RuntimeOps.error("RuntimeError", "method " + requestMethod
                    + " is not allowed for endpoint '" + endpoint + "'");
        }
        return invokeMatchedRoute(context, route, effectiveRequest, session,
                Collections.<PyValue>emptyList());
    }

    /**
     * Resolves a request without entering a Flask context or invoking user code.
     * Static rules take precedence over placeholders; ties retain registration order.
     */
    public Optional<RouteMatch> resolveRoute(String method, String path) {
        String normalizedMethod = normalizeMethod(method);
        String normalizedPath = normalizeRequestPath(path);

        for (Route route : endpoints.values()) {
            if (route.methods.contains(normalizedMethod)
                    && route.path.equals(normalizedPath)) {
                return Optional.of(new RouteMatch(
                        this, route, normalizedMethod, normalizedPath,
                        Collections.<String>emptyList(),
                        Collections.<String, String>emptyMap()));
            }
        }
        for (Route route : endpoints.values()) {
            if (!route.methods.contains(normalizedMethod)) continue;
            DynamicMatch matched = matchDynamicPath(route.path, normalizedPath);
            if (matched != null) {
                return Optional.of(new RouteMatch(
                        this, route, normalizedMethod, normalizedPath,
                        matched.values, matched.parameters));
            }
        }
        return Optional.empty();
    }

    /** Invokes a previously resolved route and passes placeholders positionally. */
    public PyValue invokeResolvedRoute(
            VmCallContext context,
            RouteMatch match,
            PyDict session) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(match, "match");
        if (match.owner != this) {
            throw new IllegalArgumentException(
                    "route match belongs to a different Flask application");
        }
        List<PyValue> positional = new ArrayList<>(match.parameterValues.size());
        for (String value : match.parameterValues) {
            positional.add(new PyString(value));
        }
        return invokeMatchedRoute(
                context, match.route,
                new PyRequest(match.method, match.requestPath),
                session, positional);
    }

    /** Convenience host API used by the non-networking command-line bridge. */
    public PyValue invokePath(
            VmCallContext context,
            String method,
            String path,
            PyDict session) {
        RouteMatch match = resolveRoute(method, path).orElseThrow(() ->
                RuntimeOps.error("RuntimeError", "no route for "
                        + normalizeMethod(method) + " " + normalizeRequestPath(path)));
        return invokeResolvedRoute(context, match, session);
    }

    private PyValue invokeMatchedRoute(
            VmCallContext context,
            Route route,
            PyRequest request,
            PyDict session,
            List<PyValue> positionalArguments) {
        FlaskExecutionContext flask = context.getRuntimeServices().getFlask();
        FlaskExecutionContext.Scope appScope = flask.enterApp(this);
        try {
            FlaskExecutionContext.Scope requestScope = flask.enterRequest(
                    request,
                    session == null ? new PyDict() : session);
            try {
                return context.invoke(route.callable,
                        positionalArguments,
                        Collections.<String, PyValue>emptyMap());
            } finally {
                requestScope.close();
            }
        } finally {
            appScope.close();
        }
    }

    private static String normalizeMethod(String method) {
        if (method == null || method.trim().isEmpty()) {
            throw RuntimeOps.error("ValueError", "HTTP method cannot be empty");
        }
        return method.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeRequestPath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') {
            throw RuntimeOps.error("ValueError", "request path must start with '/'");
        }
        return path;
    }

    private static DynamicMatch matchDynamicPath(String rule, String requestPath) {
        String[] ruleSegments = rule.split("/", -1);
        String[] requestSegments = requestPath.split("/", -1);
        if (ruleSegments.length != requestSegments.length) return null;

        List<String> values = new ArrayList<>();
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        boolean dynamic = false;
        for (int index = 0; index < ruleSegments.length; index++) {
            String ruleSegment = ruleSegments[index];
            String requestSegment = requestSegments[index];
            String parameter = placeholderName(ruleSegment);
            if (parameter == null) {
                if (!ruleSegment.equals(requestSegment)) return null;
                continue;
            }
            dynamic = true;
            if (requestSegment.isEmpty()) return null;
            String previous = parameters.put(parameter, requestSegment);
            if (previous != null && !previous.equals(requestSegment)) return null;
            values.add(requestSegment);
        }
        return dynamic ? new DynamicMatch(values, parameters) : null;
    }

    private static String placeholderName(String segment) {
        if (segment.length() < 3 || segment.charAt(0) != '<'
                || segment.charAt(segment.length() - 1) != '>') {
            return null;
        }
        String name = segment.substring(1, segment.length() - 1);
        if (!(name.charAt(0) == '_' || Character.isLetter(name.charAt(0)))) {
            return null;
        }
        for (int index = 1; index < name.length(); index++) {
            char character = name.charAt(index);
            if (!(character == '_' || Character.isLetterOrDigit(character))) return null;
        }
        return name;
    }

    private static final class DynamicMatch {
        private final List<String> values;
        private final Map<String, String> parameters;

        private DynamicMatch(List<String> values, Map<String, String> parameters) {
            this.values = values;
            this.parameters = parameters;
        }
    }

    public String urlFor(String endpoint, Map<String, PyValue> values) {
        Route route = endpoints.get(endpoint);
        if (route == null) {
            throw RuntimeOps.error("KeyError", new PyString(endpoint).repr());
        }
        String path = route.path;
        LinkedHashMap<String, PyValue> remaining = new LinkedHashMap<>(values);
        for (Map.Entry<String, PyValue> value : values.entrySet()) {
            String marker = "<" + value.getKey() + ">";
            if (path.contains(marker)) {
                path = path.replace(marker, urlComponent(value.getValue().str()));
                remaining.remove(value.getKey());
            }
        }
        if (!remaining.isEmpty()) {
            StringBuilder query = new StringBuilder();
            for (Map.Entry<String, PyValue> value : remaining.entrySet()) {
                query.append(query.length() == 0 ? '?' : '&')
                        .append(urlComponent(value.getKey()))
                        .append('=')
                        .append(urlComponent(value.getValue().str()));
            }
            path += query;
        }
        return path;
    }

    private static String urlComponent(String value) {
        return value.replace("%", "%25").replace(" ", "%20")
                .replace("?", "%3F").replace("&", "%26").replace("=", "%3D");
    }

    static List<String> parseMethods(PyValue value) {
        if (value == null || value == PyNone.INSTANCE) {
            return Collections.singletonList("GET");
        }
        List<String> result = new ArrayList<>();
        compilers.flask.vm.values.PyIterator iterator = RuntimeOps.iter(value);
        Optional<PyValue> next;
        Set<String> distinct = new LinkedHashSet<>();
        while ((next = iterator.tryNext()).isPresent()) {
            String method = string(next.get(), "HTTP method").toUpperCase(Locale.ROOT);
            if (distinct.add(method)) result.add(method);
        }
        if (result.isEmpty()) {
            throw RuntimeOps.error("ValueError", "route methods cannot be empty");
        }
        return result;
    }

    static String callableName(PyValue callable) {
        if (callable instanceof PyFunction) return ((PyFunction) callable).getName();
        if (callable instanceof PyNativeFunction) return ((PyNativeFunction) callable).getName();
        try {
            PyValue name = RuntimeOps.loadAttribute(callable, "__name__");
            if (name instanceof PyString) return ((PyString) name).getValue();
        } catch (compilers.flask.vm.VmRuntimeException ignored) {
            // Fall through to a deterministic non-reflective name.
        }
        return "endpoint_" + Integer.toUnsignedString(System.identityHashCode(callable));
    }

    static String string(PyValue value, String label) {
        if (!(value instanceof PyString)) {
            throw RuntimeOps.error("TypeError", label + " must be str");
        }
        return ((PyString) value).getValue();
    }

    static void requireArity(
            String name, List<PyValue> positional,
            Map<String, PyValue> keywords, int count) {
        if (positional.size() != count || !keywords.isEmpty()) {
            throw RuntimeOps.error("TypeError", name + "() expects " + count + " argument(s)");
        }
    }

    static void rejectUnknownKeywords(
            String name, Map<String, PyValue> keywords, Set<String> allowed) {
        for (String keyword : keywords.keySet()) {
            if (!allowed.contains(keyword)) {
                throw RuntimeOps.error("TypeError", name
                        + "() got an unexpected keyword argument '" + keyword + "'");
            }
        }
    }

    @Override public String getTypeName() { return "Flask"; }
    @Override public String repr() { return "<Flask " + new PyString(name).repr() + ">"; }
    @Override public String toString() { return repr(); }
}
