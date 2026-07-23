package compilers.flask.vm.values;

/** Singleton Python {@code None}. */
public final class PyNone implements PyValue {
    public static final PyNone INSTANCE = new PyNone();

    private PyNone() {
    }

    @Override
    public String getTypeName() {
        return "NoneType";
    }

    @Override
    public String repr() {
        return "None";
    }

    @Override
    public boolean isDeeplyImmutable() {
        return true;
    }

    @Override
    public String toString() {
        return repr();
    }
}
