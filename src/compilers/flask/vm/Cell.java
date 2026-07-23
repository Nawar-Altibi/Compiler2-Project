package compilers.flask.vm;

import compilers.flask.vm.values.PyValue;

import java.util.Objects;
import java.util.Optional;

/** Mutable shared closure cell with an explicit unbound state. */
public final class Cell {
    private PyValue value;

    public Cell() {
    }

    public Cell(PyValue value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public boolean isBound() {
        return value != null;
    }

    public Optional<PyValue> find() {
        return Optional.ofNullable(value);
    }

    public void set(PyValue value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public void clear() {
        value = null;
    }

    @Override
    public String toString() {
        return isBound() ? "<cell: " + value.repr() + ">" : "<cell: empty>";
    }
}
