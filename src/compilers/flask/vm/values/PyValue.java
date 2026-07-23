package compilers.flask.vm.values;

import compilers.flask.vm.VmStackValue;

/** Base contract for every Python-visible runtime value. */
public interface PyValue extends VmStackValue {

    /** Python-facing type name used in deterministic error messages. */
    String getTypeName();

    /** Deterministic Python-like representation. */
    String repr();

    /** Python-like string conversion used by f-strings. */
    default String str() {
        return repr();
    }

    /**
     * Whether this value and every transitively contained value are safe to
     * share from an immutable bytecode constant pool.
     */
    default boolean isDeeplyImmutable() {
        return false;
    }
}
