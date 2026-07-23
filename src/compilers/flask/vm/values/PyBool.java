package compilers.flask.vm.values;

/** Singleton Python boolean values. */
public final class PyBool implements PyValue {
    public static final PyBool TRUE = new PyBool(true);
    public static final PyBool FALSE = new PyBool(false);

    private final boolean value;

    private PyBool(boolean value) {
        this.value = value;
    }

    public static PyBool valueOf(boolean value) {
        return value ? TRUE : FALSE;
    }

    public boolean getValue() {
        return value;
    }

    @Override
    public String getTypeName() {
        return "bool";
    }

    @Override
    public String repr() {
        return value ? "True" : "False";
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
