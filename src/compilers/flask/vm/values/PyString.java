package compilers.flask.vm.values;

import java.util.Objects;

/** Immutable Python string backed by a Java Unicode string. */
public final class PyString implements PyValue {
    public static final PyString EMPTY = new PyString("");

    private final String value;

    public PyString(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public String getValue() {
        return value;
    }

    public int codePointLength() {
        return value.codePointCount(0, value.length());
    }

    @Override
    public String getTypeName() {
        return "str";
    }

    @Override
    public String repr() {
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('\'');
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            switch (codePoint) {
                case '\\':
                    result.append("\\\\");
                    break;
                case '\'':
                    result.append("\\'");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (Character.isISOControl(codePoint)) {
                        appendUnicodeEscape(result, codePoint);
                    } else {
                        result.appendCodePoint(codePoint);
                    }
            }
        }
        return result.append('\'').toString();
    }

    private static void appendUnicodeEscape(StringBuilder result, int codePoint) {
        String hex = Integer.toHexString(codePoint);
        result.append(codePoint <= 0xffff ? "\\u" : "\\U");
        int width = codePoint <= 0xffff ? 4 : 8;
        for (int i = hex.length(); i < width; i++) {
            result.append('0');
        }
        result.append(hex);
    }

    @Override
    public String str() {
        return value;
    }

    @Override
    public boolean isDeeplyImmutable() {
        return true;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PyString && value.equals(((PyString) other).value);
    }

    @Override
    public int hashCode() {
        return 31 * PyString.class.hashCode() + value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
