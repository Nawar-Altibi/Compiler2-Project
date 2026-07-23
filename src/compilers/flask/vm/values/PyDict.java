package compilers.flask.vm.values;

import compilers.flask.vm.RuntimeOps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Mutable insertion-ordered Python dictionary. */
public final class PyDict implements PyValue {

    /** Immutable snapshot entry returned to callers. */
    public static final class Entry {
        private final PyValue key;
        private final PyValue value;

        private Entry(PyValue key, PyValue value) {
            this.key = key;
            this.value = value;
        }

        public PyValue getKey() {
            return key;
        }

        public PyValue getValue() {
            return value;
        }
    }

    private static final class MutableEntry {
        private final PyValue key;
        private PyValue value;

        private MutableEntry(PyValue key, PyValue value) {
            this.key = key;
            this.value = value;
        }
    }

    private final List<MutableEntry> entries = new ArrayList<>();

    public PyDict() {
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public List<Entry> getEntries() {
        List<Entry> result = new ArrayList<>(entries.size());
        for (MutableEntry entry : entries) {
            result.add(new Entry(entry.key, entry.value));
        }
        return Collections.unmodifiableList(result);
    }

    public List<PyValue> getKeys() {
        List<PyValue> result = new ArrayList<>(entries.size());
        for (MutableEntry entry : entries) {
            result.add(entry.key);
        }
        return Collections.unmodifiableList(result);
    }

    public Optional<PyValue> find(PyValue key) {
        RuntimeOps.hash(key);
        int index = indexOf(key);
        return index < 0 ? Optional.<PyValue>empty()
                : Optional.of(entries.get(index).value);
    }

    public boolean containsKey(PyValue key) {
        RuntimeOps.hash(key);
        return indexOf(key) >= 0;
    }

    public void put(PyValue key, PyValue value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        RuntimeOps.hash(key);
        int index = indexOf(key);
        if (index < 0) {
            entries.add(new MutableEntry(key, value));
        } else {
            entries.get(index).value = value;
        }
    }

    public boolean delete(PyValue key) {
        RuntimeOps.hash(key);
        int index = indexOf(key);
        if (index < 0) {
            return false;
        }
        entries.remove(index);
        return true;
    }

    private int indexOf(PyValue key) {
        for (int i = 0; i < entries.size(); i++) {
            if (RuntimeOps.sameContainerElement(entries.get(i).key, key)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String getTypeName() {
        return "dict";
    }

    @Override
    public String repr() {
        if (!ValueRepr.enter(this)) {
            return "{...}";
        }
        try {
            StringBuilder result = new StringBuilder("{");
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) {
                    result.append(", ");
                }
                MutableEntry entry = entries.get(i);
                result.append(entry.key.repr()).append(": ").append(entry.value.repr());
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
