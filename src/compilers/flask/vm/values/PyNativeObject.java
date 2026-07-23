package compilers.flask.vm.values;

/** Minimal identity-bearing value returned by the builtin object class. */
public final class PyNativeObject implements PyValue {
    @Override public String getTypeName() { return "object"; }
    @Override public String repr() { return "<object>"; }
    @Override public String toString() { return repr(); }
}
