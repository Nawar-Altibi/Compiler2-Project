package compilers.flask.codegen.bytecode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable insertion-ordered table for dynamic/global/attribute names. */
public final class NamePool {
    private final List<String> names;

    public NamePool(List<String> names) {
        Objects.requireNonNull(names, "names");
        LinkedHashMap<String, Boolean> distinct = new LinkedHashMap<>();
        for (String name : names) {
            requireName(name);
            if (distinct.put(name, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Duplicate name-pool entry: " + name);
            }
        }
        this.names = Collections.unmodifiableList(new ArrayList<>(distinct.keySet()));
    }

    public int size() {
        return names.size();
    }

    public String get(int index) {
        return names.get(index);
    }

    public List<String> getNames() {
        return names;
    }

    public static final class Builder {
        private final List<String> names = new ArrayList<>();
        private final Map<String, Integer> indices = new LinkedHashMap<>();

        public int add(String name) {
            requireName(name);
            Integer existing = indices.get(name);
            if (existing != null) {
                return existing;
            }
            int index = names.size();
            names.add(name);
            indices.put(name, index);
            return index;
        }

        public NamePool build() {
            return new NamePool(names);
        }
    }

    private static void requireName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Name-pool entry cannot be empty");
        }
    }
}
