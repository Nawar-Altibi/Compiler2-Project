package compilers.flask.vm.modules;

import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.values.PyBool;
import compilers.flask.vm.values.PyList;
import compilers.flask.vm.values.PyNativeFunction;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Minimal explicit, capability-backed {@code os}/{@code os.path} fixture API. */
public final class OsNativeProviders {
    private OsNativeProviders() {
    }

    public static ModuleRegistry.Builder register(ModuleRegistry.Builder builder) {
        builder.registerNative("os", (module, loader) -> {
            module.put("path", loader.load("os.path"));
            module.put("sep", new PyString("/"));
            module.put("makedirs", new PyNativeFunction(
                    "makedirs", (context, positional, keywords) -> {
                        if (positional.size() < 1 || positional.size() > 2) {
                            throw typeError("makedirs() expects path and optional mode");
                        }
                        for (String keyword : keywords.keySet()) {
                            if (!keyword.equals("mode") && !keyword.equals("exist_ok")) {
                                throw typeError("makedirs() got an unexpected keyword '"
                                        + keyword + "'");
                            }
                        }
                        string(positional.get(0), "makedirs path");
                        throw RuntimeOps.error(
                                "PermissionError",
                                "filesystem write capability is not available");
                    }));
            module.put("__all__", strings("path", "sep", "makedirs"));
        });
        return builder.registerNative("os.path", (module, loader) -> {
            module.put("join", new PyNativeFunction(
                    "join", (context, positional, keywords) -> {
                        if (positional.isEmpty() || !keywords.isEmpty()) {
                            throw typeError("join() expects one or more path components");
                        }
                        List<String> parts = new ArrayList<>(positional.size());
                        for (PyValue value : positional) {
                            parts.add(string(value, "path component"));
                        }
                        return new PyString(join(parts));
                    }));
            module.put("exists", new PyNativeFunction(
                    "exists", (context, positional, keywords) -> {
                        if (positional.size() != 1 || !keywords.isEmpty()) {
                            throw typeError("exists() expects exactly one path");
                        }
                        String path = string(positional.get(0), "exists path");
                        return PyBool.valueOf(context.getRuntimeServices()
                                .getCapabilities().exists(path));
                    }));
            module.put("__all__", strings("join", "exists"));
        });
    }

    private static PyList strings(String... values) {
        List<PyValue> result = new ArrayList<>(values.length);
        for (String value : values) result.add(new PyString(value));
        return new PyList(result);
    }

    private static String join(List<String> parts) {
        String result = parts.get(0);
        for (int index = 1; index < parts.size(); index++) {
            String part = parts.get(index);
            if (isAbsolute(part)) {
                result = part;
            } else if (result.isEmpty() || result.endsWith("/") || result.endsWith("\\")) {
                result += part;
            } else {
                result += "/" + part;
            }
        }
        return result;
    }

    private static boolean isAbsolute(String path) {
        return path.startsWith("/") || path.startsWith("\\")
                || (path.length() >= 3
                && Character.isLetter(path.charAt(0))
                && path.charAt(1) == ':'
                && (path.charAt(2) == '/' || path.charAt(2) == '\\'));
    }

    private static String string(PyValue value, String label) {
        if (!(value instanceof PyString)) {
            throw typeError(label + " must be str");
        }
        return ((PyString) value).getValue();
    }

    private static compilers.flask.vm.VmRuntimeException typeError(String message) {
        return RuntimeOps.error("TypeError", message);
    }
}
