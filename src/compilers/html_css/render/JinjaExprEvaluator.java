package compilers.html_css.render;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Hand-written recursive-descent evaluator for the raw Jinja expression
 * strings stored in the template AST — the frozen grammar of plan
 * section 6.3.2. Values are the same plain-Java model that crosses the
 * Python⇄Jinja boundary (section 6.4).
 *
 * <pre>
 * expr       := or
 * or         := and ("or" and)*
 * and        := not ("and" not)*
 * not        := "not" not | comparison
 * comparison := additive (op additive)?          op: == != &lt; &lt;= &gt; &gt;= in
 * additive   := term (("+"|"-") term)*
 * term       := unary (("*"|"/"|"%") unary)*
 * unary      := ("-"|"+") unary | postfix
 * postfix    := primary ("." IDENT | "[" expr "]" | "(" args? ")" | "|" filter)*
 * primary    := NUMBER | STRING | true | false | none | IDENT | "(" expr ")" | "[" list "]"
 * </pre>
 */
public final class JinjaExprEvaluator {

    /** Frozen initial filter set (plan section 6.3.2). */
    private static final Set<String> FILTERS =
            Set.of("upper", "lower", "length", "default", "title");

    /** Resolves {@code url_for(...)} against the route map (plan 6.3.5). */
    public interface UrlResolver {
        String resolve(String endpoint, Map<String, Object> params, int line, int column);
    }

    /** Controlled evaluation failure — caller warns and substitutes empty. */
    public static final class JinjaEvalError extends RuntimeException {
        public JinjaEvalError(String message) {
            super(message);
        }
    }

    private final UrlResolver urlResolver;
    private final Deque<Map<String, Object>> frames = new ArrayDeque<>();
    private int line;
    private int column;

    private String source;
    private int position;

    public JinjaExprEvaluator(UrlResolver urlResolver) {
        this.urlResolver = Objects.requireNonNull(urlResolver, "urlResolver");
    }

    public void pushFrame(Map<String, Object> frame) {
        frames.push(frame);
    }

    public void popFrame() {
        frames.pop();
    }

    /** Evaluates one raw expression string at the given template location. */
    public Object evaluate(String expression, int line, int column) {
        this.source = Objects.requireNonNull(expression, "expression");
        this.position = 0;
        this.line = line;
        this.column = column;
        Object value = parseOr();
        skipSpaces();
        if (position < source.length()) {
            throw new JinjaEvalError("Unexpected trailing text '"
                    + source.substring(position).trim() + "' in expression");
        }
        return deref(value);
    }

    // ==================== parser/evaluator ====================

    private Object parseOr() {
        Object left = parseAnd();
        while (matchWord("or")) {
            // Jinja/Python semantics: keep the first truthy operand.
            Object right = parseAnd();
            if (!truthy(left)) {
                left = right;
            }
        }
        return left;
    }

    private Object parseAnd() {
        Object left = parseNot();
        while (matchWord("and")) {
            Object right = parseNot();
            if (truthy(left)) {
                left = right;
            }
        }
        return left;
    }

    private Object parseNot() {
        if (matchWord("not")) {
            return !truthy(parseNot());
        }
        return parseComparison();
    }

    private Object parseComparison() {
        Object left = parseAdditive();
        skipSpaces();
        String operator = null;
        if (matchSymbol("==")) {
            operator = "==";
        } else if (matchSymbol("!=")) {
            operator = "!=";
        } else if (matchSymbol("<=")) {
            operator = "<=";
        } else if (matchSymbol(">=")) {
            operator = ">=";
        } else if (matchSymbol("<")) {
            operator = "<";
        } else if (matchSymbol(">")) {
            operator = ">";
        } else if (matchWord("in")) {
            operator = "in";
        }
        if (operator == null) {
            return left;
        }
        Object right = parseAdditive();
        switch (operator) {
            case "==":
                return valueEquals(deref(left), deref(right));
            case "!=":
                return !valueEquals(deref(left), deref(right));
            case "<":
                return compare(left, right) < 0;
            case "<=":
                return compare(left, right) <= 0;
            case ">":
                return compare(left, right) > 0;
            case ">=":
                return compare(left, right) >= 0;
            case "in":
                return membership(left, right);
            default:
                throw new JinjaEvalError("Unsupported comparison '" + operator + "'");
        }
    }

    private Object parseAdditive() {
        Object left = parseTerm();
        while (true) {
            skipSpaces();
            if (matchSymbol("+")) {
                Object right = parseTerm();
                left = add(left, right);
            } else if (peekBinaryMinus()) {
                position++;
                Object right = parseTerm();
                left = numeric(left, right, "-");
            } else {
                return left;
            }
        }
    }

    private boolean peekBinaryMinus() {
        skipSpaces();
        return position < source.length() && source.charAt(position) == '-';
    }

    private Object parseTerm() {
        Object left = parseUnary();
        while (true) {
            skipSpaces();
            if (matchSymbol("*")) {
                left = numeric(left, parseUnary(), "*");
            } else if (matchSymbol("/")) {
                left = numeric(left, parseUnary(), "/");
            } else if (matchSymbol("%")) {
                left = numeric(left, parseUnary(), "%");
            } else {
                return left;
            }
        }
    }

    private Object parseUnary() {
        skipSpaces();
        if (matchSymbol("-")) {
            Object value = parseUnary();
            if (value instanceof BigInteger) {
                return ((BigInteger) value).negate();
            }
            if (value instanceof Double) {
                return -((Double) value);
            }
            throw new JinjaEvalError("Unary '-' needs a number, got " + typeName(value));
        }
        if (matchSymbol("+")) {
            return parseUnary();
        }
        return parsePostfix();
    }

    private Object parsePostfix() {
        Object value = parsePrimary();
        while (true) {
            skipSpaces();
            if (matchSymbol(".")) {
                String attribute = readIdentifier("attribute name");
                value = attributeOf(value, attribute);
            } else if (matchSymbol("[")) {
                Object key = parseOr();
                expectSymbol("]");
                value = subscriptOf(value, key);
            } else if (matchSymbol("(")) {
                value = callOf(value);
            } else if (matchSymbol("|")) {
                String filter = readIdentifier("filter name");
                List<Object> arguments = new ArrayList<>();
                skipSpaces();
                if (matchSymbol("(")) {
                    arguments = parseArguments().positional;
                }
                value = applyFilter(filter, value, arguments);
            } else {
                return value;
            }
        }
    }

    private Object parsePrimary() {
        skipSpaces();
        if (position >= source.length()) {
            throw new JinjaEvalError("Unexpected end of expression");
        }
        char current = source.charAt(position);
        if (current == '(') {
            position++;
            Object value = parseOr();
            expectSymbol(")");
            return value;
        }
        if (current == '[') {
            position++;
            List<Object> list = new ArrayList<>();
            skipSpaces();
            if (!matchSymbol("]")) {
                do {
                    list.add(parseOr());
                    skipSpaces();
                } while (matchSymbol(","));
                expectSymbol("]");
            }
            return list;
        }
        if (current == '\'' || current == '"') {
            return readString(current);
        }
        if (Character.isDigit(current)) {
            return readNumber();
        }
        if (Character.isLetter(current) || current == '_') {
            String word = readIdentifier("name");
            switch (word.toLowerCase(Locale.ROOT)) {
                case "true":
                    return Boolean.TRUE;
                case "false":
                    return Boolean.FALSE;
                case "none":
                    return null;
                default:
                    return new NameRef(word);
            }
        }
        throw new JinjaEvalError("Unexpected character '" + current + "' in expression");
    }

    /** Deferred name: resolved on use so url_for(...) can see the raw name. */
    private final class NameRef {
        final String name;

        NameRef(String name) {
            this.name = name;
        }

        Object resolve() {
            for (Map<String, Object> frame : frames) {
                if (frame.containsKey(name)) {
                    return frame.get(name);
                }
            }
            throw new JinjaEvalError("'" + name + "' is undefined in this template");
        }
    }

    // ==================== postfix behaviors ====================

    private Object attributeOf(Object receiver, String attribute) {
        Object value = deref(receiver);
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.containsKey(attribute)) {
                return map.get(attribute);
            }
            throw new JinjaEvalError("Attribute '" + attribute
                    + "' is missing on this value");
        }
        throw new JinjaEvalError("Cannot read attribute '" + attribute
                + "' of " + typeName(value));
    }

    private Object subscriptOf(Object receiver, Object key) {
        Object value = deref(receiver);
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            int index = intOf(key, "list index");
            if (index < 0) {
                index += list.size();
            }
            if (index < 0 || index >= list.size()) {
                throw new JinjaEvalError("List index " + key + " is out of range");
            }
            return list.get(index);
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.containsKey(key)) {
                return map.get(key);
            }
            throw new JinjaEvalError("Key " + toText(key) + " is missing");
        }
        throw new JinjaEvalError("Cannot index " + typeName(value));
    }

    private Object callOf(Object callee) {
        Arguments arguments = parseArguments();
        if (callee instanceof NameRef && "url_for".equals(((NameRef) callee).name)) {
            if (arguments.positional.isEmpty()
                    || !(arguments.positional.get(0) instanceof String)) {
                throw new JinjaEvalError("url_for() needs a literal endpoint name");
            }
            return urlResolver.resolve(
                    (String) arguments.positional.get(0),
                    arguments.keyword,
                    line,
                    column);
        }
        String name = callee instanceof NameRef
                ? ((NameRef) callee).name
                : typeName(deref(callee));
        throw new JinjaEvalError("Function '" + name
                + "' cannot be called inside a template");
    }

    private static final class Arguments {
        final List<Object> positional = new ArrayList<>();
        final Map<String, Object> keyword = new LinkedHashMap<>();
    }

    /** Parses call arguments after '('; supports name=value keywords. */
    private Arguments parseArguments() {
        Arguments arguments = new Arguments();
        skipSpaces();
        if (matchSymbol(")")) {
            return arguments;
        }
        do {
            skipSpaces();
            int mark = position;
            if (position < source.length()
                    && (Character.isLetter(source.charAt(position))
                            || source.charAt(position) == '_')) {
                String word = readIdentifier("argument");
                skipSpaces();
                if (matchSymbol("=") && !peekSymbol("=")) {
                    arguments.keyword.put(word, deref(parseOr()));
                    continue;
                }
                position = mark; // not a keyword argument — reparse as expression
            }
            arguments.positional.add(deref(parseOr()));
        } while (consumeComma());
        expectSymbol(")");
        return arguments;
    }

    private boolean consumeComma() {
        skipSpaces();
        return matchSymbol(",");
    }

    private Object applyFilter(String filter, Object receiver, List<Object> arguments) {
        if (!FILTERS.contains(filter)) {
            throw new JinjaEvalError("Unknown filter '| " + filter + "'");
        }
        switch (filter) {
            case "default": {
                Object fallback = arguments.isEmpty() ? "" : arguments.get(0);
                try {
                    Object value = deref(receiver);
                    return value == null ? fallback : value;
                } catch (JinjaEvalError undefined) {
                    return fallback;
                }
            }
            case "upper":
                return textOf(deref(receiver)).toUpperCase(Locale.ROOT);
            case "lower":
                return textOf(deref(receiver)).toLowerCase(Locale.ROOT);
            case "title": {
                String text = textOf(deref(receiver));
                StringBuilder result = new StringBuilder(text.length());
                boolean boundary = true;
                for (char c : text.toCharArray()) {
                    result.append(boundary
                            ? Character.toUpperCase(c)
                            : Character.toLowerCase(c));
                    boundary = !Character.isLetter(c);
                }
                return result.toString();
            }
            case "length": {
                Object value = deref(receiver);
                if (value instanceof String) {
                    return BigInteger.valueOf(((String) value).length());
                }
                if (value instanceof List) {
                    return BigInteger.valueOf(((List<?>) value).size());
                }
                if (value instanceof Map) {
                    return BigInteger.valueOf(((Map<?, ?>) value).size());
                }
                throw new JinjaEvalError("| length needs a string/list/dict");
            }
            default:
                throw new JinjaEvalError("Unknown filter '| " + filter + "'");
        }
    }

    // ==================== arithmetic ====================

    private Object add(Object leftRaw, Object rightRaw) {
        Object left = deref(leftRaw);
        Object right = deref(rightRaw);
        if (left instanceof String || right instanceof String) {
            return textOf(left) + textOf(right);
        }
        return numeric(left, right, "+");
    }

    private Object numeric(Object leftRaw, Object rightRaw, String operator) {
        Object left = deref(leftRaw);
        Object right = deref(rightRaw);
        if (left instanceof BigInteger && right instanceof BigInteger) {
            BigInteger a = (BigInteger) left;
            BigInteger b = (BigInteger) right;
            switch (operator) {
                case "+":
                    return a.add(b);
                case "-":
                    return a.subtract(b);
                case "*":
                    return a.multiply(b);
                case "/": {
                    if (b.signum() == 0) {
                        throw new JinjaEvalError("Division by zero");
                    }
                    return a.doubleValue() / b.doubleValue();
                }
                case "%": {
                    if (b.signum() == 0) {
                        throw new JinjaEvalError("Modulo by zero");
                    }
                    return a.mod(b.abs());
                }
                default:
                    throw new JinjaEvalError("Unsupported operator '" + operator + "'");
            }
        }
        double a = doubleOf(left);
        double b = doubleOf(right);
        switch (operator) {
            case "+":
                return a + b;
            case "-":
                return a - b;
            case "*":
                return a * b;
            case "/":
                if (b == 0.0) {
                    throw new JinjaEvalError("Division by zero");
                }
                return a / b;
            case "%":
                if (b == 0.0) {
                    throw new JinjaEvalError("Modulo by zero");
                }
                return a % b;
            default:
                throw new JinjaEvalError("Unsupported operator '" + operator + "'");
        }
    }

    private int compare(Object leftRaw, Object rightRaw) {
        Object left = deref(leftRaw);
        Object right = deref(rightRaw);
        if (left instanceof String && right instanceof String) {
            return ((String) left).compareTo((String) right);
        }
        return Double.compare(doubleOf(left), doubleOf(right));
    }

    private boolean membership(Object needleRaw, Object haystackRaw) {
        Object needle = deref(needleRaw);
        Object haystack = deref(haystackRaw);
        if (haystack instanceof List) {
            for (Object item : (List<?>) haystack) {
                if (valueEquals(item, needle)) {
                    return true;
                }
            }
            return false;
        }
        if (haystack instanceof Map) {
            return ((Map<?, ?>) haystack).containsKey(needle);
        }
        if (haystack instanceof String && needle instanceof String) {
            return ((String) haystack).contains((String) needle);
        }
        throw new JinjaEvalError("'in' needs a list/dict/string");
    }

    // ==================== lexing helpers ====================

    private void skipSpaces() {
        while (position < source.length()
                && Character.isWhitespace(source.charAt(position))) {
            position++;
        }
    }

    private boolean matchSymbol(String symbol) {
        skipSpaces();
        if (source.startsWith(symbol, position)) {
            // Avoid eating '==' as '=' or '<=' as '<'.
            if (("=".equals(symbol) || "<".equals(symbol) || ">".equals(symbol)
                    || "!".equals(symbol))
                    && position + 1 < source.length()
                    && source.charAt(position + 1) == '=') {
                return false;
            }
            position += symbol.length();
            return true;
        }
        return false;
    }

    private boolean peekSymbol(String symbol) {
        skipSpaces();
        return source.startsWith(symbol, position);
    }

    private void expectSymbol(String symbol) {
        if (!matchSymbol(symbol)) {
            throw new JinjaEvalError("Expected '" + symbol + "' in expression");
        }
    }

    private boolean matchWord(String word) {
        skipSpaces();
        int end = position + word.length();
        if (end <= source.length()
                && source.regionMatches(position, word, 0, word.length())
                && (end == source.length()
                        || (!Character.isLetterOrDigit(source.charAt(end))
                                && source.charAt(end) != '_'))) {
            position = end;
            return true;
        }
        return false;
    }

    private String readIdentifier(String label) {
        skipSpaces();
        int start = position;
        while (position < source.length()
                && (Character.isLetterOrDigit(source.charAt(position))
                        || source.charAt(position) == '_')) {
            position++;
        }
        if (start == position) {
            throw new JinjaEvalError("Expected " + label + " in expression");
        }
        return source.substring(start, position);
    }

    private Object readNumber() {
        int start = position;
        boolean isFloat = false;
        while (position < source.length()) {
            char c = source.charAt(position);
            if (Character.isDigit(c)) {
                position++;
            } else if (c == '.' && !isFloat
                    && position + 1 < source.length()
                    && Character.isDigit(source.charAt(position + 1))) {
                isFloat = true;
                position++;
            } else {
                break;
            }
        }
        String text = source.substring(start, position);
        return isFloat ? (Object) Double.parseDouble(text) : new BigInteger(text);
    }

    private String readString(char quote) {
        position++; // opening quote
        StringBuilder text = new StringBuilder();
        while (position < source.length() && source.charAt(position) != quote) {
            char c = source.charAt(position++);
            if (c == '\\' && position < source.length()) {
                char escaped = source.charAt(position++);
                switch (escaped) {
                    case 'n':
                        text.append('\n');
                        break;
                    case 't':
                        text.append('\t');
                        break;
                    default:
                        text.append(escaped);
                }
            } else {
                text.append(c);
            }
        }
        if (position >= source.length()) {
            throw new JinjaEvalError("Unterminated string in expression");
        }
        position++; // closing quote
        return text.toString();
    }

    // ==================== value helpers (frozen section 6.3.3) ====================

    /** Resolves deferred names; all consumers go through this. */
    public Object deref(Object value) {
        return value instanceof NameRef ? ((NameRef) value).resolve() : value;
    }

    private int intOf(Object rawValue, String label) {
        Object value = deref(rawValue);
        if (value instanceof BigInteger) {
            return ((BigInteger) value).intValueExact();
        }
        throw new JinjaEvalError("An integer " + label + " is required");
    }

    private double doubleOf(Object rawValue) {
        Object value = deref(rawValue);
        if (value instanceof BigInteger) {
            return ((BigInteger) value).doubleValue();
        }
        if (value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1.0 : 0.0;
        }
        throw new JinjaEvalError("A number is required, got " + typeName(value));
    }

    private String textOf(Object value) {
        return toText(deref(value));
    }

    public static boolean truth(Object rawValue) {
        if (rawValue == null) {
            return false;
        }
        if (rawValue instanceof Boolean) {
            return (Boolean) rawValue;
        }
        if (rawValue instanceof BigInteger) {
            return ((BigInteger) rawValue).signum() != 0;
        }
        if (rawValue instanceof Double) {
            return (Double) rawValue != 0.0;
        }
        if (rawValue instanceof String) {
            return !((String) rawValue).isEmpty();
        }
        if (rawValue instanceof List) {
            return !((List<?>) rawValue).isEmpty();
        }
        if (rawValue instanceof Map) {
            return !((Map<?, ?>) rawValue).isEmpty();
        }
        return true;
    }

    /** Truthiness over a possibly deferred value (names resolve first). */
    public boolean truthy(Object value) {
        return truth(deref(value));
    }

    public static boolean valueEquals(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        boolean leftNumber = left instanceof BigInteger || left instanceof Double;
        boolean rightNumber = right instanceof BigInteger || right instanceof Double;
        if (leftNumber && rightNumber) {
            if (left instanceof BigInteger && right instanceof BigInteger) {
                return left.equals(right);
            }
            double a = left instanceof BigInteger
                    ? ((BigInteger) left).doubleValue() : (Double) left;
            double b = right instanceof BigInteger
                    ? ((BigInteger) right).doubleValue() : (Double) right;
            return a == b;
        }
        return left.equals(right);
    }

    /**
     * Value → output text per the frozen table of plan section 6.3.3:
     * None → empty, True/False Python-style, integers undotted.
     */
    public static String toText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "True" : "False";
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof BigInteger) {
            return value.toString();
        }
        if (value instanceof Double) {
            return Double.toString((Double) value);
        }
        if (value instanceof List) {
            StringBuilder text = new StringBuilder("[");
            List<?> list = (List<?>) value;
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    text.append(", ");
                }
                text.append(reprText(list.get(index)));
            }
            return text.append("]").toString();
        }
        if (value instanceof Map) {
            StringBuilder text = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    text.append(", ");
                }
                text.append(reprText(entry.getKey()))
                        .append(": ")
                        .append(reprText(entry.getValue()));
                first = false;
            }
            return text.append("}").toString();
        }
        return String.valueOf(value);
    }

    private static String reprText(Object value) {
        if (value instanceof String) {
            return "'" + value + "'";
        }
        if (value == null) {
            return "None";
        }
        return toText(value);
    }

    /** Default-on HTML escaping for {@code {{ }}} output (plan 6.3.3). */
    public static String escapeHtml(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            switch (c) {
                case '&':
                    escaped.append("&amp;");
                    break;
                case '<':
                    escaped.append("&lt;");
                    break;
                case '>':
                    escaped.append("&gt;");
                    break;
                case '"':
                    escaped.append("&quot;");
                    break;
                case '\'':
                    escaped.append("&#39;");
                    break;
                default:
                    escaped.append(c);
            }
        }
        return escaped.toString();
    }

    public static String typeName(Object value) {
        if (value == null) {
            return "none";
        }
        if (value instanceof BigInteger) {
            return "int";
        }
        if (value instanceof Double) {
            return "float";
        }
        if (value instanceof String) {
            return "str";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof List) {
            return "list";
        }
        if (value instanceof Map) {
            return "dict";
        }
        return value.getClass().getSimpleName();
    }
}
