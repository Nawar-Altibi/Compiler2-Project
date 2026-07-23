import compilers.flask.runtime.RuntimeSymbolManifest;
import compilers.flask.vm.Namespace;
import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.RuntimeServices;
import compilers.flask.vm.VmCallContext;
import compilers.flask.vm.VmCapabilities;
import compilers.flask.vm.VmRuntimeException;
import compilers.flask.vm.flask.FlaskExecutionContext;
import compilers.flask.vm.flask.FlaskNativeProviders;
import compilers.flask.vm.flask.FlaskResponseNormalizer;
import compilers.flask.vm.flask.PyAppContextManager;
import compilers.flask.vm.flask.PyBlueprint;
import compilers.flask.vm.flask.PyContextProxy;
import compilers.flask.vm.flask.PyFlaskApp;
import compilers.flask.vm.flask.PyRequest;
import compilers.flask.vm.flask.PyResponse;
import compilers.flask.vm.flask.PyTemplateRenderRequest;
import compilers.flask.vm.modules.ModuleLoader;
import compilers.flask.vm.modules.ModuleRegistry;
import compilers.flask.vm.values.PyBool;
import compilers.flask.vm.values.PyCallable;
import compilers.flask.vm.values.PyDict;
import compilers.flask.vm.values.PyFunction;
import compilers.flask.vm.values.PyInt;
import compilers.flask.vm.values.PyList;
import compilers.flask.vm.values.PyModule;
import compilers.flask.vm.values.PyNativeClass;
import compilers.flask.vm.values.PyNativeFunction;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyTuple;
import compilers.flask.vm.values.PyValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free contract tests for the native Flask teaching adapter. */
public final class FlaskNativeAdapterHarness {
    private static int passed;
    private static int failed;

    private FlaskNativeAdapterHarness() {
    }

    public static void main(String[] args) {
        run("all 16 manifest exports have concrete provider shapes",
                FlaskNativeAdapterHarness::testExportCoverage);
        run("every export rejects an invalid call or missing context",
                FlaskNativeAdapterHarness::testExportFailures);
        run("Flask route registration and invocation isolate contexts",
                FlaskNativeAdapterHarness::testRouteInvocationAndContexts);
        run("Blueprint routes merge and resolve deterministically",
                FlaskNativeAdapterHarness::testBlueprint);
        run("method/path route resolution prefers exact rules and binds segments",
                FlaskNativeAdapterHarness::testMethodPathResolution);
        run("render, response, redirect, jsonify, abort, and make_response",
                FlaskNativeAdapterHarness::testResponseProviders);
        run("route responses normalize and format deterministically",
                FlaskNativeAdapterHarness::testResponseNormalization);
        run("url_for and flash use active app/request state",
                FlaskNativeAdapterHarness::testUrlForAndFlash);
        run("request, session, g, and current_app proxies delegate",
                FlaskNativeAdapterHarness::testProxyDelegation);
        run("send_file providers enforce explicit read capability",
                FlaskNativeAdapterHarness::testFileCapabilities);
        run("app_context enters, exits, and refreshes g",
                FlaskNativeAdapterHarness::testAppContextManager);
        run("failed context close is retryable and unavailable services isolate state",
                FlaskNativeAdapterHarness::testContextFailureAndIsolation);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " Flask adapter test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " Flask adapter tests passed.");
    }

    private static void testExportCoverage() {
        Fixture fixture = fixture(VmCapabilities.NONE);
        List<String> expected = Arrays.asList(
                "Flask", "Blueprint", "Response", "render_template", "redirect",
                "jsonify", "abort", "make_response", "url_for", "flash",
                "send_file", "send_from_directory", "request", "session", "g",
                "current_app");
        equal(expected, new ArrayList<>(fixture.module.snapshot().keySet()).subList(2, 18),
                "native Flask export order");
        equal(RuntimeSymbolManifest.moduleExports("flask").keySet(),
                new java.util.LinkedHashSet<>(expected), "manifest/provider names");

        for (String name : expected) {
            check(fixture.module.find(name).isPresent(), "missing provider " + name);
        }
        for (String name : Arrays.asList("Flask", "Blueprint", "Response")) {
            check(export(fixture, name) instanceof PyNativeClass,
                    name + " must be a native class");
        }
        for (String name : Arrays.asList(
                "render_template", "redirect", "jsonify", "abort", "make_response",
                "url_for", "flash", "send_file", "send_from_directory")) {
            check(export(fixture, name) instanceof PyNativeFunction,
                    name + " must be a native function");
        }
        for (String name : Arrays.asList("request", "session", "g", "current_app")) {
            check(export(fixture, name) instanceof PyContextProxy,
                    name + " must be a stable context proxy");
        }
    }

    private static void testExportFailures() {
        Fixture fixture = fixture(VmCapabilities.NONE);

        expectVm("TypeError", () -> call(fixture, "Flask", values(), keywords()));
        expectVm("TypeError", () -> call(fixture, "Flask",
                values(PyInt.ONE), keywords()));
        expectVm("TypeError", () -> call(fixture, "Blueprint",
                values(new PyString("only-one")), keywords()));
        expectVm("TypeError", () -> call(fixture, "Response",
                values(new PyString("body"), new PyString("bad-status")), keywords()));
        expectVm("TypeError", () -> call(fixture, "render_template",
                values(), keywords()));
        expectVm("TypeError", () -> call(fixture, "redirect", values(), keywords()));
        expectVm("TypeError", () -> call(fixture, "url_for", values(), keywords()));
        expectVm("TypeError", () -> call(fixture, "flash", values(), keywords()));
        expectVm("TypeError", () -> call(fixture, "jsonify",
                values(PyInt.ONE), keywords("value", PyInt.ONE)));
        expectVm("TypeError", () -> call(fixture, "abort",
                values(new PyString("bad")), keywords()));
        expectVm("TypeError", () -> call(fixture, "make_response",
                values(), keywords()));
        expectVm("TypeError", () -> call(fixture, "send_file",
                values(), keywords()));
        expectVm("TypeError", () -> call(fixture, "send_from_directory",
                values(new PyString("root")), keywords()));

        for (String proxy : Arrays.asList("request", "session", "g", "current_app")) {
            expectVm("RuntimeError", () -> RuntimeOps.resolveDynamic(
                    export(fixture, proxy), fixture.context));
        }
    }

    private static void testRouteInvocationAndContexts() {
        Fixture fixture = fixture(VmCapabilities.NONE);
        PyFlaskApp app = flaskApp(fixture, "route_app");
        PyValue requestProxy = export(fixture, "request");
        PyValue sessionProxy = export(fixture, "session");
        PyValue gProxy = export(fixture, "g");
        PyValue currentAppProxy = export(fixture, "current_app");
        AtomicInteger routeCalls = new AtomicInteger();

        PyNativeFunction handler = new PyNativeFunction("home", (context, p, k) -> {
            equal(0, p.size(), "route positional arguments");
            equal(new PyString("POST"),
                    RuntimeOps.loadAttribute(requestProxy, "method", context),
                    "request method in route");
            equal(new PyString("/home"),
                    RuntimeOps.loadAttribute(requestProxy, "path", context),
                    "request path in route");
            PyDict form = (PyDict) RuntimeOps.loadAttribute(
                    requestProxy, "form", context);
            equal(new PyString("Ali"), form.find(new PyString("name")).orElse(null),
                    "request form in route");
            same(app, RuntimeOps.resolveDynamic(currentAppProxy, context),
                    "current_app in route");

            expectVm("AttributeError", () -> RuntimeOps.loadAttribute(
                    gProxy, "seen", context));
            RuntimeOps.storeAttribute(gProxy, "seen", PyBool.TRUE, context);
            RuntimeOps.storeSubscript(sessionProxy, new PyString("visits"),
                    PyInt.valueOf(routeCalls.incrementAndGet()), context);
            return new PyString("ok");
        });

        PyValue route = RuntimeOps.loadAttribute(app, "route");
        PyValue decorator = fixture.context.invoke(route,
                values(new PyString("/home")),
                keywords("methods", new PyList(Arrays.<PyValue>asList(
                        new PyString("GET"), new PyString("POST")))));
        same(handler, fixture.context.invoke(decorator, values(handler), keywords()),
                "route decorator return value");
        same(handler, app.routeSnapshot().get("home"), "registered route callable");

        PyDict form = new PyDict();
        form.put(new PyString("name"), new PyString("Ali"));
        PyRequest request = new PyRequest(
                "POST", "/home", new PyDict(), form, new PyDict(), PyNone.INSTANCE);
        PyDict firstSession = new PyDict();
        equal(new PyString("ok"),
                app.invokeRoute(fixture.context, "home", request, firstSession),
                "first route result");
        equal(PyInt.ONE, firstSession.find(new PyString("visits")).orElse(null),
                "first route session");
        check(!fixture.services.getFlask().hasApp()
                        && !fixture.services.getFlask().hasRequest(),
                "route leaked Flask context");

        PyDict secondSession = new PyDict();
        equal(new PyString("ok"),
                app.invokeRoute(fixture.context, "home", request, secondSession),
                "second route result");
        equal(PyInt.valueOf(2),
                secondSession.find(new PyString("visits")).orElse(null),
                "second route session");
        expectVm("RuntimeError", () -> RuntimeOps.resolveDynamic(
                requestProxy, fixture.context));
    }

    private static void testBlueprint() {
        Fixture fixture = fixture(VmCapabilities.NONE);
        PyFlaskApp app = flaskApp(fixture, "blueprint_app");
        PyBlueprint blueprint = (PyBlueprint) call(fixture, "Blueprint",
                values(new PyString("admin"), new PyString("module")), keywords());
        PyNativeFunction handler = new PyNativeFunction(
                "dashboard", (context, p, k) -> new PyString("blue"));

        PyValue route = RuntimeOps.loadAttribute(blueprint, "route");
        PyValue decorator = fixture.context.invoke(
                route, values(new PyString("/dashboard/<id>")), keywords());
        fixture.context.invoke(decorator, values(handler), keywords());
        PyValue register = RuntimeOps.loadAttribute(app, "register_blueprint");
        fixture.context.invoke(register, values(blueprint),
                keywords("url_prefix", new PyString("/admin")));

        check(app.routeSnapshot().containsKey("admin.dashboard"),
                "blueprint endpoint missing");
        equal(new PyString("blue"), app.invokeRoute(
                fixture.context, "admin.dashboard",
                new PyRequest("GET", "/admin/dashboard/7"), new PyDict()),
                "blueprint route result");

        FlaskExecutionContext.Scope scope = fixture.services.getFlask().enterApp(app);
        try {
            PyValue url = call(fixture, "url_for",
                    values(new PyString("admin.dashboard")),
                    keywords("id", PyInt.valueOf(7), "q", new PyString("a b")));
            equal(new PyString("/admin/dashboard/7?q=a%20b"), url,
                    "blueprint url_for");
        } finally {
            scope.close();
        }
    }

    private static void testMethodPathResolution() {
        Fixture fixture = fixture(VmCapabilities.NONE);
        PyFlaskApp app = flaskApp(fixture, "resolver_app");

        PyNativeFunction dynamic = new PyNativeFunction("dynamic_user", (context, p, k) -> {
            equal(values(new PyString("Ali")), p, "dynamic path arguments");
            equal(new PyString("/users/Ali"), RuntimeOps.loadAttribute(
                    export(fixture, "request"), "path", context), "resolved request path");
            return new PyString("dynamic");
        });
        PyNativeFunction exact = new PyNativeFunction("new_user", (context, p, k) -> {
            equal(0, p.size(), "exact path arguments");
            return new PyString("exact");
        });
        PyNativeFunction secondDynamic = new PyNativeFunction(
                "second_dynamic", (context, p, k) -> new PyString("second"));

        PyValue route = RuntimeOps.loadAttribute(app, "route");
        fixture.context.invoke(
                fixture.context.invoke(route, values(new PyString("/users/<name>")),
                        keywords()), values(dynamic), keywords());
        fixture.context.invoke(
                fixture.context.invoke(route, values(new PyString("/users/new")),
                        keywords()), values(exact), keywords());
        fixture.context.invoke(
                fixture.context.invoke(route, values(new PyString("/users/<other>")),
                        keywords()), values(secondDynamic), keywords());

        PyFlaskApp.RouteMatch exactMatch = app.resolveRoute("get", "/users/new")
                .orElseThrow(() -> new AssertionError("exact route did not resolve"));
        equal("new_user", exactMatch.getEndpoint(), "exact route precedence");
        equal(Collections.emptyMap(), exactMatch.getPathParameters(),
                "exact route parameters");

        PyFlaskApp.RouteMatch dynamicMatch = app.resolveRoute("GET", "/users/Ali")
                .orElseThrow(() -> new AssertionError("dynamic route did not resolve"));
        equal("dynamic_user", dynamicMatch.getEndpoint(),
                "dynamic registration-order precedence");
        equal(Collections.singletonMap("name", "Ali"),
                dynamicMatch.getPathParameters(), "dynamic route parameters");
        equal(new PyString("dynamic"),
                app.invokeResolvedRoute(fixture.context, dynamicMatch, new PyDict()),
                "resolved route invocation");
        check(!fixture.services.getFlask().hasApp()
                        && !fixture.services.getFlask().hasRequest(),
                "resolved route leaked Flask context");

        check(!app.resolveRoute("POST", "/users/Ali").isPresent(),
                "method mismatch resolved unexpectedly");
        check(!app.resolveRoute("GET", "/users/Ali/details").isPresent(),
                "placeholder consumed multiple segments");
        expectVm("RuntimeError", () -> app.invokePath(
                fixture.context, "DELETE", "/missing", new PyDict()));
    }

    private static void testResponseProviders() {
        Fixture fixture = fixture(VmCapabilities.NONE);

        PyResponse response = (PyResponse) call(fixture, "Response",
                values(new PyString("body")),
                keywords("status", PyInt.valueOf(201),
                        "content_type", new PyString("text/custom")));
        equal(201, response.getStatusCode(), "Response status");
        equal(new PyString("body"), response.getBody(), "Response body");
        equal(new PyString("text/custom"),
                RuntimeOps.loadAttribute(response, "content_type"),
                "Response content type");

        PyTemplateRenderRequest rendered = (PyTemplateRenderRequest) call(
                fixture, "render_template", values(new PyString("index.html")),
                keywords("user", new PyString("Ali")));
        equal(new PyString("index.html"),
                RuntimeOps.loadAttribute(rendered, "template_name"),
                "template name");
        PyDict templateContext = (PyDict) RuntimeOps.loadAttribute(rendered, "context");
        equal(new PyString("Ali"),
                templateContext.find(new PyString("user")).orElse(null),
                "template context");

        PyResponse redirect = (PyResponse) call(fixture, "redirect",
                values(new PyString("/next")), keywords("code", PyInt.valueOf(307)));
        equal(307, redirect.getStatusCode(), "redirect status");
        equal(new PyString("/next"),
                redirect.getHeaders().find(new PyString("Location")).orElse(null),
                "redirect Location");

        PyResponse json = (PyResponse) call(fixture, "jsonify", values(),
                keywords("name", new PyString("Ali"), "ok", PyBool.TRUE));
        equal(new PyString("{\"name\":\"Ali\",\"ok\":true}"), json.getBody(),
                "deterministic JSON body");
        equal(new PyString("application/json"),
                RuntimeOps.loadAttribute(json, "content_type"), "JSON content type");
        PyList cyclic = new PyList();
        cyclic.append(cyclic);
        expectVm("ValueError", () -> call(
                fixture, "jsonify", values(cyclic), keywords()));

        expectVm("HTTPException", () -> call(
                fixture, "abort", values(PyInt.valueOf(404)), keywords()));

        PyResponse made = (PyResponse) call(fixture, "make_response",
                values(new PyString("made"), PyInt.valueOf(202)), keywords());
        equal(202, made.getStatusCode(), "make_response status");
        equal(new PyString("made"), made.getBody(), "make_response body");
        same(response, call(fixture, "make_response", values(response), keywords()),
                "make_response existing Response identity");
    }

    private static void testResponseNormalization() {
        Fixture fixture = fixture(VmCapabilities.NONE);
        PyResponse plain = FlaskResponseNormalizer.normalize(new PyString("hello"));
        equal(200, plain.getStatusCode(), "plain response status");
        equal("text/plain; charset=utf-8", plain.getContentType(),
                "plain response content type");

        PyDict headers = new PyDict();
        headers.put(new PyString("Z-Last"), new PyString("z"));
        headers.put(new PyString("A-First"), new PyString("a"));
        PyTuple tuple = new PyTuple(values(
                new PyString("hello"), PyInt.valueOf(202), headers));
        PyResponse normalized = FlaskResponseNormalizer.normalize(tuple);
        equal(202, normalized.getStatusCode(), "tuple response status");
        equal("HTTP 202\nContent-Type: text/plain; charset=utf-8\n"
                        + "A-First: a\nZ-Last: z\n\nhello",
                FlaskResponseNormalizer.format(normalized),
                "deterministic response format");

        same(normalized, FlaskResponseNormalizer.normalize(normalized),
                "normalizer response identity");
        PyResponse made = (PyResponse) call(
                fixture, "make_response", values(tuple), keywords());
        equal(202, made.getStatusCode(), "make_response tuple integration");
        expectVm("TypeError", () -> FlaskResponseNormalizer.normalize(
                new PyTuple(values(PyInt.ONE))));
        expectVm("TypeError", () -> FlaskResponseNormalizer.normalize(
                new PyTuple(values(new PyString("body"), new PyString("bad")))));
    }

    private static void testUrlForAndFlash() {
        Fixture fixture = fixture(VmCapabilities.NONE);
        PyFlaskApp app = flaskApp(fixture, "state_app");
        PyNativeFunction handler = new PyNativeFunction(
                "profile", (context, p, k) -> PyNone.INSTANCE);
        PyValue decorator = fixture.context.invoke(
                RuntimeOps.loadAttribute(app, "route"),
                values(new PyString("/user/<name>")), keywords());
        fixture.context.invoke(decorator, values(handler), keywords());

        expectVm("RuntimeError", () -> call(fixture, "url_for",
                values(new PyString("profile")), keywords("name", new PyString("Ali"))));
        expectVm("RuntimeError", () -> call(fixture, "flash",
                values(new PyString("message")), keywords()));

        PyDict session = new PyDict();
        FlaskExecutionContext.Scope appScope = fixture.services.getFlask().enterApp(app);
        try {
            FlaskExecutionContext.Scope requestScope = fixture.services.getFlask()
                    .enterRequest(new PyRequest("GET", "/"), session);
            try {
                equal(new PyString("/user/Ali?tab=info"),
                        call(fixture, "url_for", values(new PyString("profile")),
                                keywords("name", new PyString("Ali"),
                                        "tab", new PyString("info"))),
                        "url_for result");
                equal(PyNone.INSTANCE, call(fixture, "flash",
                        values(new PyString("saved")),
                        keywords("category", new PyString("success"))),
                        "flash return");
            } finally {
                requestScope.close();
            }
        } finally {
            appScope.close();
        }
        PyList flashes = (PyList) session.find(new PyString("_flashes")).orElse(null);
        check(flashes != null && flashes.size() == 1, "flash record missing");
        equal(new PyTuple(Arrays.<PyValue>asList(
                        new PyString("success"), new PyString("saved"))),
                flashes.get(0), "flash record");
    }

    private static void testProxyDelegation() {
        Fixture fixture = fixture(VmCapabilities.NONE);
        PyFlaskApp app = flaskApp(fixture, "proxy_app");
        PyDict args = new PyDict();
        args.put(new PyString("page"), PyInt.valueOf(2));
        PyRequest request = new PyRequest(
                "POST", "/proxy", args, new PyDict(), new PyDict(), PyNone.INSTANCE);
        PyDict session = new PyDict();

        FlaskExecutionContext.Scope appScope = fixture.services.getFlask().enterApp(app);
        try {
            FlaskExecutionContext.Scope requestScope = fixture.services.getFlask()
                    .enterRequest(request, session);
            try {
                equal(new PyString("POST"), RuntimeOps.loadAttribute(
                        export(fixture, "request"), "method", fixture.context),
                        "request proxy attribute");
                RuntimeOps.storeSubscript(export(fixture, "session"),
                        new PyString("user"), new PyString("Ali"), fixture.context);
                equal(new PyString("Ali"), RuntimeOps.loadSubscript(
                        export(fixture, "session"), new PyString("user"), fixture.context),
                        "session proxy subscription");
                RuntimeOps.storeAttribute(export(fixture, "g"), "value",
                        PyInt.valueOf(9), fixture.context);
                equal(PyInt.valueOf(9), RuntimeOps.loadAttribute(
                        export(fixture, "g"), "value", fixture.context),
                        "g proxy attribute");
                same(app, RuntimeOps.resolveDynamic(
                        export(fixture, "current_app"), fixture.context),
                        "current_app proxy");
            } finally {
                requestScope.close();
            }
        } finally {
            appScope.close();
        }

        for (String proxy : Arrays.asList("request", "session", "g", "current_app")) {
            expectVm("RuntimeError", () -> RuntimeOps.resolveDynamic(
                    export(fixture, proxy), fixture.context));
        }
    }

    private static void testFileCapabilities() throws Exception {
        Fixture denied = fixture(VmCapabilities.NONE);
        expectVm("PermissionError", () -> call(denied, "send_file",
                values(new PyString("docs/readme.txt")), keywords()));
        expectVm("PermissionError", () -> call(denied, "send_from_directory",
                values(new PyString("docs"), new PyString("readme.txt")), keywords()));

        List<String> requestedPaths = new ArrayList<>();
        Fixture allowed = fixture(VmCapabilities.withFileSystemRead(path -> {
            requestedPaths.add(path);
            return "content:" + path.replace('\\', '/');
        }));
        PyResponse direct = (PyResponse) call(allowed, "send_file",
                values(new PyString("docs/readme.txt")), keywords());
        equal(new PyString("content:docs/readme.txt"), direct.getBody(),
                "send_file body");

        PyResponse contained = (PyResponse) call(allowed, "send_from_directory",
                values(new PyString("docs"), new PyString("nested/file.txt")), keywords());
        check(((PyString) contained.getBody()).getValue().endsWith(
                        "docs/nested/file.txt"),
                "send_from_directory resolved path");
        equal(2, requestedPaths.size(), "filesystem capability call count");
        expectVm("PermissionError", () -> call(allowed, "send_from_directory",
                values(new PyString("docs"), new PyString("../secret.txt")), keywords()));
        equal(2, requestedPaths.size(), "traversal reached filesystem capability");

        Path root = Files.createTempDirectory(
                java.nio.file.Paths.get(".tmp"), "flask-send-root-");
        Path inside = root.resolve("inside.txt");
        Path outside = root.resolveSibling(root.getFileName() + "-outside.txt");
        Files.write(inside, Collections.singletonList("inside"), StandardCharsets.UTF_8);
        Files.write(outside, Collections.singletonList("outside"), StandardCharsets.UTF_8);
        Path symlink = root.resolve("escape-link.txt");
        boolean symlinkCreated = false;
        try {
            Fixture rooted = fixture(VmCapabilities.rootedFileSystemRead(root));
            PyResponse insideResponse = (PyResponse) call(rooted, "send_file",
                    values(new PyString("inside.txt")), keywords());
            check(((PyString) insideResponse.getBody()).getValue().contains("inside"),
                    "rooted send_file content");
            expectVm("PermissionError", () -> call(rooted, "send_file",
                    values(new PyString("../" + outside.getFileName())), keywords()));
            expectVm("PermissionError", () -> call(rooted, "send_file",
                    values(new PyString(outside.toAbsolutePath().toString())), keywords()));
            try {
                Files.createSymbolicLink(symlink, outside.toAbsolutePath());
                symlinkCreated = true;
                expectVm("PermissionError", () -> call(rooted, "send_file",
                        values(new PyString("escape-link.txt")), keywords()));
            } catch (UnsupportedOperationException | java.io.IOException
                    | SecurityException unavailable) {
                // The canonical implementation is still exercised where the
                // host permits symlink creation (often restricted on Windows).
            }
        } finally {
            if (symlinkCreated) Files.deleteIfExists(symlink);
            Files.deleteIfExists(inside);
            Files.deleteIfExists(outside);
            Files.deleteIfExists(root);
        }
    }

    private static void testAppContextManager() {
        Fixture fixture = fixture(VmCapabilities.NONE);
        PyFlaskApp app = flaskApp(fixture, "context_app");
        PyValue managerValue = fixture.context.invoke(
                RuntimeOps.loadAttribute(app, "app_context"), values(), keywords());
        check(managerValue instanceof PyAppContextManager,
                "app_context did not return context manager");
        PyValue enter = RuntimeOps.loadAttribute(managerValue, "__enter__");
        PyValue exit = RuntimeOps.loadAttribute(managerValue, "__exit__");

        same(app, fixture.context.invoke(enter, values(), keywords()),
                "app context enter value");
        same(app, RuntimeOps.resolveDynamic(
                export(fixture, "current_app"), fixture.context),
                "current_app inside app context");
        RuntimeOps.storeAttribute(export(fixture, "g"), "temporary",
                PyInt.ONE, fixture.context);
        equal(PyBool.FALSE, fixture.context.invoke(exit,
                values(PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE), keywords()),
                "app context exit suppression");
        expectVm("RuntimeError", () -> RuntimeOps.resolveDynamic(
                export(fixture, "current_app"), fixture.context));

        fixture.context.invoke(enter, values(), keywords());
        try {
            expectVm("AttributeError", () -> RuntimeOps.loadAttribute(
                    export(fixture, "g"), "temporary", fixture.context));
        } finally {
            fixture.context.invoke(exit,
                    values(PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE), keywords());
        }
        check(!fixture.services.getFlask().hasApp(), "app context leaked after exit");
    }

    private static void testContextFailureAndIsolation() {
        PyFlaskApp app = new PyFlaskApp("retry_context");
        FlaskExecutionContext flask = new FlaskExecutionContext();
        FlaskExecutionContext.Scope appScope = flask.enterApp(app);
        FlaskExecutionContext.Scope requestScope = flask.enterRequest(
                new PyRequest("GET", "/"), new PyDict());
        try {
            try {
                appScope.close();
                throw new AssertionError("app context closed with active request");
            } catch (IllegalStateException expected) {
                check(flask.hasApp(), "failed close removed app state");
            }
            requestScope.close();
            appScope.close();
            check(!flask.hasApp(), "retry close left app context active");
        } finally {
            if (flask.hasRequest()) requestScope.close();
            if (flask.hasApp()) appScope.close();
        }

        RuntimeServices first = RuntimeServices.unavailable();
        FlaskExecutionContext.Scope firstScope = first.getFlask().enterApp(app);
        try {
            RuntimeServices second = RuntimeServices.unavailable();
            check(!second.getFlask().hasApp(),
                    "unavailable RuntimeServices leaked mutable Flask state");
        } finally {
            firstScope.close();
        }
    }

    private static PyFlaskApp flaskApp(Fixture fixture, String name) {
        return (PyFlaskApp) call(fixture, "Flask",
                values(new PyString(name)), keywords());
    }

    private static PyValue call(
            Fixture fixture, String exportName,
            List<PyValue> positional, Map<String, PyValue> keywords) {
        return fixture.context.invoke(
                export(fixture, exportName), positional, keywords);
    }

    private static PyValue export(Fixture fixture, String name) {
        return fixture.module.find(name).orElseThrow(() ->
                new AssertionError("missing flask export " + name));
    }

    private static Fixture fixture(VmCapabilities capabilities) {
        ModuleRegistry registry = FlaskNativeProviders.register(
                ModuleRegistry.builder()).build();
        PyModule module = new ModuleLoader(registry).load("flask");
        RuntimeServices services = new RuntimeServices(capabilities);
        return new Fixture(module, services, new RecordingContext(services));
    }

    private static List<PyValue> values(PyValue... values) {
        return Arrays.asList(values);
    }

    private static Map<String, PyValue> keywords(Object... pairs) {
        if ((pairs.length & 1) != 0) {
            throw new IllegalArgumentException("keyword pairs must be even");
        }
        LinkedHashMap<String, PyValue> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], (PyValue) pairs[index + 1]);
        }
        return result;
    }

    private static void expectVm(String typeName, ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("expected " + typeName);
        } catch (VmRuntimeException failure) {
            equal(typeName, failure.getExceptionValue().getExceptionTypeName(),
                    "runtime error type");
        } catch (Exception other) {
            throw new AssertionError("expected " + typeName + " but got " + other, other);
        }
    }

    private static void run(String name, ThrowingRunnable test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS: " + name);
        } catch (Throwable failure) {
            failed++;
            System.err.println("FAIL: " + name + " -> " + failure.getMessage());
            failure.printStackTrace(System.err);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void same(Object expected, Object actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": identities differ");
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                    label + ": expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static final class Fixture {
        private final PyModule module;
        private final RuntimeServices services;
        private final RecordingContext context;
        private Fixture(
                PyModule module, RuntimeServices services, RecordingContext context) {
            this.module = module;
            this.services = services;
            this.context = context;
        }
    }

    private static final class RecordingContext implements VmCallContext {
        private final RuntimeServices services;
        private final Namespace globals = new Namespace();
        private final Namespace locals = new Namespace();
        private final StringBuilder stdout = new StringBuilder();

        private RecordingContext(RuntimeServices services) {
            this.services = services;
        }

        @Override
        public PyValue invoke(
                PyValue callable,
                List<PyValue> positionalArguments,
                Map<String, PyValue> keywordArguments) {
            PyValue resolved = RuntimeOps.resolveDynamic(callable, this);
            if (!(resolved instanceof PyCallable)) {
                throw RuntimeOps.error("TypeError", "object of type '"
                        + resolved.getTypeName() + "' is not callable");
            }
            return ((PyCallable) resolved).call(
                    this,
                    Collections.unmodifiableList(new ArrayList<>(positionalArguments)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(keywordArguments)));
        }

        @Override
        public PyValue executeFunction(
                PyFunction function,
                List<PyValue> positionalArguments,
                Map<String, PyValue> keywordArguments) {
            throw new AssertionError(
                    "This native-adapter harness does not execute bytecode functions");
        }

        @Override public Namespace getCurrentGlobals() { return globals; }
        @Override public Namespace getCurrentLocals() { return locals; }
        @Override public void writeStdout(String text) { stdout.append(text); }
        @Override public RuntimeServices getRuntimeServices() { return services; }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
