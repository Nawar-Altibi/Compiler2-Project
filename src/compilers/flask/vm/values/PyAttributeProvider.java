package compilers.flask.vm.values;

import java.util.Optional;

/**
 * Explicit protocol used by {@code RuntimeOps} for attribute access.
 * Primitive values normally do not implement it; later module/class/instance
 * values can do so without adding reflection to the VM.
 */
public interface PyAttributeProvider {

    Optional<PyValue> findAttribute(String name);

    default boolean setAttribute(String name, PyValue value) {
        return false;
    }

    default boolean deleteAttribute(String name) {
        return false;
    }
}
