package compilers.flask.vm.values;

import compilers.flask.vm.RuntimeOps;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable arbitrary-precision range value used by the builtin provider. */
public final class PyRange implements PyValue {
    private static final BigInteger MATERIALIZATION_LIMIT = BigInteger.valueOf(1_000_000L);

    private final BigInteger start;
    private final BigInteger stop;
    private final BigInteger step;
    private final BigInteger length;

    public PyRange(BigInteger start, BigInteger stop, BigInteger step) {
        this.start = Objects.requireNonNull(start, "start");
        this.stop = Objects.requireNonNull(stop, "stop");
        this.step = Objects.requireNonNull(step, "step");
        if (step.signum() == 0) {
            throw RuntimeOps.error("ValueError", "range() arg 3 must not be zero");
        }
        this.length = computeLength(start, stop, step);
    }

    public BigInteger getStart() { return start; }
    public BigInteger getStop() { return stop; }
    public BigInteger getStep() { return step; }
    public BigInteger getLength() { return length; }

    public PyIterator iterator() {
        if (length.compareTo(MATERIALIZATION_LIMIT) > 0) {
            throw RuntimeOps.error(
                    "RuntimeError", "range is too large for this VM iterator");
        }
        List<PyValue> values = new ArrayList<>(length.intValue());
        BigInteger current = start;
        for (int index = 0; index < length.intValue(); index++) {
            values.add(new PyInt(current));
            current = current.add(step);
        }
        return new PyIterator(values);
    }

    @Override public String getTypeName() { return "range"; }

    @Override
    public String repr() {
        if (step.equals(BigInteger.ONE)) {
            if (start.equals(BigInteger.ZERO)) {
                return "range(" + stop + ")";
            }
            return "range(" + start + ", " + stop + ")";
        }
        return "range(" + start + ", " + stop + ", " + step + ")";
    }

    @Override public boolean isDeeplyImmutable() { return true; }
    @Override public String toString() { return repr(); }

    private static BigInteger computeLength(
            BigInteger start, BigInteger stop, BigInteger step) {
        if (step.signum() > 0) {
            if (start.compareTo(stop) >= 0) {
                return BigInteger.ZERO;
            }
            return stop.subtract(start).subtract(BigInteger.ONE)
                    .divide(step).add(BigInteger.ONE);
        }
        if (start.compareTo(stop) <= 0) {
            return BigInteger.ZERO;
        }
        BigInteger magnitude = step.negate();
        return start.subtract(stop).subtract(BigInteger.ONE)
                .divide(magnitude).add(BigInteger.ONE);
    }
}
