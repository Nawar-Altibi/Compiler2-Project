package compilers.flask.vm.flask;

import compilers.flask.vm.Namespace;
import compilers.flask.vm.values.PyAttributeProvider;
import compilers.flask.vm.values.PyValue;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Attribute-based Python namespace used by Flask's {@code g}. */
public final class PyNamespaceValue implements PyValue, PyAttributeProvider {
    private final String typeName;
    private final Namespace values = new Namespace();

    public PyNamespaceValue(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            throw new IllegalArgumentException("Namespace type cannot be empty");
        }
        this.typeName = typeName;
    }

    public Map<String, PyValue> snapshot() {
        return values.snapshot();
    }

    @Override
    public Optional<PyValue> findAttribute(String name) {
        return values.find(name);
    }

    @Override
    public boolean setAttribute(String name, PyValue value) {
        values.put(name, Objects.requireNonNull(value, "value"));
        return true;
    }

    @Override
    public boolean deleteAttribute(String name) {
        return values.delete(name);
    }

    @Override public String getTypeName() { return typeName; }
    @Override public String repr() { return "<" + typeName + ">"; }
    @Override public String toString() { return repr(); }
}
