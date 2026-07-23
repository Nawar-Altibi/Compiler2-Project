package compilers.flask.vm.values;

import java.util.IdentityHashMap;

/** Cycle guard shared by deterministic container representations. */
final class ValueRepr {
    private static final ThreadLocal<IdentityHashMap<PyValue, Boolean>> ACTIVE =
            new ThreadLocal<>();

    private ValueRepr() {
    }

    static boolean enter(PyValue value) {
        IdentityHashMap<PyValue, Boolean> active = ACTIVE.get();
        if (active == null) {
            active = new IdentityHashMap<>();
            ACTIVE.set(active);
        }
        return active.put(value, Boolean.TRUE) == null;
    }

    static void leave(PyValue value) {
        IdentityHashMap<PyValue, Boolean> active = ACTIVE.get();
        if (active == null) {
            return;
        }
        active.remove(value);
        if (active.isEmpty()) {
            ACTIVE.remove();
        }
    }
}
