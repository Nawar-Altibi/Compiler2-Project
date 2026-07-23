package compilers.flask.vm;

import compilers.flask.vm.values.PyValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Deterministic mutable namespace used for locals, globals, and builtins. */
public final class Namespace {
    private final LinkedHashMap<String, PyValue> values;

    public Namespace() {
        this.values = new LinkedHashMap<>();
    }

    public Namespace(Map<String, ? extends PyValue> initialValues) {
        this();
        Objects.requireNonNull(initialValues, "initialValues");
        for (Map.Entry<String, ? extends PyValue> entry : initialValues.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    public int size() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public boolean contains(String name) {
        return values.containsKey(requireName(name));
    }

    public Optional<PyValue> find(String name) {
        return Optional.ofNullable(values.get(requireName(name)));
    }

    public void put(String name, PyValue value) {
        values.put(requireName(name), Objects.requireNonNull(value, "value"));
    }

    public boolean delete(String name) {
        return values.remove(requireName(name)) != null;
    }

    /** Immutable insertion-ordered snapshot; never a live mutable view. */
    public Map<String, PyValue> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Namespace copy() {
        return new Namespace(values);
    }

    private static String requireName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Namespace name cannot be empty");
        }
        return name;
    }

    @Override
    public String toString() {
        return snapshot().toString();
    }
}
