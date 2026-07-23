package compilers.flask.vm.values;

import compilers.flask.vm.Namespace;
import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.VmCallContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Python-visible class value with an owned attribute namespace and a frozen
 * C3 method-resolution order.
 *
 * <p>The supported object model deliberately omits metaclasses and the full
 * descriptor protocol.  Attribute lookup is therefore an identity-based walk
 * of the C3 order, while writes and deletes affect only this class's own
 * namespace.</p>
 */
public final class PyClass implements PyType {
    private final String name;
    private final List<PyType> bases;
    private final Namespace attributes;
    private final List<PyType> methodResolutionOrder;

    /**
     * Constructs a class from already evaluated base values and an executed
     * class-body namespace.
     *
     * <p>The namespace is copied so a completed class cannot be changed by a
     * stale class-body frame.  Base validation is a Python runtime operation:
     * non-class, duplicate, or C3-inconsistent bases raise {@code TypeError}.</p>
     */
    public PyClass(
            String name,
            List<? extends PyValue> baseValues,
            Namespace classNamespace) {
        this.name = requireName(name);
        this.bases = immutableBases(baseValues, this.name);
        this.attributes = new Namespace(Objects.requireNonNull(
                classNamespace, "classNamespace").snapshot());
        this.methodResolutionOrder = PyTypeMro.linearize(this, this.bases);
    }

    public String getName() {
        return name;
    }

    public List<PyType> getBases() {
        return bases;
    }

    /** The class itself is always the first entry. */
    public List<PyType> getMethodResolutionOrder() {
        return methodResolutionOrder;
    }

    /** Immutable insertion-ordered snapshot of this class's own attributes. */
    public Map<String, PyValue> getOwnAttributes() {
        return attributes.snapshot();
    }

    @Override
    public Optional<PyValue> findAttribute(String attributeName) {
        requireAttributeName(attributeName);
        if ("__name__".equals(attributeName)) {
            return Optional.<PyValue>of(new PyString(name));
        }
        if ("__bases__".equals(attributeName)) {
            return Optional.<PyValue>of(new PyTuple(
                    new ArrayList<PyValue>(bases)));
        }
        if ("__mro__".equals(attributeName)) {
            return Optional.<PyValue>of(new PyTuple(
                    new ArrayList<PyValue>(methodResolutionOrder)));
        }
        for (PyType entry : methodResolutionOrder) {
            Optional<PyValue> value = entry.findOwnAttribute(attributeName);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<PyValue> findOwnAttribute(String attributeName) {
        return attributes.find(requireAttributeName(attributeName));
    }

    @Override
    public boolean setAttribute(String attributeName, PyValue value) {
        attributes.put(requireAttributeName(attributeName),
                Objects.requireNonNull(value, "value"));
        return true;
    }

    /** Deletes only an attribute owned by this class, never an inherited one. */
    @Override
    public boolean deleteAttribute(String attributeName) {
        return attributes.delete(requireAttributeName(attributeName));
    }

    /**
     * Creates a fresh instance and invokes its inherited/bound
     * {@code __init__}, when present.  The initializer must return None.
     */
    @Override
    public PyValue call(
            VmCallContext context,
            List<PyValue> positionalArguments,
            Map<String, PyValue> keywordArguments) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(positionalArguments, "positionalArguments");
        Objects.requireNonNull(keywordArguments, "keywordArguments");

        PyInstance instance = new PyInstance(this);
        Optional<PyValue> initializer = instance.findAttribute("__init__");
        if (!initializer.isPresent()) {
            if (!positionalArguments.isEmpty() || !keywordArguments.isEmpty()) {
                throw RuntimeOps.error(
                        "TypeError", name + "() takes no arguments");
            }
            return instance;
        }

        PyValue result = context.invoke(
                initializer.get(), positionalArguments, keywordArguments);
        if (result != PyNone.INSTANCE) {
            throw RuntimeOps.error(
                    "TypeError",
                    "__init__() should return None, not '"
                            + result.getTypeName() + "'");
        }
        return instance;
    }

    @Override
    public String getTypeName() {
        return "type";
    }

    @Override
    public String repr() {
        return "<class '" + name + "'>";
    }

    @Override
    public String toString() {
        return repr();
    }

    private static List<PyType> immutableBases(
            List<? extends PyValue> source, String className) {
        Objects.requireNonNull(source, "bases");
        List<PyType> result = new ArrayList<>(source.size());
        IdentityHashMap<PyType, Boolean> seen = new IdentityHashMap<>();
        for (PyValue value : source) {
            Objects.requireNonNull(value, "base");
            if (!(value instanceof PyType)) {
                throw RuntimeOps.error(
                        "TypeError",
                        "class '" + className + "' base must be a class, not '"
                                + value.getTypeName() + "'");
            }
            PyType base = (PyType) value;
            if (seen.put(base, Boolean.TRUE) != null) {
                throw RuntimeOps.error(
                        "TypeError", "duplicate base class " + base.getName());
            }
            result.add(base);
        }
        return Collections.unmodifiableList(result);
    }

    private static String requireName(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Class name cannot be empty");
        }
        return value;
    }

    private static String requireAttributeName(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Attribute name cannot be empty");
        }
        return value;
    }
}
