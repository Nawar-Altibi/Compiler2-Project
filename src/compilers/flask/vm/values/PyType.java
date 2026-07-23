package compilers.flask.vm.values;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Common protocol for user-defined and host-approved native Python types.
 *
 * <p>The protocol is intentionally smaller than Python's metaclass model. It
 * exists so class construction, C3 linearization, attribute lookup, and
 * subclass checks use one identity-based hierarchy regardless of which side
 * created a base type.</p>
 */
public interface PyType extends PyCallable, PyAttributeProvider {

    String getName();

    List<PyType> getBases();

    List<PyType> getMethodResolutionOrder();

    /** Returns only an attribute owned by this exact type, without MRO lookup. */
    default Optional<PyValue> findOwnAttribute(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.empty();
    }

    /** Identity-based subclass relation, including a type being itself. */
    default boolean isSubclassOf(PyType candidateBase) {
        Objects.requireNonNull(candidateBase, "candidateBase");
        for (PyType entry : getMethodResolutionOrder()) {
            if (entry == candidateBase) {
                return true;
            }
        }
        return false;
    }
}
