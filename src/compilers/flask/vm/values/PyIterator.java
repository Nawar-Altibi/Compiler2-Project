package compilers.flask.vm.values;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One-shot Python iterator used by GET_ITER/FOR_ITER. */
public final class PyIterator implements PyValue {
    @FunctionalInterface
    public interface NextSource {
        Optional<PyValue> tryNext();
    }

    private interface Source {
        Optional<PyValue> tryNext();
    }

    private final Source source;
    private int consumedCount;

    public PyIterator(List<? extends PyValue> values) {
        Objects.requireNonNull(values, "values");
        List<PyValue> snapshot = new ArrayList<>(values.size());
        for (PyValue value : values) {
            snapshot.add(Objects.requireNonNull(value, "iterator value"));
        }
        this.source = new IndexedSource(snapshot);
    }

    private PyIterator(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Creates a lazy one-shot iterator from an explicit VM-owned source. */
    public static PyIterator fromSource(final NextSource source) {
        Objects.requireNonNull(source, "source");
        return new PyIterator(() -> Objects.requireNonNull(
                source.tryNext(), "iterator source result"));
    }

    /** Python list iterators observe later list mutations. */
    public static PyIterator overList(final PyList list) {
        Objects.requireNonNull(list, "list");
        return new PyIterator(new Source() {
            private int index;

            @Override
            public Optional<PyValue> tryNext() {
                if (index >= list.size()) {
                    return Optional.empty();
                }
                return Optional.of(list.get(index++));
            }
        });
    }

    public Optional<PyValue> tryNext() {
        Optional<PyValue> result = source.tryNext();
        if (result.isPresent()) {
            consumedCount++;
        }
        return result;
    }

    public int getConsumedCount() {
        return consumedCount;
    }

    @Override
    public String getTypeName() {
        return "iterator";
    }

    @Override
    public String repr() {
        return "<iterator object>";
    }

    @Override
    public String toString() {
        return repr();
    }

    private static final class IndexedSource implements Source {
        private final List<PyValue> values;
        private int index;

        private IndexedSource(List<PyValue> values) {
            this.values = values;
        }

        @Override
        public Optional<PyValue> tryNext() {
            if (index >= values.size()) {
                return Optional.empty();
            }
            return Optional.of(values.get(index++));
        }
    }
}
