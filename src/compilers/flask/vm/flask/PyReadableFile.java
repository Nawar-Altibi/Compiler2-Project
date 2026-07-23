package compilers.flask.vm.flask;

import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.values.PyAttributeProvider;
import compilers.flask.vm.values.PyNativeFunction;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyValue;
import compilers.flask.vm.values.PyIterator;

import java.util.ArrayList;
import java.util.List;

import java.util.Optional;

/** Capability-backed, read-only VM file/context-manager value. */
public final class PyReadableFile implements PyValue, PyAttributeProvider {
    private final String path;
    private final String content;
    private boolean closed;

    public PyReadableFile(String path, String content) {
        this.path = path;
        this.content = content;
    }

    public PyIterator lineIterator() {
        if (closed) {
            throw RuntimeOps.error("ValueError", "I/O operation on closed file");
        }
        List<PyValue> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '\n') {
                lines.add(new PyString(content.substring(start, index + 1)));
                start = index + 1;
            }
        }
        if (start < content.length()) {
            lines.add(new PyString(content.substring(start)));
        }
        return new PyIterator(lines);
    }

    @Override
    public Optional<PyValue> findAttribute(String name) {
        switch (name) {
            case "read":
                return Optional.<PyValue>of(new PyNativeFunction("read", (context, p, k) -> {
                    requireNoArguments("read", p.size(), k.size());
                    if (closed) throw RuntimeOps.error("ValueError", "I/O operation on closed file");
                    return new PyString(content);
                }));
            case "close":
                return Optional.<PyValue>of(new PyNativeFunction("close", (context, p, k) -> {
                    requireNoArguments("close", p.size(), k.size());
                    closed = true;
                    return PyNone.INSTANCE;
                }));
            case "__enter__":
                return Optional.<PyValue>of(new PyNativeFunction("__enter__", (context, p, k) -> {
                    requireNoArguments("__enter__", p.size(), k.size());
                    return this;
                }));
            case "__exit__":
                return Optional.<PyValue>of(new PyNativeFunction("__exit__", (context, p, k) -> {
                    if (p.size() != 3 || !k.isEmpty()) {
                        throw RuntimeOps.error("TypeError", "__exit__() expects three arguments");
                    }
                    closed = true;
                    return compilers.flask.vm.values.PyBool.FALSE;
                }));
            case "name": return Optional.<PyValue>of(new PyString(path));
            case "closed": return Optional.<PyValue>of(
                    compilers.flask.vm.values.PyBool.valueOf(closed));
            default: return Optional.empty();
        }
    }

    private static void requireNoArguments(String name, int positional, int keywords) {
        if (positional != 0 || keywords != 0) {
            throw RuntimeOps.error("TypeError", name + "() takes no arguments");
        }
    }

    @Override public String getTypeName() { return "TextIOWrapper"; }
    @Override public String repr() { return "<open file " + new PyString(path).repr() + ">"; }
    @Override public String toString() { return repr(); }
}
