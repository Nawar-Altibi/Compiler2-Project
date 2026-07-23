package compilers.flask.vm.values;

import java.math.BigInteger;
import java.util.Objects;

/** Arbitrary-precision Python integer. */
public final class PyInt implements PyValue {
    public static final PyInt ZERO = new PyInt(BigInteger.ZERO);
    public static final PyInt ONE = new PyInt(BigInteger.ONE);

    private final BigInteger value;

    public PyInt(BigInteger value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static PyInt valueOf(long value) {
        if (value == 0L) {
            return ZERO;
        }
        if (value == 1L) {
            return ONE;
        }
        return new PyInt(BigInteger.valueOf(value));
    }

    public BigInteger getValue() {
        return value;
    }

    @Override
    public String getTypeName() {
        return "int";
    }

    @Override
    public String repr() {
        return value.toString();
    }

    @Override
    public boolean isDeeplyImmutable() {
        return true;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PyInt && value.equals(((PyInt) other).value);
    }

    @Override
    public int hashCode() {
        return 31 * PyInt.class.hashCode() + value.hashCode();
    }

    @Override
    public String toString() {
        return repr();
    }
}
