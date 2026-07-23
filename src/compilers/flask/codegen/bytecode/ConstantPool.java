package compilers.flask.codegen.bytecode;

import compilers.flask.vm.values.PyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Immutable, type-aware pool of Python constants and nested code definitions. */
public final class ConstantPool {
    private final List<Object> entries;

    public ConstantPool(List<?> entries) {
        Objects.requireNonNull(entries, "entries");
        List<Object> copy = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            validateEntry(entry);
            copy.add(entry);
        }
        this.entries = Collections.unmodifiableList(copy);
    }

    public int size() {
        return entries.size();
    }

    public Object get(int index) {
        return entries.get(index);
    }

    public List<Object> getEntries() {
        return entries;
    }

    public List<CodeObject> getNestedCodeObjects() {
        List<CodeObject> nested = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof CodeObject) {
                nested.add((CodeObject) entry);
            }
        }
        return Collections.unmodifiableList(nested);
    }

    private static void validateEntry(Object entry) {
        Objects.requireNonNull(entry, "constant entry");
        if (entry instanceof CodeObject) {
            return;
        }
        if (!(entry instanceof PyValue)) {
            throw new IllegalArgumentException(
                    "Constant entries must be PyValue or CodeObject, got "
                            + entry.getClass().getName());
        }
        if (!((PyValue) entry).isDeeplyImmutable()) {
            throw new IllegalArgumentException(
                    "Mutable Python value cannot be stored in a constant pool: "
                            + ((PyValue) entry).getTypeName());
        }
    }

    /** Mutable insertion-order builder; it never escapes into a CodeObject. */
    public static final class Builder {
        private final List<Object> entries = new ArrayList<>();
        private final Map<TypedValueKey, Integer> valueIndices = new LinkedHashMap<>();
        private final IdentityHashMap<CodeObject, Integer> codeIndices =
                new IdentityHashMap<>();
        private final IdentityHashMap<CodeObjectBuilder, Integer> builderIndices =
                new IdentityHashMap<>();

        public int add(PyValue value) {
            validateEntry(value);
            TypedValueKey key = new TypedValueKey(value);
            Integer existing = valueIndices.get(key);
            if (existing != null) {
                return existing;
            }
            int index = entries.size();
            entries.add(value);
            valueIndices.put(key, index);
            return index;
        }

        public int add(CodeObject codeObject) {
            validateEntry(codeObject);
            Integer existing = codeIndices.get(codeObject);
            if (existing != null) {
                return existing;
            }
            int index = entries.size();
            entries.add(codeObject);
            codeIndices.put(codeObject, index);
            return index;
        }

        /** Reserves a lexical constant slot for a symbolic nested code object. */
        public int add(CodeObjectBuilder codeBuilder) {
            Objects.requireNonNull(codeBuilder, "codeBuilder");
            Integer existing = builderIndices.get(codeBuilder);
            if (existing != null) {
                return existing;
            }
            int index = entries.size();
            entries.add(codeBuilder);
            builderIndices.put(codeBuilder, index);
            return index;
        }

        public int size() {
            return entries.size();
        }

        public ConstantPool build() {
            return new ConstantPool(entries);
        }

        /** Resolves every symbolic nested builder before freezing the pool. */
        public ConstantPool buildResolved(
                Function<CodeObjectBuilder, CodeObject> resolver) {
            Objects.requireNonNull(resolver, "resolver");
            List<Object> resolved = new ArrayList<>(entries.size());
            for (Object entry : entries) {
                if (entry instanceof CodeObjectBuilder) {
                    CodeObject code = resolver.apply((CodeObjectBuilder) entry);
                    if (code == null) {
                        throw new IllegalStateException(
                                "Nested code-object assembly failed");
                    }
                    resolved.add(code);
                } else {
                    resolved.add(entry);
                }
            }
            return new ConstantPool(resolved);
        }
    }

    private static final class TypedValueKey {
        private final Class<?> type;
        private final PyValue value;

        private TypedValueKey(PyValue value) {
            this.type = value.getClass();
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof TypedValueKey)) {
                return false;
            }
            TypedValueKey that = (TypedValueKey) other;
            return type == that.type && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return 31 * type.hashCode() + value.hashCode();
        }
    }
}
