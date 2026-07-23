package compilers.flask.vm.values;

import compilers.flask.vm.Namespace;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Python-visible instance with its own attribute dictionary. */
public final class PyInstance implements PyAttributeProvider, PyValue {
    private final PyClass pythonClass;
    private final Namespace attributes = new Namespace();

    public PyInstance(PyClass pythonClass) {
        this.pythonClass = Objects.requireNonNull(pythonClass, "pythonClass");
    }

    public PyClass getPythonClass() {
        return pythonClass;
    }

    /** Immutable insertion-ordered snapshot of instance-owned attributes. */
    public Map<String, PyValue> getOwnAttributes() {
        return attributes.snapshot();
    }

    public boolean isInstanceOf(PyType candidateClass) {
        return pythonClass.isSubclassOf(
                Objects.requireNonNull(candidateClass, "candidateClass"));
    }

    @Override
    public Optional<PyValue> findAttribute(String name) {
        if ("__class__".equals(name)) {
            return Optional.<PyValue>of(pythonClass);
        }
        Optional<PyValue> own = attributes.find(name);
        if (own.isPresent()) {
            return own;
        }

        Optional<PyValue> inherited = pythonClass.findAttribute(name);
        if (!inherited.isPresent()) {
            return Optional.empty();
        }
        PyValue value = inherited.get();
        // This is the deliberately supported descriptor subset.  Native and
        // arbitrary callable attributes remain ordinary values.
        return value instanceof PyFunction
                ? Optional.<PyValue>of(new PyBoundMethod(value, this))
                : inherited;
    }

    @Override
    public boolean setAttribute(String name, PyValue value) {
        attributes.put(name, Objects.requireNonNull(value, "value"));
        return true;
    }

    /** Deletes only the instance entry; an inherited class entry is untouched. */
    @Override
    public boolean deleteAttribute(String name) {
        return attributes.delete(name);
    }

    @Override
    public String getTypeName() {
        return pythonClass.getName();
    }

    @Override
    public String repr() {
        return "<" + pythonClass.getName() + " instance>";
    }

    @Override
    public String toString() {
        return repr();
    }
}
