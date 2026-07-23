package compilers.flask.vm.values;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Mutable Python list with deterministic insertion order. */
public final class PyList implements PyValue {
    private final List<PyValue> elements;

    public PyList() {
        this.elements = new ArrayList<>();
    }

    public PyList(List<? extends PyValue> elements) {
        Objects.requireNonNull(elements, "elements");
        this.elements = new ArrayList<>(elements.size());
        for (PyValue element : elements) {
            this.elements.add(Objects.requireNonNull(element, "list element"));
        }
    }

    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    public PyValue get(int index) {
        return elements.get(index);
    }

    public List<PyValue> getElements() {
        return Collections.unmodifiableList(new ArrayList<>(elements));
    }

    public void append(PyValue value) {
        elements.add(Objects.requireNonNull(value, "value"));
    }

    public void extend(List<? extends PyValue> values) {
        Objects.requireNonNull(values, "values");
        for (PyValue value : values) {
            append(value);
        }
    }

    public void set(int index, PyValue value) {
        elements.set(index, Objects.requireNonNull(value, "value"));
    }

    public PyValue remove(int index) {
        return elements.remove(index);
    }

    public void clear() {
        elements.clear();
    }

    @Override
    public String getTypeName() {
        return "list";
    }

    @Override
    public String repr() {
        if (!ValueRepr.enter(this)) {
            return "[...]";
        }
        try {
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < elements.size(); i++) {
                if (i > 0) {
                    result.append(", ");
                }
                result.append(elements.get(i).repr());
            }
            return result.append(']').toString();
        } finally {
            ValueRepr.leave(this);
        }
    }

    @Override
    public String toString() {
        return repr();
    }
}
