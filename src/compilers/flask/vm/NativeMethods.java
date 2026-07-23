package compilers.flask.vm;

import compilers.flask.vm.values.PyDict;
import compilers.flask.vm.values.PyInt;
import compilers.flask.vm.values.PyList;
import compilers.flask.vm.values.PyNativeFunction;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyTuple;
import compilers.flask.vm.values.PyValue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Explicit non-reflective native methods for supported container/string values. */
final class NativeMethods {
    private NativeMethods() {
    }

    static Optional<PyValue> find(PyValue receiver, String name) {
        if (receiver instanceof PyList) return list((PyList) receiver, name);
        if (receiver instanceof PyDict) return dict((PyDict) receiver, name);
        if (receiver instanceof PyString) return string((PyString) receiver, name);
        return Optional.empty();
    }

    private static Optional<PyValue> list(PyList receiver, String name) {
        switch (name) {
            case "append": return method(name, (context, p, k) -> {
                exact(name, p, k, 1); receiver.append(p.get(0)); return PyNone.INSTANCE;
            });
            case "extend": return method(name, (context, p, k) -> {
                exact(name, p, k, 1);
                receiver.extend(drain(RuntimeOps.iter(
                        RuntimeOps.resolveDynamic(p.get(0), context))));
                return PyNone.INSTANCE;
            });
            case "clear": return method(name, (context, p, k) -> {
                exact(name, p, k, 0); receiver.clear(); return PyNone.INSTANCE;
            });
            case "pop": return method(name, (context, p, k) -> {
                if (!k.isEmpty() || p.size() > 1) type(name, "expects at most one index");
                if (receiver.isEmpty()) throw RuntimeOps.error("IndexError", "pop from empty list");
                int index = p.isEmpty() ? receiver.size() - 1
                        : index(p.get(0), receiver.size());
                return receiver.remove(index);
            });
            default: return Optional.empty();
        }
    }

    private static Optional<PyValue> dict(PyDict receiver, String name) {
        switch (name) {
            case "get": return method(name, (context, p, k) -> {
                if (!k.isEmpty() || p.size() < 1 || p.size() > 2) {
                    type(name, "expects key and optional default");
                }
                PyValue fallback = p.size() == 2 ? p.get(1) : PyNone.INSTANCE;
                return receiver.find(p.get(0)).orElse(fallback);
            });
            case "keys": return method(name, (context, p, k) -> {
                exact(name, p, k, 0); return new PyList(receiver.getKeys());
            });
            case "values": return method(name, (context, p, k) -> {
                exact(name, p, k, 0);
                List<PyValue> values = new ArrayList<>();
                for (PyDict.Entry entry : receiver.getEntries()) values.add(entry.getValue());
                return new PyList(values);
            });
            case "items": return method(name, (context, p, k) -> {
                exact(name, p, k, 0);
                List<PyValue> items = new ArrayList<>();
                for (PyDict.Entry entry : receiver.getEntries()) {
                    items.add(new PyTuple(java.util.Arrays.asList(
                            entry.getKey(), entry.getValue())));
                }
                return new PyList(items);
            });
            case "update": return method(name, (context, p, k) -> {
                if (p.size() > 1) type(name, "expects at most one mapping");
                if (!p.isEmpty()) {
                    PyValue source = RuntimeOps.resolveDynamic(p.get(0), context);
                    if (!(source instanceof PyDict)) type(name, "requires a dict");
                    for (PyDict.Entry entry : ((PyDict) source).getEntries()) {
                        receiver.put(entry.getKey(), entry.getValue());
                    }
                }
                for (Map.Entry<String, PyValue> entry : k.entrySet()) {
                    receiver.put(new PyString(entry.getKey()), entry.getValue());
                }
                return PyNone.INSTANCE;
            });
            default: return Optional.empty();
        }
    }

    private static Optional<PyValue> string(PyString receiver, String name) {
        String value = receiver.getValue();
        switch (name) {
            case "lower": return noArgString(name, value.toLowerCase(java.util.Locale.ROOT));
            case "upper": return noArgString(name, value.toUpperCase(java.util.Locale.ROOT));
            case "strip": return method(name, (context, p, k) -> {
                exact(name, p, k, 0); return new PyString(value.trim());
            });
            case "startswith": return predicate(name, value, true);
            case "endswith": return predicate(name, value, false);
            case "split": return method(name, (context, p, k) -> {
                if (!k.isEmpty() || p.size() > 1) type(name, "expects an optional separator");
                String[] parts;
                if (p.isEmpty() || p.get(0) == PyNone.INSTANCE) {
                    String trimmed = value.trim();
                    parts = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
                } else {
                    String separator = text(p.get(0), "separator");
                    if (separator.isEmpty()) throw RuntimeOps.error("ValueError", "empty separator");
                    parts = value.split(java.util.regex.Pattern.quote(separator), -1);
                }
                List<PyValue> result = new ArrayList<>();
                for (String part : parts) result.add(new PyString(part));
                return new PyList(result);
            });
            case "join": return method(name, (context, p, k) -> {
                exact(name, p, k, 1);
                List<PyValue> values = drain(RuntimeOps.iter(
                        RuntimeOps.resolveDynamic(p.get(0), context)));
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < values.size(); i++) {
                    if (i != 0) result.append(value);
                    result.append(text(values.get(i), "sequence item"));
                }
                return new PyString(result.toString());
            });
            case "replace": return method(name, (context, p, k) -> {
                exact(name, p, k, 2);
                return new PyString(value.replace(
                        text(p.get(0), "old"), text(p.get(1), "new")));
            });
            default: return Optional.empty();
        }
    }

    private static Optional<PyValue> predicate(
            String name, String receiver, boolean starts) {
        return method(name, (context, p, k) -> {
            exact(name, p, k, 1);
            String needle = text(p.get(0), "prefix/suffix");
            return compilers.flask.vm.values.PyBool.valueOf(
                    starts ? receiver.startsWith(needle) : receiver.endsWith(needle));
        });
    }

    private static Optional<PyValue> noArgString(String name, String result) {
        return method(name, (context, p, k) -> {
            exact(name, p, k, 0); return new PyString(result);
        });
    }

    private static Optional<PyValue> method(String name, PyNativeFunction.Body body) {
        return Optional.<PyValue>of(new PyNativeFunction(name, body));
    }

    private static void exact(
            String name, List<PyValue> p, Map<String, PyValue> k, int count) {
        if (p.size() != count || !k.isEmpty()) type(name, "expects " + count + " argument(s)");
    }

    private static void type(String name, String message) {
        throw RuntimeOps.error("TypeError", name + "() " + message);
    }

    private static List<PyValue> drain(compilers.flask.vm.values.PyIterator iterator) {
        List<PyValue> result = new ArrayList<>();
        Optional<PyValue> next;
        while ((next = iterator.tryNext()).isPresent()) result.add(next.get());
        return result;
    }

    private static String text(PyValue value, String label) {
        if (!(value instanceof PyString)) type(label, "must be str");
        return ((PyString) value).getValue();
    }

    private static int index(PyValue value, int size) {
        if (!(value instanceof PyInt)) type("index", "must be int");
        final int raw;
        try { raw = ((PyInt) value).getValue().intValueExact(); }
        catch (ArithmeticException overflow) {
            throw RuntimeOps.error("IndexError", "list index out of range");
        }
        int normalized = raw < 0 ? raw + size : raw;
        if (normalized < 0 || normalized >= size) {
            throw RuntimeOps.error("IndexError", "list index out of range");
        }
        return normalized;
    }
}
