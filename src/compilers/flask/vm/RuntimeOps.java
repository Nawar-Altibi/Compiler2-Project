package compilers.flask.vm;

import compilers.flask.vm.values.PyAttributeProvider;
import compilers.flask.vm.values.PyBaseException;
import compilers.flask.vm.values.PyBool;
import compilers.flask.vm.values.PyCallable;
import compilers.flask.vm.values.PyDict;
import compilers.flask.vm.values.PyFloat;
import compilers.flask.vm.values.PyInt;
import compilers.flask.vm.values.PyIterator;
import compilers.flask.vm.values.PyInstance;
import compilers.flask.vm.values.PyList;
import compilers.flask.vm.values.PyNativeObject;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyRange;
import compilers.flask.vm.values.PySet;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyTuple;
import compilers.flask.vm.values.PyValue;
import compilers.flask.vm.flask.PyContextProxy;
import compilers.flask.vm.flask.PyReadableFile;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Single implementation point for Python-like primitive runtime semantics.
 * Java {@code equals}, reflection, and host collection coercions are never
 * used as substitutes for Python operations.
 */
public final class RuntimeOps {
    private static final int MAX_REPEAT = 10_000_000;

    private RuntimeOps() {
    }

    // ------------------------------------------------------------------
    // Truth, equality, identity, hashing
    // ------------------------------------------------------------------

    public static boolean isTruthy(PyValue value) {
        Objects.requireNonNull(value, "value");
        if (value == PyNone.INSTANCE) {
            return false;
        }
        if (value instanceof PyBool) {
            return ((PyBool) value).getValue();
        }
        if (value instanceof PyInt) {
            return ((PyInt) value).getValue().signum() != 0;
        }
        if (value instanceof PyFloat) {
            return ((PyFloat) value).getValue() != 0.0d;
        }
        if (value instanceof PyString) {
            return !((PyString) value).getValue().isEmpty();
        }
        if (value instanceof PyList) {
            return !((PyList) value).isEmpty();
        }
        if (value instanceof PyTuple) {
            return !((PyTuple) value).isEmpty();
        }
        if (value instanceof PySet) {
            return !((PySet) value).isEmpty();
        }
        if (value instanceof PyDict) {
            return !((PyDict) value).isEmpty();
        }
        if (value instanceof PyRange) {
            return ((PyRange) value).getLength().signum() != 0;
        }
        return true;
    }

    public static boolean isTruthy(PyValue value, VmCallContext context) {
        return isTruthy(resolveDynamic(value, context));
    }

    /** Resolves stable context-local proxies only at the operation boundary. */
    public static PyValue resolveDynamic(PyValue value, VmCallContext context) {
        Objects.requireNonNull(value, "value");
        if (value instanceof PyContextProxy) {
            return ((PyContextProxy) value).resolve(
                    Objects.requireNonNull(context, "context"));
        }
        return value;
    }

    public static boolean equalsValue(PyValue left, PyValue right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (left == right) {
            // NaN is the only primitive value that is not equal to itself.
            return !(left instanceof PyFloat)
                    || !Double.isNaN(((PyFloat) left).getValue());
        }
        if (isNumeric(left) && isNumeric(right)) {
            return numericEquals(left, right);
        }
        if (left instanceof PyString && right instanceof PyString) {
            return ((PyString) left).getValue().equals(((PyString) right).getValue());
        }
        if (left instanceof PyList && right instanceof PyList) {
            return sequenceEquals(
                    ((PyList) left).getElements(), ((PyList) right).getElements());
        }
        if (left instanceof PyTuple && right instanceof PyTuple) {
            return sequenceEquals(
                    ((PyTuple) left).getElements(), ((PyTuple) right).getElements());
        }
        if (left instanceof PySet && right instanceof PySet) {
            PySet leftSet = (PySet) left;
            PySet rightSet = (PySet) right;
            if (leftSet.size() != rightSet.size()) {
                return false;
            }
            for (PyValue element : leftSet.getElements()) {
                if (!rightSet.contains(element)) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof PyDict && right instanceof PyDict) {
            PyDict leftDict = (PyDict) left;
            PyDict rightDict = (PyDict) right;
            if (leftDict.size() != rightDict.size()) {
                return false;
            }
            for (PyDict.Entry entry : leftDict.getEntries()) {
                Optional<PyValue> other = rightDict.find(entry.getKey());
                if (!other.isPresent()
                        || !sameContainerElement(entry.getValue(), other.get())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Container lookup follows Python's identity-or-equality rule. This is
     * observably different from {@code ==} for values such as NaN: a NaN is
     * not equal to itself, but the exact same object remains a valid dict/set
     * key and is found by list membership.
     */
    public static boolean sameContainerElement(PyValue left, PyValue right) {
        return left == right || equalsValue(left, right);
    }

    private static boolean sequenceEquals(List<PyValue> left, List<PyValue> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!sameContainerElement(left.get(i), right.get(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isIdentical(PyValue left, PyValue right) {
        return left == right;
    }

    public static int hash(PyValue value) {
        Objects.requireNonNull(value, "value");
        if (value == PyNone.INSTANCE) {
            return 0x45d9f3b;
        }
        if (value instanceof PyBool) {
            return ((PyBool) value).getValue() ? 1 : 0;
        }
        if (value instanceof PyInt) {
            return ((PyInt) value).getValue().hashCode();
        }
        if (value instanceof PyFloat) {
            double number = ((PyFloat) value).getValue();
            if (Double.isFinite(number)) {
                try {
                    return new BigDecimal(number).toBigIntegerExact().hashCode();
                } catch (ArithmeticException ignored) {
                    // A non-integral float has its own deterministic hash.
                }
            }
            return Double.hashCode(number);
        }
        if (value instanceof PyString) {
            return ((PyString) value).getValue().hashCode();
        }
        if (value instanceof PyTuple) {
            int result = 0x345678;
            for (PyValue element : ((PyTuple) value).getElements()) {
                result = (result ^ hash(element)) * 1_000_003;
            }
            return result ^ ((PyTuple) value).size();
        }
        if (value instanceof PyRange) {
            PyRange range = (PyRange) value;
            int result = range.getStart().hashCode();
            result = 31 * result + range.getStop().hashCode();
            return 31 * result + range.getStep().hashCode();
        }
        if (value instanceof PyBaseException) {
            return System.identityHashCode(value);
        }
        if (value instanceof PyCallable || value instanceof PyInstance
                || value instanceof PyAttributeProvider
                || value instanceof PyNativeObject) {
            return System.identityHashCode(value);
        }
        throw error("TypeError", "unhashable type: '" + value.getTypeName() + "'");
    }

    // ------------------------------------------------------------------
    // Unary and binary operations
    // ------------------------------------------------------------------

    public static PyValue unary(String operator, PyValue operand) {
        String op = normalize(operator);
        switch (op) {
            case "POSITIVE":
            case "+":
                if (operand instanceof PyFloat) {
                    return new PyFloat(((PyFloat) operand).getValue());
                }
                if (isIntegerLike(operand)) {
                    return new PyInt(asInteger(operand));
                }
                throw badUnary("+", operand);
            case "NEGATIVE":
            case "-":
                if (operand instanceof PyFloat) {
                    return new PyFloat(-((PyFloat) operand).getValue());
                }
                if (isIntegerLike(operand)) {
                    return new PyInt(asInteger(operand).negate());
                }
                throw badUnary("-", operand);
            case "NOT":
                return PyBool.valueOf(!isTruthy(operand));
            default:
                throw new IllegalArgumentException("Unknown unary operation: " + operator);
        }
    }

    public static PyValue binary(String operator, PyValue left, PyValue right) {
        String op = normalize(operator);
        switch (op) {
            case "ADD":
            case "+":
                return add(left, right, false);
            case "INPLACE_ADD":
                return add(left, right, true);
            case "SUBTRACT":
            case "-":
                return numericBinary(left, right, op, '-');
            case "INPLACE_SUBTRACT":
                return numericBinary(left, right, op, '-');
            case "MULTIPLY":
            case "*":
                return multiply(left, right, false);
            case "INPLACE_MULTIPLY":
                return multiply(left, right, true);
            case "TRUE_DIVIDE":
            case "/":
            case "INPLACE_TRUE_DIVIDE":
                return trueDivide(left, right);
            case "FLOOR_DIVIDE":
            case "//":
                return floorDivide(left, right);
            case "MODULO":
            case "%":
                return modulo(left, right);
            case "POWER":
            case "**":
                return power(left, right);
            default:
                throw new IllegalArgumentException("Unknown binary operation: " + operator);
        }
    }

    private static PyValue add(PyValue left, PyValue right, boolean inPlace) {
        if (isNumeric(left) && isNumeric(right)) {
            return numericBinary(left, right, "ADD", '+');
        }
        if (left instanceof PyString && right instanceof PyString) {
            return new PyString(
                    ((PyString) left).getValue() + ((PyString) right).getValue());
        }
        if (left instanceof PyList && right instanceof PyList) {
            PyList target = inPlace ? (PyList) left
                    : new PyList(((PyList) left).getElements());
            target.extend(((PyList) right).getElements());
            return target;
        }
        if (left instanceof PyTuple && right instanceof PyTuple) {
            List<PyValue> values = new ArrayList<>(((PyTuple) left).getElements());
            values.addAll(((PyTuple) right).getElements());
            return new PyTuple(values);
        }
        throw badBinary("+", left, right);
    }

    private static PyValue multiply(PyValue left, PyValue right, boolean inPlace) {
        if (isNumeric(left) && isNumeric(right)) {
            return numericBinary(left, right, "MULTIPLY", '*');
        }
        if (isIntegerLike(right)) {
            return repeat(left, asInteger(right), inPlace);
        }
        if (isIntegerLike(left)) {
            return repeat(right, asInteger(left), false);
        }
        throw badBinary("*", left, right);
    }

    private static PyValue repeat(PyValue sequence, BigInteger countValue, boolean inPlace) {
        int count = boundedRepeatCount(countValue);
        if (sequence instanceof PyString) {
            String value = ((PyString) sequence).getValue();
            if ((long) value.length() * count > MAX_REPEAT) {
                throw error("RuntimeError", "repeated string is too large");
            }
            StringBuilder result = new StringBuilder(value.length() * count);
            for (int i = 0; i < count; i++) {
                result.append(value);
            }
            return new PyString(result.toString());
        }
        if (sequence instanceof PyList) {
            List<PyValue> original = ((PyList) sequence).getElements();
            ensureRepeatedSize(original.size(), count);
            PyList result = inPlace ? (PyList) sequence : new PyList();
            if (inPlace) {
                result.clear();
            }
            for (int i = 0; i < count; i++) {
                result.extend(original);
            }
            return result;
        }
        if (sequence instanceof PyTuple) {
            List<PyValue> original = ((PyTuple) sequence).getElements();
            ensureRepeatedSize(original.size(), count);
            List<PyValue> result = new ArrayList<>(original.size() * count);
            for (int i = 0; i < count; i++) {
                result.addAll(original);
            }
            return new PyTuple(result);
        }
        throw badBinary("*", sequence, new PyInt(countValue));
    }

    private static int boundedRepeatCount(BigInteger count) {
        if (count.signum() <= 0) {
            return 0;
        }
        if (count.compareTo(BigInteger.valueOf(MAX_REPEAT)) > 0) {
            throw error("RuntimeError", "repeated sequence is too large");
        }
        return count.intValue();
    }

    private static void ensureRepeatedSize(int size, int count) {
        if ((long) size * count > MAX_REPEAT) {
            throw error("RuntimeError", "repeated sequence is too large");
        }
    }

    private static PyValue numericBinary(
            PyValue left, PyValue right, String operation, char symbol) {
        requireNumeric(left, right, String.valueOf(symbol));
        if (left instanceof PyFloat || right instanceof PyFloat) {
            double a = asDouble(left);
            double b = asDouble(right);
            switch (operation) {
                case "ADD":
                    return new PyFloat(a + b);
                case "SUBTRACT":
                case "INPLACE_SUBTRACT":
                    return new PyFloat(a - b);
                case "MULTIPLY":
                    return new PyFloat(a * b);
                default:
                    throw new IllegalArgumentException("Unknown numeric operation " + operation);
            }
        }
        BigInteger a = asInteger(left);
        BigInteger b = asInteger(right);
        switch (operation) {
            case "ADD":
                return new PyInt(a.add(b));
            case "SUBTRACT":
            case "INPLACE_SUBTRACT":
                return new PyInt(a.subtract(b));
            case "MULTIPLY":
                return new PyInt(a.multiply(b));
            default:
                throw new IllegalArgumentException("Unknown numeric operation " + operation);
        }
    }

    private static PyValue trueDivide(PyValue left, PyValue right) {
        requireNumeric(left, right, "/");
        double divisor = asDouble(right);
        if (divisor == 0.0d) {
            throw error("ZeroDivisionError", "division by zero");
        }
        return new PyFloat(asDouble(left) / divisor);
    }

    private static PyValue floorDivide(PyValue left, PyValue right) {
        requireNumeric(left, right, "//");
        if (left instanceof PyFloat || right instanceof PyFloat) {
            double divisor = asDouble(right);
            if (divisor == 0.0d) {
                throw error("ZeroDivisionError", "float floor division by zero");
            }
            return new PyFloat(Math.floor(asDouble(left) / divisor));
        }
        BigInteger divisor = asInteger(right);
        if (divisor.signum() == 0) {
            throw error("ZeroDivisionError", "integer division or modulo by zero");
        }
        return new PyInt(floorDivideInteger(asInteger(left), divisor));
    }

    private static BigInteger floorDivideInteger(BigInteger dividend, BigInteger divisor) {
        BigInteger[] quotientRemainder = dividend.divideAndRemainder(divisor);
        BigInteger quotient = quotientRemainder[0];
        BigInteger remainder = quotientRemainder[1];
        if (remainder.signum() != 0 && dividend.signum() != divisor.signum()) {
            quotient = quotient.subtract(BigInteger.ONE);
        }
        return quotient;
    }

    private static PyValue modulo(PyValue left, PyValue right) {
        requireNumeric(left, right, "%");
        if (left instanceof PyFloat || right instanceof PyFloat) {
            double divisor = asDouble(right);
            if (divisor == 0.0d) {
                throw error("ZeroDivisionError", "float modulo");
            }
            double dividend = asDouble(left);
            double result = dividend - Math.floor(dividend / divisor) * divisor;
            if (result == 0.0d) {
                result = Math.copySign(0.0d, divisor);
            }
            return new PyFloat(result);
        }
        BigInteger dividend = asInteger(left);
        BigInteger divisor = asInteger(right);
        if (divisor.signum() == 0) {
            throw error("ZeroDivisionError", "integer division or modulo by zero");
        }
        BigInteger quotient = floorDivideInteger(dividend, divisor);
        return new PyInt(dividend.subtract(quotient.multiply(divisor)));
    }

    private static PyValue power(PyValue left, PyValue right) {
        requireNumeric(left, right, "**");
        if (isIntegerLike(left) && isIntegerLike(right)) {
            BigInteger base = asInteger(left);
            BigInteger exponent = asInteger(right);
            if (exponent.signum() >= 0) {
                if (exponent.bitLength() > 31 || exponent.intValue() > 1_000_000) {
                    throw error("RuntimeError", "integer exponent is too large");
                }
                return new PyInt(base.pow(exponent.intValue()));
            }
            if (base.signum() == 0) {
                throw error("ZeroDivisionError",
                        "0.0 cannot be raised to a negative power");
            }
        }
        double base = asDouble(left);
        double exponent = asDouble(right);
        if (base == 0.0d && exponent < 0.0d) {
            throw error("ZeroDivisionError", "0.0 cannot be raised to a negative power");
        }
        double result = Math.pow(base, exponent);
        if (Double.isNaN(result) && !Double.isNaN(base) && !Double.isNaN(exponent)) {
            throw error("ValueError", "negative number cannot be raised to a fractional power");
        }
        return new PyFloat(result);
    }

    // ------------------------------------------------------------------
    // Comparisons and membership
    // ------------------------------------------------------------------

    public static PyBool compare(String operator, PyValue left, PyValue right) {
        String op = normalize(operator);
        switch (op) {
            case "EQ":
            case "==":
                return PyBool.valueOf(equalsValue(left, right));
            case "NE":
            case "NEQ":
            case "!=":
                return PyBool.valueOf(!equalsValue(left, right));
            case "IS":
                return PyBool.valueOf(isIdentical(left, right));
            case "IN":
                return PyBool.valueOf(contains(right, left));
            case "LT":
            case "<":
                return orderedComparison(left, right, op, -1);
            case "LE":
            case "LTE":
            case "<=":
                return orderedComparison(left, right, op, -2);
            case "GT":
            case ">":
                return orderedComparison(left, right, op, 1);
            case "GE":
            case "GTE":
            case ">=":
                return orderedComparison(left, right, op, 2);
            default:
                throw new IllegalArgumentException("Unknown comparison operation: " + operator);
        }
    }

    private static PyBool orderedComparison(
            PyValue left, PyValue right, String operator, int predicate) {
        Integer comparison = order(left, right, operator);
        if (comparison == null) {
            return PyBool.FALSE;
        }
        switch (predicate) {
            case -1:
                return PyBool.valueOf(comparison < 0);
            case -2:
                return PyBool.valueOf(comparison <= 0);
            case 1:
                return PyBool.valueOf(comparison > 0);
            case 2:
                return PyBool.valueOf(comparison >= 0);
            default:
                throw new IllegalArgumentException("Unknown ordering predicate");
        }
    }

    /** Null means an unordered comparison, currently caused by NaN. */
    private static Integer order(PyValue left, PyValue right, String operator) {
        if (isNumeric(left) && isNumeric(right)) {
            return compareNumbers(left, right);
        }
        if (left instanceof PyString && right instanceof PyString) {
            return compareCodePoints(
                    ((PyString) left).getValue(), ((PyString) right).getValue());
        }
        if (left instanceof PyList && right instanceof PyList) {
            return sequenceOrder(
                    ((PyList) left).getElements(), ((PyList) right).getElements());
        }
        if (left instanceof PyTuple && right instanceof PyTuple) {
            return sequenceOrder(
                    ((PyTuple) left).getElements(), ((PyTuple) right).getElements());
        }
        throw error("TypeError", "'" + printableOperator(operator)
                + "' not supported between instances of '" + left.getTypeName()
                + "' and '" + right.getTypeName() + "'");
    }

    private static Integer sequenceOrder(List<PyValue> left, List<PyValue> right) {
        int common = Math.min(left.size(), right.size());
        for (int i = 0; i < common; i++) {
            PyValue a = left.get(i);
            PyValue b = right.get(i);
            if (!sameContainerElement(a, b)) {
                return order(a, b, "<");
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    public static boolean contains(PyValue container, PyValue needle) {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(needle, "needle");
        if (container instanceof PyString) {
            if (!(needle instanceof PyString)) {
                throw error("TypeError", "'in <string>' requires string as left operand, not "
                        + needle.getTypeName());
            }
            return ((PyString) container).getValue()
                    .contains(((PyString) needle).getValue());
        }
        if (container instanceof PyList) {
            return listContains(((PyList) container).getElements(), needle);
        }
        if (container instanceof PyTuple) {
            return listContains(((PyTuple) container).getElements(), needle);
        }
        if (container instanceof PySet) {
            return ((PySet) container).contains(needle);
        }
        if (container instanceof PyDict) {
            return ((PyDict) container).containsKey(needle);
        }
        if (container instanceof PyIterator) {
            Optional<PyValue> next;
            while ((next = ((PyIterator) container).tryNext()).isPresent()) {
                if (sameContainerElement(next.get(), needle)) {
                    return true;
                }
            }
            return false;
        }
        if (container instanceof PyRange) {
            if (!isIntegerLike(needle)) return false;
            PyRange range = (PyRange) container;
            BigInteger number = asInteger(needle);
            BigInteger step = range.getStep();
            boolean within = step.signum() > 0
                    ? number.compareTo(range.getStart()) >= 0
                            && number.compareTo(range.getStop()) < 0
                    : number.compareTo(range.getStart()) <= 0
                            && number.compareTo(range.getStop()) > 0;
            return within && number.subtract(range.getStart()).remainder(step).signum() == 0;
        }
        throw error("TypeError", "argument of type '" + container.getTypeName()
                + "' is not iterable");
    }

    private static boolean listContains(List<PyValue> values, PyValue needle) {
        for (PyValue value : values) {
            if (sameContainerElement(value, needle)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Iteration, unpacking, formatting
    // ------------------------------------------------------------------

    public static PyIterator iter(PyValue value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof PyIterator) {
            return (PyIterator) value;
        }
        if (value instanceof PyList) {
            return PyIterator.overList((PyList) value);
        }
        if (value instanceof PyTuple) {
            return new PyIterator(((PyTuple) value).getElements());
        }
        if (value instanceof PySet) {
            return new PyIterator(((PySet) value).getElements());
        }
        if (value instanceof PyDict) {
            return new PyIterator(((PyDict) value).getKeys());
        }
        if (value instanceof PyString) {
            String string = ((PyString) value).getValue();
            List<PyValue> characters = new ArrayList<>();
            for (int offset = 0; offset < string.length();) {
                int codePoint = string.codePointAt(offset);
                offset += Character.charCount(codePoint);
                characters.add(new PyString(new String(Character.toChars(codePoint))));
            }
            return new PyIterator(characters);
        }
        if (value instanceof PyRange) {
            return ((PyRange) value).iterator();
        }
        if (value instanceof PyReadableFile) {
            return ((PyReadableFile) value).lineIterator();
        }
        throw error("TypeError", "'" + value.getTypeName() + "' object is not iterable");
    }

    public static PyValue next(PyIterator iterator) {
        return iterator.tryNext().orElseThrow(
                () -> error("StopIteration", ""));
    }

    public static List<PyValue> unpack(PyValue value, int expectedCount) {
        if (expectedCount < 0) {
            throw new IllegalArgumentException("Expected count cannot be negative");
        }
        PyIterator iterator = iter(value);
        List<PyValue> result = new ArrayList<>(expectedCount);
        for (int i = 0; i < expectedCount; i++) {
            Optional<PyValue> next = iterator.tryNext();
            if (!next.isPresent()) {
                throw error("ValueError", "not enough values to unpack (expected "
                        + expectedCount + ", got " + i + ")");
            }
            result.add(next.get());
        }
        if (iterator.tryNext().isPresent()) {
            throw error("ValueError", "too many values to unpack (expected "
                    + expectedCount + ")");
        }
        return result;
    }

    public static PyString formatValue(PyValue value) {
        return new PyString(Objects.requireNonNull(value, "value").str());
    }

    public static PyString buildString(List<? extends PyValue> parts) {
        Objects.requireNonNull(parts, "parts");
        StringBuilder result = new StringBuilder();
        for (PyValue part : parts) {
            if (!(part instanceof PyString)) {
                throw error("TypeError", "BUILD_STRING expected str, got '"
                        + part.getTypeName() + "'");
            }
            result.append(((PyString) part).getValue());
        }
        return new PyString(result.toString());
    }

    // ------------------------------------------------------------------
    // Subscripts and attributes
    // ------------------------------------------------------------------

    public static PyValue loadSubscript(PyValue object, PyValue key) {
        if (object instanceof PyList) {
            PyList list = (PyList) object;
            return list.get(normalizeIndex(key, list.size(), "list"));
        }
        if (object instanceof PyTuple) {
            PyTuple tuple = (PyTuple) object;
            return tuple.get(normalizeIndex(key, tuple.size(), "tuple"));
        }
        if (object instanceof PyString) {
            PyString string = (PyString) object;
            int index = normalizeIndex(key, string.codePointLength(), "string");
            int offset = string.getValue().offsetByCodePoints(0, index);
            int codePoint = string.getValue().codePointAt(offset);
            return new PyString(new String(Character.toChars(codePoint)));
        }
        if (object instanceof PyDict) {
            return ((PyDict) object).find(key).orElseThrow(
                    () -> error("KeyError", key.repr()));
        }
        throw error("TypeError", "'" + object.getTypeName()
                + "' object is not subscriptable");
    }

    public static PyValue loadSubscript(
            PyValue object, PyValue key, VmCallContext context) {
        return loadSubscript(resolveDynamic(object, context),
                resolveDynamic(key, context));
    }

    public static void storeSubscript(PyValue object, PyValue key, PyValue value) {
        Objects.requireNonNull(value, "value");
        if (object instanceof PyList) {
            PyList list = (PyList) object;
            list.set(normalizeIndex(key, list.size(), "list"), value);
            return;
        }
        if (object instanceof PyDict) {
            ((PyDict) object).put(key, value);
            return;
        }
        throw error("TypeError", "'" + object.getTypeName()
                + "' object does not support item assignment");
    }

    public static void storeSubscript(
            PyValue object, PyValue key, PyValue value, VmCallContext context) {
        storeSubscript(resolveDynamic(object, context),
                resolveDynamic(key, context), resolveDynamic(value, context));
    }

    public static void deleteSubscript(PyValue object, PyValue key) {
        if (object instanceof PyList) {
            PyList list = (PyList) object;
            list.remove(normalizeIndex(key, list.size(), "list"));
            return;
        }
        if (object instanceof PyDict) {
            if (!((PyDict) object).delete(key)) {
                throw error("KeyError", key.repr());
            }
            return;
        }
        throw error("TypeError", "'" + object.getTypeName()
                + "' object does not support item deletion");
    }

    public static void deleteSubscript(
            PyValue object, PyValue key, VmCallContext context) {
        deleteSubscript(resolveDynamic(object, context),
                resolveDynamic(key, context));
    }

    public static PyValue loadAttribute(PyValue object, String name) {
        validateAttributeName(name);
        if (object instanceof PyAttributeProvider) {
            Optional<PyValue> value = ((PyAttributeProvider) object).findAttribute(name);
            if (value.isPresent()) {
                return value.get();
            }
        }
        Optional<PyValue> nativeMethod = NativeMethods.find(object, name);
        if (nativeMethod.isPresent()) {
            return nativeMethod.get();
        }
        throw error("AttributeError", "'" + object.getTypeName()
                + "' object has no attribute '" + name + "'");
    }

    public static PyValue loadAttribute(
            PyValue object, String name, VmCallContext context) {
        return loadAttribute(resolveDynamic(object, context), name);
    }

    public static void storeAttribute(PyValue object, String name, PyValue value) {
        validateAttributeName(name);
        Objects.requireNonNull(value, "value");
        if (object instanceof PyAttributeProvider
                && ((PyAttributeProvider) object).setAttribute(name, value)) {
            return;
        }
        throw error("AttributeError", "'" + object.getTypeName()
                + "' object has no writable attribute '" + name + "'");
    }

    public static void storeAttribute(
            PyValue object, String name, PyValue value, VmCallContext context) {
        storeAttribute(resolveDynamic(object, context), name,
                resolveDynamic(value, context));
    }

    public static void deleteAttribute(PyValue object, String name) {
        validateAttributeName(name);
        if (object instanceof PyAttributeProvider
                && ((PyAttributeProvider) object).deleteAttribute(name)) {
            return;
        }
        throw error("AttributeError", "'" + object.getTypeName()
                + "' object has no attribute '" + name + "'");
    }

    public static void deleteAttribute(
            PyValue object, String name, VmCallContext context) {
        deleteAttribute(resolveDynamic(object, context), name);
    }

    private static void validateAttributeName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Attribute name cannot be empty");
        }
    }

    private static int normalizeIndex(PyValue key, int size, String containerName) {
        if (!isIntegerLike(key)) {
            throw error("TypeError", containerName + " indices must be integers, not "
                    + key.getTypeName());
        }
        final int raw;
        try {
            raw = asInteger(key).intValueExact();
        } catch (ArithmeticException tooLarge) {
            throw error("IndexError", containerName + " index out of range");
        }
        int normalized = raw < 0 ? raw + size : raw;
        if (normalized < 0 || normalized >= size) {
            throw error("IndexError", containerName + " index out of range");
        }
        return normalized;
    }

    // ------------------------------------------------------------------
    // Numeric helpers and errors
    // ------------------------------------------------------------------

    private static boolean isNumeric(PyValue value) {
        return isIntegerLike(value) || value instanceof PyFloat;
    }

    private static boolean isIntegerLike(PyValue value) {
        return value instanceof PyInt || value instanceof PyBool;
    }

    private static BigInteger asInteger(PyValue value) {
        if (value instanceof PyInt) {
            return ((PyInt) value).getValue();
        }
        if (value instanceof PyBool) {
            return ((PyBool) value).getValue() ? BigInteger.ONE : BigInteger.ZERO;
        }
        throw new IllegalArgumentException("Not an integer-like value: " + value.getTypeName());
    }

    private static double asDouble(PyValue value) {
        if (value instanceof PyFloat) {
            return ((PyFloat) value).getValue();
        }
        return asInteger(value).doubleValue();
    }

    private static void requireNumeric(PyValue left, PyValue right, String operator) {
        if (!isNumeric(left) || !isNumeric(right)) {
            throw badBinary(operator, left, right);
        }
    }

    private static boolean numericEquals(PyValue left, PyValue right) {
        if (left instanceof PyFloat || right instanceof PyFloat) {
            double floatValue = left instanceof PyFloat
                    ? ((PyFloat) left).getValue()
                    : ((PyFloat) right).getValue();
            PyValue integerValue = left instanceof PyFloat ? right : left;
            if (left instanceof PyFloat && right instanceof PyFloat) {
                return ((PyFloat) left).getValue() == ((PyFloat) right).getValue();
            }
            if (!Double.isFinite(floatValue)) {
                return false;
            }
            try {
                BigInteger exactFloat = new BigDecimal(floatValue).toBigIntegerExact();
                return exactFloat.equals(asInteger(integerValue));
            } catch (ArithmeticException fractional) {
                return false;
            }
        }
        return asInteger(left).equals(asInteger(right));
    }

    /** Null means at least one numeric operand is NaN. */
    private static Integer compareNumbers(PyValue left, PyValue right) {
        if (left instanceof PyFloat && Double.isNaN(((PyFloat) left).getValue())
                || right instanceof PyFloat && Double.isNaN(((PyFloat) right).getValue())) {
            return null;
        }
        if (!(left instanceof PyFloat) && !(right instanceof PyFloat)) {
            return asInteger(left).compareTo(asInteger(right));
        }
        if (left instanceof PyFloat && right instanceof PyFloat) {
            double a = ((PyFloat) left).getValue();
            double b = ((PyFloat) right).getValue();
            if (a == b) {
                return 0;
            }
            return a < b ? -1 : 1;
        }

        PyValue integerValue = left instanceof PyFloat ? right : left;
        double floatValue = left instanceof PyFloat
                ? ((PyFloat) left).getValue()
                : ((PyFloat) right).getValue();
        int integerVsFloat;
        if (floatValue == Double.POSITIVE_INFINITY) {
            integerVsFloat = -1;
        } else if (floatValue == Double.NEGATIVE_INFINITY) {
            integerVsFloat = 1;
        } else {
            integerVsFloat = new BigDecimal(asInteger(integerValue))
                    .compareTo(new BigDecimal(floatValue));
        }
        return left instanceof PyFloat ? -integerVsFloat : integerVsFloat;
    }

    private static int compareCodePoints(String left, String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int a = left.codePointAt(leftOffset);
            int b = right.codePointAt(rightOffset);
            if (a != b) {
                return Integer.compare(a, b);
            }
            leftOffset += Character.charCount(a);
            rightOffset += Character.charCount(b);
        }
        return Integer.compare(
                left.codePointCount(0, left.length()),
                right.codePointCount(0, right.length()));
    }

    private static String normalize(String operator) {
        if (operator == null || operator.trim().isEmpty()) {
            throw new IllegalArgumentException("Operation cannot be empty");
        }
        String trimmed = operator.trim();
        if (trimmed.matches("[+*/%<>=!-]+")) {
            return trimmed;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static String printableOperator(String operation) {
        switch (operation) {
            case "LT": return "<";
            case "LE":
            case "LTE": return "<=";
            case "GT": return ">";
            case "GE":
            case "GTE": return ">=";
            default: return operation;
        }
    }

    private static VmRuntimeException badUnary(String operator, PyValue operand) {
        return error("TypeError", "bad operand type for unary " + operator + ": '"
                + operand.getTypeName() + "'");
    }

    private static VmRuntimeException badBinary(
            String operator, PyValue left, PyValue right) {
        return error("TypeError", "unsupported operand type(s) for " + operator
                + ": '" + left.getTypeName() + "' and '" + right.getTypeName() + "'");
    }

    public static VmRuntimeException error(String typeName, String message) {
        return VmRuntimeException.of(typeName, message);
    }
}
