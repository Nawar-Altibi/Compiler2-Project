package compilers.flask.vm.values;

/** Python double-precision floating-point value. */
public final class PyFloat implements PyValue {
    private final double value;

    public PyFloat(double value) {
        this.value = value;
    }

    public double getValue() {
        return value;
    }

    @Override
    public String getTypeName() {
        return "float";
    }

    @Override
    public String repr() {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "inf";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-inf";
        }
        return Double.toString(value);
    }

    @Override
    public boolean isDeeplyImmutable() {
        return true;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PyFloat
                && Double.doubleToLongBits(value)
                == Double.doubleToLongBits(((PyFloat) other).value);
    }

    @Override
    public int hashCode() {
        return 31 * PyFloat.class.hashCode() + Double.hashCode(value);
    }

    @Override
    public String toString() {
        return repr();
    }
}
