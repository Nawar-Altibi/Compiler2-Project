package compilers.flask.vm.values;

import compilers.flask.vm.VmCallContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Explicit callable type object used by the whitelisted builtin registry. */
public final class PyNativeClass implements PyType {
    @FunctionalInterface
    public interface Constructor {
        PyValue construct(
                VmCallContext context,
                List<PyValue> positionalArguments,
                Map<String, PyValue> keywordArguments);
    }

    private final String name;
    private final List<PyType> bases;
    private final List<PyType> methodResolutionOrder;
    private final Constructor constructor;

    public PyNativeClass(
            String name,
            List<? extends PyType> bases,
            Constructor constructor) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Native class name cannot be empty");
        }
        this.name = name;
        this.bases = immutableIdentityDistinct(bases);
        this.constructor = Objects.requireNonNull(constructor, "constructor");

        this.methodResolutionOrder = PyTypeMro.linearize(this, this.bases);
    }

    public String getName() {
        return name;
    }

    public List<PyType> getBases() {
        return bases;
    }

    public List<PyType> getMethodResolutionOrder() {
        return methodResolutionOrder;
    }

    @Override
    public PyValue call(
            VmCallContext context,
            List<PyValue> positionalArguments,
            Map<String, PyValue> keywordArguments) {
        return Objects.requireNonNull(
                constructor.construct(
                        Objects.requireNonNull(context, "context"),
                        Objects.requireNonNull(positionalArguments, "positionalArguments"),
                        Objects.requireNonNull(keywordArguments, "keywordArguments")),
                "native constructor result");
    }

    @Override
    public Optional<PyValue> findAttribute(String attributeName) {
        if ("__name__".equals(attributeName)) {
            return Optional.<PyValue>of(new PyString(name));
        }
        if ("__bases__".equals(attributeName)) {
            return Optional.<PyValue>of(new PyTuple(new ArrayList<PyValue>(bases)));
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

    private static List<PyType> immutableIdentityDistinct(
            List<? extends PyType> source) {
        Objects.requireNonNull(source, "bases");
        List<PyType> result = new ArrayList<>(source.size());
        IdentityHashMap<PyType, Boolean> seen = new IdentityHashMap<>();
        for (PyType base : source) {
            Objects.requireNonNull(base, "base");
            if (seen.put(base, Boolean.TRUE) != null) {
                throw new IllegalArgumentException(
                        "Duplicate native base class " + base.getName());
            }
            result.add(base);
        }
        return Collections.unmodifiableList(result);
    }
}
