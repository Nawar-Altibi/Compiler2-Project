package compilers.flask.vm.values;

import compilers.flask.vm.RuntimeOps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Mutable insertion-ordered implementation of Python set semantics. */
public final class PySet implements PyValue {
    private final List<PyValue> elements;

    public PySet() {
        this.elements = new ArrayList<>();
    }

    public PySet(List<? extends PyValue> elements) {
        this();
        Objects.requireNonNull(elements, "elements");
        for (PyValue value : elements) {
            add(value);
        }
    }

    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    public List<PyValue> getElements() {
        return Collections.unmodifiableList(new ArrayList<>(elements));
    }

    public boolean contains(PyValue value) {
        RuntimeOps.hash(value);
        return indexOf(value) >= 0;
    }

    public boolean add(PyValue value) {
        Objects.requireNonNull(value, "value");
        RuntimeOps.hash(value);
        if (indexOf(value) >= 0) {
            return false;
        }
        elements.add(value);
        return true;
    }

    public boolean remove(PyValue value) {
        RuntimeOps.hash(value);
        int index = indexOf(value);
        if (index < 0) {
            return false;
        }
        elements.remove(index);
        return true;
    }

    private int indexOf(PyValue value) {
        for (int i = 0; i < elements.size(); i++) {
            if (RuntimeOps.sameContainerElement(elements.get(i), value)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String getTypeName() {
        return "set";
    }

    @Override
    public String repr() {
        if (elements.isEmpty()) {
            return "set()";
        }
        if (!ValueRepr.enter(this)) {
            return "{...}";
        }
        try {
            StringBuilder result = new StringBuilder("{");
            for (int i = 0; i < elements.size(); i++) {
                if (i > 0) {
                    result.append(", ");
                }
                result.append(elements.get(i).repr());
            }
            return result.append('}').toString();
        } finally {
            ValueRepr.leave(this);
        }
    }

    @Override
    public String toString() {
        return repr();
    }
}
