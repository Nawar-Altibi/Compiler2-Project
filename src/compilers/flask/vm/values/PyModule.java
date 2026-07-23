package compilers.flask.vm.values;

import compilers.flask.vm.Namespace;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Python-visible module object backed by a deterministic namespace. */
public final class PyModule implements PyValue, PyAttributeProvider {
    private final String canonicalName;
    private final String packageName;
    private final Namespace namespace;

    /**
     * Creates a module whose namespace is ready before initialization code
     * runs, as required for circular imports.
     */
    public PyModule(String canonicalName, String packageName) {
        this.canonicalName = requireModuleName(canonicalName);
        this.packageName = requirePackageName(packageName);
        this.namespace = new Namespace();
        namespace.put("__name__", new PyString(this.canonicalName));
        namespace.put("__package__", new PyString(this.packageName));
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public String getPackageName() {
        return packageName;
    }

    /** Mutable namespace intentionally exposed to trusted module initializers. */
    public Namespace getNamespace() {
        return namespace;
    }

    public void put(String name, PyValue value) {
        namespace.put(name, value);
    }

    public Optional<PyValue> find(String name) {
        return namespace.find(name);
    }

    public Map<String, PyValue> snapshot() {
        return namespace.snapshot();
    }

    @Override
    public Optional<PyValue> findAttribute(String name) {
        return namespace.find(name);
    }

    @Override
    public boolean setAttribute(String name, PyValue value) {
        namespace.put(name, Objects.requireNonNull(value, "value"));
        return true;
    }

    @Override
    public boolean deleteAttribute(String name) {
        return namespace.delete(name);
    }

    @Override
    public String getTypeName() {
        return "module";
    }

    @Override
    public String repr() {
        return "<module " + new PyString(canonicalName).repr() + ">";
    }

    @Override
    public String toString() {
        return repr();
    }

    private static String requireModuleName(String value) {
        String normalized = Objects.requireNonNull(value, "canonicalName").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Module name cannot be empty");
        }
        return normalized;
    }

    private static String requirePackageName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (!normalized.equals(value)) {
            throw new IllegalArgumentException(
                    "Package name cannot contain surrounding whitespace");
        }
        return normalized;
    }
}
