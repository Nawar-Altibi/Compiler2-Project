package compilers.flask.vm.values;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable Python tuple. */
public final class PyTuple implements PyValue {
    public static final PyTuple EMPTY = new PyTuple(Collections.<PyValue>emptyList());

    private final List<PyValue> elements;

    public PyTuple(List<? extends PyValue> elements) {
        Objects.requireNonNull(elements, "elements");
        List<PyValue> copy = new ArrayList<>(elements.size());
        for (PyValue element : elements) {
            copy.add(Objects.requireNonNull(element, "tuple element"));
        }
        this.elements = Collections.unmodifiableList(copy);
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
        return elements;
    }

    @Override
    public String getTypeName() {
        return "tuple";
    }

    @Override
    public String repr() {
        if (!ValueRepr.enter(this)) {
            return "(...)";
        }
        try {
            StringBuilder result = new StringBuilder("(");
            for (int i = 0; i < elements.size(); i++) {
                if (i > 0) {
                    result.append(", ");
                }
                result.append(elements.get(i).repr());
            }
            if (elements.size() == 1) {
                result.append(',');
            }
            return result.append(')').toString();
        } finally {
            ValueRepr.leave(this);
        }
    }

    @Override
    public boolean isDeeplyImmutable() {
        for (PyValue element : elements) {
            if (!element.isDeeplyImmutable()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PyTuple
                && elements.equals(((PyTuple) other).elements);
    }

    @Override
    public int hashCode() {
        return 31 * PyTuple.class.hashCode() + elements.hashCode();
    }

    @Override
    public String toString() {
        return repr();
    }
}
