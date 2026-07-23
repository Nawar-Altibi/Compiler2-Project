package compilers.flask.vm.flask;

import compilers.flask.runtime.RuntimeSymbolManifest;
import compilers.flask.runtime.RuntimeSymbolSpec;
import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.RuntimeProviderRegistry;
import compilers.flask.vm.VmCallContext;
import compilers.flask.vm.modules.ModuleRegistry;
import compilers.flask.vm.values.PyBool;
import compilers.flask.vm.values.PyDict;
import compilers.flask.vm.values.PyFloat;
import compilers.flask.vm.values.PyInt;
import compilers.flask.vm.values.PyList;
import compilers.flask.vm.values.PyNativeClass;
import compilers.flask.vm.values.PyNativeFunction;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyTuple;
import compilers.flask.vm.values.PyValue;

import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Concrete providers for every symbol advertised by the native flask module. */
public final class FlaskNativeProviders {
    private FlaskNativeProviders() {
    }

    public static ModuleRegistry.Builder register(ModuleRegistry.Builder builder) {
        return builder.registerNative(RuntimeSymbolManifest.FLASK_MODULE, (module, loader) -> {
            for (Map.Entry<String, PyValue> provider
                    : createRegistry().moduleProviders(
                            RuntimeSymbolManifest.FLASK_MODULE).entrySet()) {
                module.put(provider.getKey(), provider.getValue());
            }
        });
    }

    /** Builds the concrete provider-ID registry for all Flask exports. */
    public static RuntimeProviderRegistry createRegistry() {
        RuntimeProviderRegistry.Builder providers = RuntimeProviderRegistry.builder();
        PyNativeClass flaskClass = new PyNativeClass(
                "Flask", Collections.<PyNativeClass>emptyList(),
                (context, positional, keywords) -> {
                    PyFlaskApp.requireArity("Flask", positional, keywords, 1);
                    return new PyFlaskApp(PyFlaskApp.string(
                            positional.get(0), "Flask import name"));
                });
        PyNativeClass blueprintClass = new PyNativeClass(
                "Blueprint", Collections.<PyNativeClass>emptyList(),
                (context, positional, keywords) -> {
                    PyFlaskApp.requireArity("Blueprint", positional, keywords, 2);
                    return new PyBlueprint(
                            PyFlaskApp.string(positional.get(0), "Blueprint name"),
                            PyFlaskApp.string(positional.get(1), "Blueprint import name"));
                });
        PyNativeClass responseClass = new PyNativeClass(
                "Response", Collections.<PyNativeClass>emptyList(),
                FlaskNativeProviders::response);

        registerProvider(providers, "Flask", flaskClass);
        registerProvider(providers, "Blueprint", blueprintClass);
        registerProvider(providers, "Response", responseClass);
        registerProvider(providers, "render_template", nativeFunction(
                "render_template", FlaskNativeProviders::renderTemplate));
        registerProvider(providers, "redirect", nativeFunction(
                "redirect", FlaskNativeProviders::redirect));
        registerProvider(providers, "jsonify", nativeFunction(
                "jsonify", FlaskNativeProviders::jsonify));
        registerProvider(providers, "abort", nativeFunction(
                "abort", FlaskNativeProviders::abort));
        registerProvider(providers, "make_response", nativeFunction(
                "make_response", FlaskNativeProviders::makeResponse));
        registerProvider(providers, "url_for", nativeFunction(
                "url_for", FlaskNativeProviders::urlFor));
        registerProvider(providers, "flash", nativeFunction(
                "flash", FlaskNativeProviders::flash));
        registerProvider(providers, "send_file", nativeFunction(
                "send_file", FlaskNativeProviders::sendFile));
        registerProvider(providers, "send_from_directory", nativeFunction(
                "send_from_directory", FlaskNativeProviders::sendFromDirectory));
        registerProvider(providers, "request",
                new PyContextProxy(PyContextProxy.Kind.REQUEST));
        registerProvider(providers, "session",
                new PyContextProxy(PyContextProxy.Kind.SESSION));
        registerProvider(providers, "g", new PyContextProxy(PyContextProxy.Kind.G));
        registerProvider(providers, "current_app",
                new PyContextProxy(PyContextProxy.Kind.CURRENT_APP));

        RuntimeProviderRegistry registry = providers.build();
        registry.moduleProviders(RuntimeSymbolManifest.FLASK_MODULE);
        return registry;
    }

    private static void registerProvider(
            RuntimeProviderRegistry.Builder providers,
            String name,
            PyValue value) {
        RuntimeSymbolSpec spec = RuntimeSymbolManifest.lookup(
                RuntimeSymbolManifest.FLASK_MODULE, name);
        if (spec == null) {
            throw new IllegalStateException("Flask provider is not declared: " + name);
        }
        providers.register(spec, value);
    }

    private static PyNativeFunction nativeFunction(
            String name, PyNativeFunction.Body body) {
        return new PyNativeFunction(name, body);
    }

    private static PyValue response(
            VmCallContext context, List<PyValue> positional,
            Map<String, PyValue> keywords) {
        BoundResponse bound = bindResponse("Response", positional, keywords);
        return new PyResponse(bound.body, bound.status, bound.headers, bound.contentType);
    }

    private static PyValue renderTemplate(
            VmCallContext context, List<PyValue> positional,
            Map<String, PyValue> keywords) {
        if (positional.size() != 1) {
            throw RuntimeOps.error("TypeError",
                    "render_template() expects one template name");
        }
        PyDict values = new PyDict();
        for (Map.Entry<String, PyValue> entry : keywords.entrySet()) {
            values.put(new PyString(entry.getKey()), entry.getValue());
        }
        return new PyTemplateRenderRequest(
                PyFlaskApp.string(positional.get(0), "template name"), values);
    }

    private static PyValue redirect(
            VmCallContext context, List<PyValue> positional,
            Map<String, PyValue> keywords) {
        if (positional.size() < 1 || positional.size() > 2) {
            throw RuntimeOps.error("TypeError", "redirect() expects location and optional code");
        }
        PyFlaskApp.rejectUnknownKeywords("redirect", keywords,
                Collections.singleton("code"));
        String location = PyFlaskApp.string(positional.get(0), "redirect location");
        PyValue codeValue = positional.size() == 2
                ? positional.get(1) : keywords.get("code");
        int code = codeValue == null ? 302 : integer(codeValue, "redirect code");
        PyDict headers = new PyDict();
        headers.put(new PyString("Location"), new PyString(location));
        return new PyResponse(new PyString(""), code, headers,
                "text/html; charset=utf-8");
    }

    private static PyValue urlFor(
            VmCallContext context, List<PyValue> positional,
            Map<String, PyValue> keywords) {
        if (positional.size() != 1) {
            throw RuntimeOps.error("TypeError", "url_for() expects one endpoint");
        }
        String endpoint = PyFlaskApp.string(positional.get(0), "endpoint");
        return new PyString(context.getRuntimeServices().getFlask()
                .currentApp().urlFor(endpoint, keywords));
    }

    private static PyValue flash(
            VmCallContext context, List<PyValue> positional,
            Map<String, PyValue> keywords) {
        if (positional.size() < 1 || positional.size() > 2) {
            throw RuntimeOps.error("TypeError", "flash() expects message and optional category");
        }
        PyFlaskApp.rejectUnknownKeywords("flash", keywords,
                Collections.singleton("category"));
        PyValue categoryValue = positional.size() == 2
                ? positional.get(1) : keywords.get("category");
        String category = categoryValue == null
                ? "message" : PyFlaskApp.string(categoryValue, "flash category");
        PyDict session = context.getRuntimeServices().getFlask().currentSession();
        PyString key = new PyString("_flashes");
        PyValue existing = session.find(key).orElse(null);
        PyList flashes;
        if (existing == null) {
            flashes = new PyList();
            session.put(key, flashes);
        } else if (existing instanceof PyList) {
            flashes = (PyList) existing;
        } else {
            throw RuntimeOps.error("TypeError", "session _flashes must be a list");
        }
        flashes.append(new PyTuple(java.util.Arrays.<PyValue>asList(
                new PyString(category), positional.get(0))));
        return PyNone.INSTANCE;
    }

    private static PyValue jsonify(
            VmCallContext context, List<PyValue> positional,
            Map<String, PyValue> keywords) {
        PyValue payload;
        if (!positional.isEmpty() && !keywords.isEmpty()) {
            throw RuntimeOps.error("TypeError",
                    "jsonify() cannot mix positional and keyword values");
        }
        if (!keywords.isEmpty()) {
            PyDict object = new PyDict();
            for (Map.Entry<String, PyValue> entry : keywords.entrySet()) {
                object.put(new PyString(entry.getKey()), entry.getValue());
            }
            payload = object;
        } else if (positional.size() == 1) {
            payload = positional.get(0);
        } else {
            payload = new PyList(positional);
        }
        String json = json(payload, new IdentityHashMap<PyValue, Boolean>());
        return new PyResponse(new PyString(json), 200, new PyDict(),
                "application/json");
    }

    private static PyValue abort(
            VmCallContext context, List<PyValue> positional,
            Map<String, PyValue> keywords) {
        PyFlaskApp.requireArity("abort", positional, keywords, 1);
        int status = integer(positional.get(0), "abort status");
        throw RuntimeOps.error("HTTPException", "HTTP " + status);
    }

    private static PyValue makeResponse(
            VmCallContext context, List<PyValue> positional,
            Map<String, PyValue> keywords) {
        if (positional.isEmpty()) {
            throw RuntimeOps.error("TypeError", "make_response() expects a value");
        }
        if (positional.size() == 1 && keywords.isEmpty()) {
            return FlaskResponseNormalizer.normalize(positional.get(0));
        }
        BoundResponse bound = bindResponse("make_response", positional, keywords);
        return new PyResponse(bound.body, bound.status, bound.headers, bound.contentType);
    }

    private static PyValue sendFile(
            VmCallContext context, List<PyValue> positional,
            Map<String, PyValue> keywords) {
        PyFlaskApp.requireArity("send_file", positional, keywords, 1);
        String path = PyFlaskApp.string(positional.get(0), "file path");
        String content = context.getRuntimeServices().getCapabilities().readText(path);
        return new PyResponse(new PyString(content), 200, new PyDict(),
                "application/octet-stream");
    }

    private static PyValue sendFromDirectory(
            VmCallContext context, List<PyValue> positional,
            Map<String, PyValue> keywords) {
        PyFlaskApp.requireArity("send_from_directory", positional, keywords, 2);
        String directory = PyFlaskApp.string(positional.get(0), "directory");
        String name = PyFlaskApp.string(positional.get(1), "file name");
        Path relative = Paths.get(name).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw RuntimeOps.error("PermissionError", "file escapes configured directory");
        }
        Path root = Paths.get(directory).normalize();
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw RuntimeOps.error("PermissionError", "file escapes configured directory");
        }
        String content = context.getRuntimeServices().getCapabilities()
                .readText(candidate.toString());
        return new PyResponse(new PyString(content), 200, new PyDict(),
                "application/octet-stream");
    }

    private static BoundResponse bindResponse(
            String function, List<PyValue> positional,
            Map<String, PyValue> keywords) {
        if (positional.size() > 3) {
            throw RuntimeOps.error("TypeError", function + "() accepts at most 3 arguments");
        }
        PyFlaskApp.rejectUnknownKeywords(function, keywords,
                new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                        "body", "status", "headers", "content_type")));
        PyValue body = positional.size() >= 1 ? positional.get(0)
                : keywords.getOrDefault("body", PyNone.INSTANCE);
        PyValue statusValue = positional.size() >= 2 ? positional.get(1)
                : keywords.get("status");
        PyValue headersValue = positional.size() >= 3 ? positional.get(2)
                : keywords.get("headers");
        int status = statusValue == null ? 200 : integer(statusValue, "status");
        PyDict headers;
        if (headersValue == null || headersValue == PyNone.INSTANCE) {
            headers = new PyDict();
        } else if (headersValue instanceof PyDict) {
            headers = (PyDict) headersValue;
        } else {
            throw RuntimeOps.error("TypeError", "headers must be dict");
        }
        String contentType = keywords.containsKey("content_type")
                ? PyFlaskApp.string(keywords.get("content_type"), "content_type")
                : null;
        return new BoundResponse(body, status, headers, contentType);
    }

    private static int integer(PyValue value, String label) {
        BigInteger number;
        if (value instanceof PyInt) number = ((PyInt) value).getValue();
        else if (value instanceof PyBool) number = ((PyBool) value).getValue()
                ? BigInteger.ONE : BigInteger.ZERO;
        else throw RuntimeOps.error("TypeError", label + " must be int");
        try {
            return number.intValueExact();
        } catch (ArithmeticException overflow) {
            throw RuntimeOps.error("OverflowError", label + " is too large");
        }
    }

    private static String json(PyValue value, IdentityHashMap<PyValue, Boolean> active) {
        if (value == PyNone.INSTANCE) return "null";
        if (value instanceof PyBool) return ((PyBool) value).getValue() ? "true" : "false";
        if (value instanceof PyInt) return ((PyInt) value).getValue().toString();
        if (value instanceof PyFloat) {
            double number = ((PyFloat) value).getValue();
            if (!Double.isFinite(number)) {
                throw RuntimeOps.error("ValueError", "non-finite float is not valid JSON");
            }
            return Double.toString(number);
        }
        if (value instanceof PyString) return quote(((PyString) value).getValue());
        if (active.put(value, Boolean.TRUE) != null) {
            throw RuntimeOps.error("ValueError", "circular reference in JSON value");
        }
        try {
            if (value instanceof PyList || value instanceof PyTuple) {
                List<PyValue> elements = value instanceof PyList
                        ? ((PyList) value).getElements()
                        : ((PyTuple) value).getElements();
                StringBuilder result = new StringBuilder("[");
                for (int index = 0; index < elements.size(); index++) {
                    if (index != 0) result.append(',');
                    result.append(json(elements.get(index), active));
                }
                return result.append(']').toString();
            }
            if (value instanceof PyDict) {
                StringBuilder result = new StringBuilder("{");
                int index = 0;
                for (PyDict.Entry entry : ((PyDict) value).getEntries()) {
                    if (!(entry.getKey() instanceof PyString)) {
                        throw RuntimeOps.error("TypeError", "JSON object keys must be strings");
                    }
                    if (index++ != 0) result.append(',');
                    result.append(quote(((PyString) entry.getKey()).getValue()))
                            .append(':').append(json(entry.getValue(), active));
                }
                return result.append('}').toString();
            }
            throw RuntimeOps.error("TypeError", "object of type '"
                    + value.getTypeName() + "' is not JSON serializable");
        } finally {
            active.remove(value);
        }
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int offset = 0; offset < value.length(); offset++) {
            char character = value.charAt(offset);
            switch (character) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else result.append(character);
            }
        }
        return result.append('"').toString();
    }

    private static final class BoundResponse {
        private final PyValue body;
        private final int status;
        private final PyDict headers;
        private final String contentType;
        private BoundResponse(PyValue body, int status, PyDict headers, String contentType) {
            this.body = body; this.status = status;
            this.headers = headers; this.contentType = contentType;
        }
    }
}
