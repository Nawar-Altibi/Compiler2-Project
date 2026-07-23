package compilers.flask.vm.flask;

import compilers.flask.vm.values.PyAttributeProvider;
import compilers.flask.vm.values.PyDict;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyValue;

import java.util.Optional;

/** Immutable request envelope supplied by tests/host route invocation. */
public final class PyRequest implements PyValue, PyAttributeProvider {
    private final PyString method;
    private final PyString path;
    private final PyDict args;
    private final PyDict form;
    private final PyDict files;
    private final PyValue json;

    public PyRequest(String method, String path) {
        this(method, path, new PyDict(), new PyDict(), new PyDict(), PyNone.INSTANCE);
    }

    public PyRequest(
            String method, String path, PyDict args, PyDict form,
            PyDict files, PyValue json) {
        this.method = new PyString(method == null
                ? "GET" : method.toUpperCase(java.util.Locale.ROOT));
        this.path = new PyString(path == null || path.isEmpty() ? "/" : path);
        this.args = args == null ? new PyDict() : args;
        this.form = form == null ? new PyDict() : form;
        this.files = files == null ? new PyDict() : files;
        this.json = json == null ? PyNone.INSTANCE : json;
    }

    @Override
    public Optional<PyValue> findAttribute(String name) {
        switch (name) {
            case "method": return Optional.<PyValue>of(method);
            case "path": return Optional.<PyValue>of(path);
            case "args": return Optional.<PyValue>of(args);
            case "form": return Optional.<PyValue>of(form);
            case "files": return Optional.<PyValue>of(files);
            case "json": return Optional.of(json);
            default: return Optional.empty();
        }
    }

    @Override public String getTypeName() { return "Request"; }
    @Override public String repr() { return "<Request " + method.getValue() + " " + path.getValue() + ">"; }
    @Override public String toString() { return repr(); }
}
