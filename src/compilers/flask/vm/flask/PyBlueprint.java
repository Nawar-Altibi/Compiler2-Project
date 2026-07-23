package compilers.flask.vm.flask;

import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.values.PyAttributeProvider;
import compilers.flask.vm.values.PyCallable;
import compilers.flask.vm.values.PyNativeFunction;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Route collection merged into a Flask app by explicit registration. */
public final class PyBlueprint implements PyValue, PyAttributeProvider {
    private static final class Definition {
        private final String path;
        private final List<String> methods;
        private final String endpoint;
        private final PyValue callable;
        private Definition(String path, List<String> methods, String endpoint, PyValue callable) {
            this.path = path; this.methods = methods;
            this.endpoint = endpoint; this.callable = callable;
        }
    }

    private final String name;
    private final String importName;
    private final LinkedHashMap<String, Definition> definitions = new LinkedHashMap<>();

    public PyBlueprint(String name, String importName) {
        if (name == null || name.isEmpty() || importName == null || importName.isEmpty()) {
            throw RuntimeOps.error("ValueError", "Blueprint names cannot be empty");
        }
        this.name = name;
        this.importName = importName;
    }

    @Override
    public Optional<PyValue> findAttribute(String attribute) {
        if ("name".equals(attribute)) return Optional.<PyValue>of(new PyString(name));
        if ("import_name".equals(attribute)) return Optional.<PyValue>of(new PyString(importName));
        if (!"route".equals(attribute)) return Optional.empty();
        return Optional.<PyValue>of(new PyNativeFunction("route", (context, positional, keywords) -> {
            if (positional.size() != 1) {
                throw RuntimeOps.error("TypeError", "route() expects one path argument");
            }
            String path = PyFlaskApp.string(positional.get(0), "route path");
            PyFlaskApp.rejectUnknownKeywords("route", keywords,
                    Collections.singleton("methods"));
            List<String> methods = PyFlaskApp.parseMethods(keywords.get("methods"));
            return new PyNativeFunction("route_decorator", (callContext, values, named) -> {
                PyFlaskApp.requireArity("route decorator", values, named, 1);
                PyValue callable = values.get(0);
                if (!(callable instanceof PyCallable)) {
                    throw RuntimeOps.error("TypeError", "route decorator requires a callable");
                }
                String endpoint = PyFlaskApp.callableName(callable);
                if (definitions.containsKey(endpoint)) {
                    throw RuntimeOps.error("RuntimeError", "duplicate blueprint endpoint '"
                            + endpoint + "'");
                }
                definitions.put(endpoint, new Definition(path,
                        Collections.unmodifiableList(new ArrayList<>(methods)),
                        endpoint, callable));
                return callable;
            });
        }));
    }

    void registerInto(PyFlaskApp app, String prefix) {
        String normalizedPrefix = prefix == null ? "" : prefix;
        if (!normalizedPrefix.isEmpty() && normalizedPrefix.charAt(0) != '/') {
            throw RuntimeOps.error("ValueError", "url_prefix must start with '/'");
        }
        for (Definition definition : definitions.values()) {
            app.registerRoute(normalizedPrefix + definition.path,
                    definition.methods, name + "." + definition.endpoint,
                    definition.callable);
        }
    }

    @Override public String getTypeName() { return "Blueprint"; }
    @Override public String repr() { return "<Blueprint " + new PyString(name).repr() + ">"; }
    @Override public String toString() { return repr(); }
}
