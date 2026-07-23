package compilers.flask.generation;

import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.Statement;
import compilers.flask.ast.nodes.expressions.access.AttributeAccessNode;
import compilers.flask.ast.nodes.expressions.access.FunctionCallNode;
import compilers.flask.ast.nodes.expressions.access.SubscriptNode;
import compilers.flask.ast.nodes.expressions.atoms.DictNode;
import compilers.flask.ast.nodes.expressions.atoms.FStringNode;
import compilers.flask.ast.nodes.expressions.atoms.FStringPart;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.expressions.atoms.ListNode;
import compilers.flask.ast.nodes.expressions.atoms.LiteralNode;
import compilers.flask.ast.nodes.expressions.atoms.SetNode;
import compilers.flask.ast.nodes.expressions.atoms.TupleNode;
import compilers.flask.ast.nodes.expressions.operations.BinaryOpNode;
import compilers.flask.ast.nodes.expressions.operations.CompareNode;
import compilers.flask.ast.nodes.expressions.operations.UnaryOpNode;
import compilers.flask.ast.nodes.helpers.CallArgument;
import compilers.flask.ast.nodes.helpers.Parameter;
import compilers.flask.ast.nodes.statements.compound.ForStatementNode;
import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;
import compilers.flask.ast.nodes.statements.compound.IfStatementNode;
import compilers.flask.ast.nodes.statements.compound.WhileStatementNode;
import compilers.flask.ast.nodes.statements.simple.AssignmentNode;
import compilers.flask.ast.nodes.statements.simple.BreakNode;
import compilers.flask.ast.nodes.statements.simple.ContinueNode;
import compilers.flask.ast.nodes.statements.simple.ExpressionStatementNode;
import compilers.flask.ast.nodes.statements.simple.GlobalNode;
import compilers.flask.ast.nodes.statements.simple.PassNode;
import compilers.flask.ast.nodes.statements.simple.ReturnNode;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Small tree-walking evaluator over the Python AST — the generation-stage
 * replacement for the deleted VM (plan section 5).
 *
 * <p>Values are plain Java: {@link BigInteger} (int), {@link Double} (float),
 * {@link String}, {@link Boolean}, {@code null} (None), {@link ArrayList}
 * (list/tuple), {@link LinkedHashMap} (dict), {@link LinkedHashSet} (set).
 * There is deliberately no symbol table here (frozen rule 1.5): name lookup
 * is a plain frame chain.</p>
 *
 * <p>Anything outside the supported subset raises {@link EvalError} — a
 * diagnostic, never a silently wrong value (plan section 5.3).</p>
 */
public final class PyEval {

    /** Evaluation budget: hard cap on executed evaluation steps. */
    private static final int MAX_STEPS = 200_000;
    /** Hard cap on user-function call depth. */
    private static final int MAX_CALL_DEPTH = 64;
    /** Hard cap on a {@code range(...)} materialization. */
    private static final BigInteger MAX_RANGE = BigInteger.valueOf(100_000);

    /** Intercepts calls the evaluator does not own (render_template, Flask, …). */
    public interface CallInterceptor {
        /** @return the call result, or {@link #SKIP} when not intercepted. */
        Object intercept(
                String functionName,
                List<Object> positional,
                Map<String, Object> keyword,
                ASTNode site);

        /** @return the method-call result, or {@link #SKIP} when not intercepted. */
        Object interceptMethod(
                Object receiver,
                String methodName,
                List<Object> positional,
                Map<String, Object> keyword,
                ASTNode site);
    }

    /** Sentinel returned by an interceptor that declines a call. */
    public static final Object SKIP = new Object();

    /** Controlled evaluation failure: reported as a diagnostic by the caller. */
    public static final class EvalError extends RuntimeException {
        private final transient ASTNode site;

        public EvalError(String message, ASTNode site) {
            super(message);
            this.site = site;
        }

        public int line() {
            return site == null ? 0 : site.getLine();
        }

        public int column() {
            return site == null ? 0 : site.getColumn();
        }
    }

    /** Statement-block outcome. */
    public static final class Signal {
        public enum Kind { NONE, BREAK, CONTINUE, RETURN }

        public static final Signal NONE = new Signal(Kind.NONE, null);
        public static final Signal BREAK = new Signal(Kind.BREAK, null);
        public static final Signal CONTINUE = new Signal(Kind.CONTINUE, null);

        private final Kind kind;
        private final Object value;

        private Signal(Kind kind, Object value) {
            this.kind = kind;
            this.value = value;
        }

        public static Signal returning(Object value) {
            return new Signal(Kind.RETURN, value);
        }

        public Kind kind() {
            return kind;
        }

        public Object value() {
            return value;
        }
    }

    private static final class Frame {
        final Map<String, Object> names;
        final Set<String> declaredGlobals = new HashSet<>();

        Frame(Map<String, Object> names) {
            this.names = names;
        }
    }

    private static final class UserFunction {
        final FunctionDefNode definition;
        final List<Object> defaults; // aligned to the trailing parameters

        UserFunction(FunctionDefNode definition, List<Object> defaults) {
            this.definition = definition;
            this.defaults = defaults;
        }
    }

    private final Map<String, Object> moduleEnv;
    private final CallInterceptor interceptor;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private final Map<String, UserFunction> functions = new LinkedHashMap<>();
    private int steps;
    private int callDepth;
    private boolean lenientConditions;
    private List<String> warnSink;

    public PyEval(Map<String, Object> moduleEnv, CallInterceptor interceptor) {
        this.moduleEnv = Objects.requireNonNull(moduleEnv, "moduleEnv");
        this.interceptor = Objects.requireNonNull(interceptor, "interceptor");
        frames.push(new Frame(moduleEnv));
    }

    /**
     * Route mode (plan section 5.4): an {@code if} condition that cannot be
     * evaluated (e.g. it reads {@code request}) is logged and treated as
     * False, so the GET view of the route is what gets rendered.
     */
    public void setLenientConditions(boolean lenient, List<String> warnSink) {
        this.lenientConditions = lenient;
        this.warnSink = warnSink;
    }

    /** Registers a module-level function; defaults are evaluated at def time. */
    public void registerFunction(FunctionDefNode definition) {
        List<Object> defaults = new ArrayList<>();
        for (Parameter parameter : definition.getParameters()) {
            if (parameter.getDefaultValue() != null) {
                defaults.add(eval(parameter.getDefaultValue()));
            }
        }
        functions.put(definition.getName(), new UserFunction(definition, defaults));
    }

    public boolean hasFunction(String name) {
        return functions.containsKey(name);
    }

    // ==================== statements ====================

    /** Executes a block; frames/budget shared with expression evaluation. */
    public Signal execBlock(List<Statement> body) {
        for (Statement statement : body) {
            Signal signal = exec(statement);
            if (signal.kind() != Signal.Kind.NONE) {
                return signal;
            }
        }
        return Signal.NONE;
    }

    public Signal exec(Statement statement) {
        step(statement);
        if (statement instanceof ExpressionStatementNode) {
            eval(((ExpressionStatementNode) statement).getExpression());
            return Signal.NONE;
        }
        if (statement instanceof AssignmentNode) {
            return execAssignment((AssignmentNode) statement);
        }
        if (statement instanceof IfStatementNode) {
            return execIf((IfStatementNode) statement);
        }
        if (statement instanceof ForStatementNode) {
            return execFor((ForStatementNode) statement);
        }
        if (statement instanceof WhileStatementNode) {
            return execWhile((WhileStatementNode) statement);
        }
        if (statement instanceof ReturnNode) {
            ReturnNode node = (ReturnNode) statement;
            return Signal.returning(node.hasValue() ? eval(node.getValue()) : null);
        }
        if (statement instanceof PassNode) {
            return Signal.NONE;
        }
        if (statement instanceof BreakNode) {
            return Signal.BREAK;
        }
        if (statement instanceof ContinueNode) {
            return Signal.CONTINUE;
        }
        if (statement instanceof GlobalNode) {
            frames.peek().declaredGlobals.addAll(((GlobalNode) statement).getNames());
            return Signal.NONE;
        }
        throw new EvalError("Unsupported statement in generation evaluator: "
                + statement.getNodeType(), statement);
    }

    private Signal execAssignment(AssignmentNode node) {
        if (node.isAugmented()) {
            Object current = eval(node.getTarget());
            Object operand = eval(node.getValue());
            String operator = node.getOperator();
            String base = operator.substring(0, operator.length() - 1);
            Object result = binary(base, current, operand, node);
            store(node.getTarget(), result);
            return Signal.NONE;
        }
        Object value = eval(node.getValue());
        store(node.getTarget(), value);
        return Signal.NONE;
    }

    private Signal execIf(IfStatementNode node) {
        if (condition(node.getCondition())) {
            return execBlock(node.getThenBody());
        }
        for (IfStatementNode.ElifClause clause : node.getElifClauses()) {
            if (condition(clause.getCondition())) {
                return execBlock(clause.getBody());
            }
        }
        if (node.hasElse()) {
            return execBlock(node.getElseBody());
        }
        return Signal.NONE;
    }

    private boolean condition(Expression expression) {
        if (!lenientConditions) {
            return truth(eval(expression));
        }
        try {
            return truth(eval(expression));
        } catch (EvalError unsupported) {
            if (warnSink != null) {
                warnSink.add("[warn]      condition not evaluable ("
                        + unsupported.getMessage() + ") -> treated as False (GET view)");
            }
            return false;
        }
    }

    private Signal execFor(ForStatementNode node) {
        Object iterable = eval(node.getIterable());
        List<Object> items = iterate(iterable, node);
        boolean broke = false;
        for (Object item : items) {
            step(node);
            store(node.getTarget(), item);
            Signal signal = execBlock(node.getBody());
            if (signal.kind() == Signal.Kind.RETURN) {
                return signal;
            }
            if (signal.kind() == Signal.Kind.BREAK) {
                broke = true;
                break;
            }
        }
        if (!broke && node.hasElse()) {
            return execBlock(node.getElseBody());
        }
        return Signal.NONE;
    }

    private Signal execWhile(WhileStatementNode node) {
        boolean broke = false;
        while (truth(eval(node.getCondition()))) {
            step(node);
            Signal signal = execBlock(node.getBody());
            if (signal.kind() == Signal.Kind.RETURN) {
                return signal;
            }
            if (signal.kind() == Signal.Kind.BREAK) {
                broke = true;
                break;
            }
        }
        if (!broke && node.hasElse()) {
            return execBlock(node.getElseBody());
        }
        return Signal.NONE;
    }

    // ==================== stores ====================

    private void store(Expression target, Object value) {
        if (target instanceof IdentifierNode) {
            String name = ((IdentifierNode) target).getName();
            Frame frame = frames.peek();
            if (frame.declaredGlobals.contains(name)) {
                moduleEnv.put(name, value);
            } else {
                frame.names.put(name, value);
            }
            return;
        }
        if (target instanceof AttributeAccessNode) {
            AttributeAccessNode access = (AttributeAccessNode) target;
            Object receiver = eval(access.getObject());
            if (receiver instanceof Map) {
                mapPut(receiver, access.getAttribute(), value);
                return;
            }
            throw new EvalError("Cannot assign attribute '" + access.getAttribute()
                    + "' on " + typeName(receiver), target);
        }
        if (target instanceof SubscriptNode) {
            SubscriptNode subscript = (SubscriptNode) target;
            Object receiver = eval(subscript.getObject());
            Object key = eval(subscript.getIndex());
            if (receiver instanceof List) {
                List<Object> list = asObjectList(receiver);
                list.set(listIndex(list, key, target), value);
                return;
            }
            if (receiver instanceof Map) {
                mapPut(receiver, key, value);
                return;
            }
            throw new EvalError("Cannot assign into " + typeName(receiver), target);
        }
        if (target instanceof TupleNode || target instanceof ListNode) {
            List<Expression> parts = target instanceof TupleNode
                    ? ((TupleNode) target).getElements()
                    : ((ListNode) target).getElements();
            List<Object> values = iterate(value, target);
            if (values.size() != parts.size()) {
                throw new EvalError("Cannot unpack " + values.size()
                        + " value(s) into " + parts.size() + " target(s)", target);
            }
            for (int index = 0; index < parts.size(); index++) {
                store(parts.get(index), values.get(index));
            }
            return;
        }
        throw new EvalError("Unsupported assignment target: "
                + target.getNodeType(), target);
    }

    // ==================== expressions ====================

    public Object eval(Expression expression) {
        step(expression);
        if (expression instanceof LiteralNode) {
            return normalize(((LiteralNode) expression).getValue());
        }
        if (expression instanceof IdentifierNode) {
            return lookup((IdentifierNode) expression);
        }
        if (expression instanceof ListNode) {
            List<Object> list = new ArrayList<>();
            for (Expression element : ((ListNode) expression).getElements()) {
                list.add(eval(element));
            }
            return list;
        }
        if (expression instanceof TupleNode) {
            List<Object> tuple = new ArrayList<>();
            for (Expression element : ((TupleNode) expression).getElements()) {
                tuple.add(eval(element));
            }
            return tuple;
        }
        if (expression instanceof SetNode) {
            Set<Object> set = new LinkedHashSet<>();
            for (Expression element : ((SetNode) expression).getElements()) {
                set.add(eval(element));
            }
            return set;
        }
        if (expression instanceof DictNode) {
            Map<Object, Object> map = new LinkedHashMap<>();
            for (DictNode.DictItem item : ((DictNode) expression).getItems()) {
                map.put(eval(item.getKey()), eval(item.getValue()));
            }
            return map;
        }
        if (expression instanceof FStringNode) {
            StringBuilder text = new StringBuilder();
            for (FStringPart part : ((FStringNode) expression).getParts()) {
                if (part instanceof FStringPart.StringPart) {
                    text.append(((FStringPart.StringPart) part).getValue());
                } else {
                    text.append(pyStr(eval(
                            ((FStringPart.ExpressionPart) part).getExpression())));
                }
            }
            return text.toString();
        }
        if (expression instanceof UnaryOpNode) {
            UnaryOpNode node = (UnaryOpNode) expression;
            Object operand = eval(node.getOperand());
            switch (node.getOperator()) {
                case "not":
                    return !truth(operand);
                case "-":
                    return negate(operand, node);
                case "+":
                    requireNumber(operand, node);
                    return operand;
                default:
                    throw new EvalError("Unsupported unary operator '"
                            + node.getOperator() + "'", node);
            }
        }
        if (expression instanceof BinaryOpNode) {
            BinaryOpNode node = (BinaryOpNode) expression;
            String operator = node.getOperator();
            if ("and".equals(operator)) {
                Object left = eval(node.getLeft());
                return truth(left) ? eval(node.getRight()) : left;
            }
            if ("or".equals(operator)) {
                Object left = eval(node.getLeft());
                return truth(left) ? left : eval(node.getRight());
            }
            return binary(operator, eval(node.getLeft()), eval(node.getRight()), node);
        }
        if (expression instanceof CompareNode) {
            return evalCompare((CompareNode) expression);
        }
        if (expression instanceof AttributeAccessNode) {
            AttributeAccessNode node = (AttributeAccessNode) expression;
            Object receiver = eval(node.getObject());
            if (receiver instanceof Map) {
                return ((Map<?, ?>) receiver).get(node.getAttribute());
            }
            throw new EvalError("Unsupported attribute access '." + node.getAttribute()
                    + "' on " + typeName(receiver), node);
        }
        if (expression instanceof SubscriptNode) {
            SubscriptNode node = (SubscriptNode) expression;
            Object receiver = eval(node.getObject());
            Object key = eval(node.getIndex());
            if (receiver instanceof List) {
                List<Object> list = asObjectList(receiver);
                return list.get(listIndex(list, key, node));
            }
            if (receiver instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) receiver;
                if (!map.containsKey(key)) {
                    throw new EvalError("KeyError: " + pyRepr(key), node);
                }
                return map.get(key);
            }
            if (receiver instanceof String) {
                String text = (String) receiver;
                int index = intIndex(key, text.length(), node);
                return String.valueOf(text.charAt(index));
            }
            throw new EvalError("Unsupported subscript on " + typeName(receiver), node);
        }
        if (expression instanceof FunctionCallNode) {
            return evalCall((FunctionCallNode) expression);
        }
        throw new EvalError("Unsupported expression in generation evaluator: "
                + expression.getNodeType(), expression);
    }

    private Object lookup(IdentifierNode node) {
        String name = node.getName();
        Frame frame = frames.peek();
        if (!frame.declaredGlobals.contains(name) && frame.names.containsKey(name)) {
            return frame.names.get(name);
        }
        if (moduleEnv.containsKey(name)) {
            return moduleEnv.get(name);
        }
        throw new EvalError("name '" + name + "' is not defined for generation", node);
    }

    private Object evalCompare(CompareNode node) {
        Object left = eval(node.getLeft());
        List<CompareNode.CompareOp> operators = node.getOperators();
        List<Expression> comparators = node.getComparators();
        for (int index = 0; index < operators.size(); index++) {
            Object right = eval(comparators.get(index));
            if (!compareOnce(operators.get(index), left, right, node)) {
                return Boolean.FALSE;
            }
            left = right;
        }
        return Boolean.TRUE;
    }

    private boolean compareOnce(
            CompareNode.CompareOp op, Object left, Object right, ASTNode site) {
        switch (op) {
            case EQ:
                return valueEquals(left, right);
            case NEQ:
                return !valueEquals(left, right);
            case LT:
                return numberCompare(left, right, site) < 0;
            case LTE:
                return numberCompare(left, right, site) <= 0;
            case GT:
                return numberCompare(left, right, site) > 0;
            case GTE:
                return numberCompare(left, right, site) >= 0;
            case IN:
                return membership(left, right, site);
            case IS:
                return left == right
                        || (left == null && right == null)
                        || (left instanceof Boolean && left.equals(right));
            default:
                throw new EvalError("Unsupported comparison '" + op + "'", site);
        }
    }

    private boolean membership(Object needle, Object haystack, ASTNode site) {
        if (haystack instanceof Map) {
            return ((Map<?, ?>) haystack).containsKey(needle);
        }
        if (haystack instanceof List) {
            for (Object item : (List<?>) haystack) {
                if (valueEquals(needle, item)) {
                    return true;
                }
            }
            return false;
        }
        if (haystack instanceof Set) {
            for (Object item : (Set<?>) haystack) {
                if (valueEquals(needle, item)) {
                    return true;
                }
            }
            return false;
        }
        if (haystack instanceof String && needle instanceof String) {
            return ((String) haystack).contains((String) needle);
        }
        throw new EvalError("Unsupported 'in' on " + typeName(haystack), site);
    }

    // ==================== calls ====================

    private Object evalCall(FunctionCallNode node) {
        List<Object> positional = new ArrayList<>();
        Map<String, Object> keyword = new LinkedHashMap<>();
        for (CallArgument argument : node.getArguments()) {
            Object value = eval(argument.getValue());
            if (argument.isKeyword()) {
                keyword.put(argument.getKeywordName(), value);
            } else {
                positional.add(value);
            }
        }

        Expression callee = node.getFunction();
        if (callee instanceof IdentifierNode) {
            String name = ((IdentifierNode) callee).getName();
            UserFunction function = functions.get(name);
            if (function != null) {
                return callUserFunction(function, positional, keyword, node);
            }
            Object builtin = builtinCall(name, positional, keyword, node);
            if (builtin != SKIP) {
                return builtin;
            }
            Object intercepted = interceptor.intercept(name, positional, keyword, node);
            if (intercepted != SKIP) {
                return intercepted;
            }
            throw new EvalError("Unsupported function '" + name + "' in generation", node);
        }
        if (callee instanceof AttributeAccessNode) {
            AttributeAccessNode access = (AttributeAccessNode) callee;
            Object receiver = eval(access.getObject());
            String method = access.getAttribute();
            Object result = methodCall(receiver, method, positional, keyword, node);
            if (result != SKIP) {
                return result;
            }
            Object intercepted = interceptor.interceptMethod(
                    receiver, method, positional, keyword, node);
            if (intercepted != SKIP) {
                return intercepted;
            }
            throw new EvalError("Unsupported method '." + method + "' on "
                    + typeName(receiver), node);
        }
        throw new EvalError("Unsupported call target: " + callee.getNodeType(), node);
    }

    private Object callUserFunction(
            UserFunction function,
            List<Object> positional,
            Map<String, Object> keyword,
            ASTNode site) {
        if (++callDepth > MAX_CALL_DEPTH) {
            callDepth--;
            throw new EvalError("Maximum call depth exceeded", site);
        }
        try {
            List<Parameter> parameters = function.definition.getParameters();
            Map<String, Object> locals = new LinkedHashMap<>();
            int required = parameters.size() - function.defaults.size();
            for (int index = 0; index < parameters.size(); index++) {
                Parameter parameter = parameters.get(index);
                if (index < positional.size()) {
                    locals.put(parameter.getName(), positional.get(index));
                } else if (keyword.containsKey(parameter.getName())) {
                    locals.put(parameter.getName(), keyword.remove(parameter.getName()));
                } else if (index >= required) {
                    locals.put(parameter.getName(),
                            function.defaults.get(index - required));
                } else {
                    throw new EvalError("Missing argument '" + parameter.getName()
                            + "' for " + function.definition.getName() + "()", site);
                }
            }
            if (positional.size() > parameters.size() || !keyword.isEmpty()) {
                throw new EvalError("Too many arguments for "
                        + function.definition.getName() + "()", site);
            }
            frames.push(new Frame(locals));
            try {
                Signal signal = execBlock(function.definition.getBody());
                return signal.kind() == Signal.Kind.RETURN ? signal.value() : null;
            } finally {
                frames.pop();
            }
        } finally {
            callDepth--;
        }
    }

    private Object builtinCall(
            String name,
            List<Object> positional,
            Map<String, Object> keyword,
            ASTNode site) {
        switch (name) {
            case "len": {
                Object value = one(positional, keyword, name, site);
                if (value instanceof String) {
                    return BigInteger.valueOf(((String) value).length());
                }
                if (value instanceof List) {
                    return BigInteger.valueOf(((List<?>) value).size());
                }
                if (value instanceof Map) {
                    return BigInteger.valueOf(((Map<?, ?>) value).size());
                }
                if (value instanceof Set) {
                    return BigInteger.valueOf(((Set<?>) value).size());
                }
                throw new EvalError("len() unsupported on " + typeName(value), site);
            }
            case "str":
                return pyStr(one(positional, keyword, name, site));
            case "int": {
                Object value = one(positional, keyword, name, site);
                if (value instanceof BigInteger) {
                    return value;
                }
                if (value instanceof Double) {
                    return BigInteger.valueOf((long) Math.floor((Double) value));
                }
                if (value instanceof Boolean) {
                    return ((Boolean) value) ? BigInteger.ONE : BigInteger.ZERO;
                }
                if (value instanceof String) {
                    try {
                        return new BigInteger(((String) value).trim());
                    } catch (NumberFormatException bad) {
                        throw new EvalError("int() cannot parse " + pyRepr(value), site);
                    }
                }
                throw new EvalError("int() unsupported on " + typeName(value), site);
            }
            case "float": {
                Object value = one(positional, keyword, name, site);
                if (value instanceof Double) {
                    return value;
                }
                if (value instanceof BigInteger) {
                    return ((BigInteger) value).doubleValue();
                }
                if (value instanceof String) {
                    try {
                        return Double.parseDouble(((String) value).trim());
                    } catch (NumberFormatException bad) {
                        throw new EvalError("float() cannot parse " + pyRepr(value), site);
                    }
                }
                throw new EvalError("float() unsupported on " + typeName(value), site);
            }
            case "bool":
                return truth(one(positional, keyword, name, site));
            case "list": {
                if (positional.isEmpty() && keyword.isEmpty()) {
                    return new ArrayList<>();
                }
                return new ArrayList<>(iterate(one(positional, keyword, name, site), site));
            }
            case "dict": {
                Map<Object, Object> map = new LinkedHashMap<>();
                map.putAll(keyword);
                return map;
            }
            case "tuple": {
                if (positional.isEmpty() && keyword.isEmpty()) {
                    return new ArrayList<>();
                }
                return new ArrayList<>(iterate(one(positional, keyword, name, site), site));
            }
            case "range": {
                BigInteger start = BigInteger.ZERO;
                BigInteger stop;
                BigInteger stepValue = BigInteger.ONE;
                if (positional.size() == 1) {
                    stop = asInt(positional.get(0), site);
                } else if (positional.size() == 2 || positional.size() == 3) {
                    start = asInt(positional.get(0), site);
                    stop = asInt(positional.get(1), site);
                    if (positional.size() == 3) {
                        stepValue = asInt(positional.get(2), site);
                    }
                } else {
                    throw new EvalError("range() expects 1-3 arguments", site);
                }
                if (stepValue.signum() == 0) {
                    throw new EvalError("range() step cannot be zero", site);
                }
                List<Object> values = new ArrayList<>();
                for (BigInteger current = start;
                        stepValue.signum() > 0
                                ? current.compareTo(stop) < 0
                                : current.compareTo(stop) > 0;
                        current = current.add(stepValue)) {
                    values.add(current);
                    if (BigInteger.valueOf(values.size()).compareTo(MAX_RANGE) > 0) {
                        throw new EvalError("range() too large for generation", site);
                    }
                }
                return values;
            }
            case "enumerate": {
                List<Object> source = iterate(one(positional, keyword, name, site), site);
                List<Object> pairs = new ArrayList<>();
                for (int index = 0; index < source.size(); index++) {
                    List<Object> pair = new ArrayList<>();
                    pair.add(BigInteger.valueOf(index));
                    pair.add(source.get(index));
                    pairs.add(pair);
                }
                return pairs;
            }
            case "sorted": {
                List<Object> copy = new ArrayList<>(
                        iterate(one(positional, keyword, name, site), site));
                copy.sort((a, b) -> naturalCompare(a, b, site));
                return copy;
            }
            case "sum": {
                Object total = BigInteger.ZERO;
                for (Object item : iterate(one(positional, keyword, name, site), site)) {
                    total = binary("+", total, item, site);
                }
                return total;
            }
            case "min":
            case "max": {
                List<Object> values = positional.size() > 1
                        ? positional
                        : iterate(one(positional, keyword, name, site), site);
                if (values.isEmpty()) {
                    throw new EvalError(name + "() of an empty sequence", site);
                }
                Object best = values.get(0);
                for (Object item : values.subList(1, values.size())) {
                    int order = naturalCompare(item, best, site);
                    if (("min".equals(name) && order < 0)
                            || ("max".equals(name) && order > 0)) {
                        best = item;
                    }
                }
                return best;
            }
            case "abs": {
                Object value = one(positional, keyword, name, site);
                if (value instanceof BigInteger) {
                    return ((BigInteger) value).abs();
                }
                if (value instanceof Double) {
                    return Math.abs((Double) value);
                }
                throw new EvalError("abs() unsupported on " + typeName(value), site);
            }
            case "round": {
                Object value = positional.isEmpty() ? null : positional.get(0);
                if (value instanceof BigInteger) {
                    return value;
                }
                if (value instanceof Double) {
                    if (positional.size() == 2) {
                        int digits = asInt(positional.get(1), site).intValueExact();
                        double factor = Math.pow(10, digits);
                        return Math.round((Double) value * factor) / factor;
                    }
                    return BigInteger.valueOf(Math.round((Double) value));
                }
                throw new EvalError("round() unsupported on " + typeName(value), site);
            }
            default:
                return SKIP;
        }
    }

    private Object methodCall(
            Object receiver,
            String method,
            List<Object> positional,
            Map<String, Object> keyword,
            ASTNode site) {
        if (receiver instanceof String) {
            String text = (String) receiver;
            switch (method) {
                case "upper":
                    return text.toUpperCase(java.util.Locale.ROOT);
                case "lower":
                    return text.toLowerCase(java.util.Locale.ROOT);
                case "strip":
                    return text.trim();
                case "title": {
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
                default:
                    return SKIP;
            }
        }
        if (receiver instanceof List) {
            List<Object> list = asObjectList(receiver);
            switch (method) {
                case "append":
                    list.add(one(positional, keyword, "append", site));
                    return null;
                case "extend":
                    list.addAll(iterate(one(positional, keyword, "extend", site), site));
                    return null;
                case "pop":
                    if (list.isEmpty()) {
                        throw new EvalError("pop from empty list", site);
                    }
                    return positional.isEmpty()
                            ? list.remove(list.size() - 1)
                            : list.remove(listIndex(list, positional.get(0), site));
                default:
                    return SKIP;
            }
        }
        if (receiver instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) receiver;
            switch (method) {
                case "get":
                    if (positional.isEmpty()) {
                        throw new EvalError("dict.get() expects a key", site);
                    }
                    if (map.containsKey(positional.get(0))) {
                        return map.get(positional.get(0));
                    }
                    return positional.size() > 1 ? positional.get(1) : null;
                case "keys":
                    return new ArrayList<Object>(map.keySet());
                case "values":
                    return new ArrayList<Object>(map.values());
                case "items": {
                    List<Object> items = new ArrayList<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        List<Object> pair = new ArrayList<>();
                        pair.add(entry.getKey());
                        pair.add(entry.getValue());
                        items.add(pair);
                    }
                    return items;
                }
                default:
                    return SKIP;
            }
        }
        return SKIP;
    }

    private Object one(
            List<Object> positional,
            Map<String, Object> keyword,
            String name,
            ASTNode site) {
        if (positional.size() != 1 || !keyword.isEmpty()) {
            throw new EvalError(name + "() expects exactly one argument", site);
        }
        return positional.get(0);
    }

    // ==================== arithmetic & helpers ====================

    private Object binary(String operator, Object left, Object right, ASTNode site) {
        switch (operator) {
            case "+":
                if (left instanceof String && right instanceof String) {
                    return (String) left + right;
                }
                if (left instanceof List && right instanceof List) {
                    List<Object> joined = new ArrayList<>(asObjectList(left));
                    joined.addAll((List<?>) right);
                    return joined;
                }
                return arithmetic(operator, left, right, site);
            case "-":
            case "%":
            case "//":
            case "**":
                return arithmetic(operator, left, right, site);
            case "*":
                if (left instanceof String && right instanceof BigInteger) {
                    return repeat((String) left, (BigInteger) right, site);
                }
                if (left instanceof BigInteger && right instanceof String) {
                    return repeat((String) right, (BigInteger) left, site);
                }
                if (left instanceof List && right instanceof BigInteger) {
                    return repeatList(asObjectList(left), (BigInteger) right, site);
                }
                if (left instanceof BigInteger && right instanceof List) {
                    return repeatList(asObjectList(right), (BigInteger) left, site);
                }
                return arithmetic(operator, left, right, site);
            case "/": {
                double denominator = toDouble(right, site);
                if (denominator == 0.0) {
                    throw new EvalError("division by zero", site);
                }
                return toDouble(left, site) / denominator;
            }
            default:
                throw new EvalError("Unsupported operator '" + operator + "'", site);
        }
    }

    private Object arithmetic(String operator, Object left, Object right, ASTNode site) {
        boolean bothInt = left instanceof BigInteger && right instanceof BigInteger;
        if (bothInt) {
            BigInteger a = (BigInteger) left;
            BigInteger b = (BigInteger) right;
            switch (operator) {
                case "+":
                    return a.add(b);
                case "-":
                    return a.subtract(b);
                case "*":
                    return a.multiply(b);
                case "//":
                    if (b.signum() == 0) {
                        throw new EvalError("integer division by zero", site);
                    }
                    BigInteger[] division = a.divideAndRemainder(b);
                    if (division[1].signum() != 0
                            && division[1].signum() != b.signum()) {
                        return division[0].subtract(BigInteger.ONE);
                    }
                    return division[0];
                case "%":
                    if (b.signum() == 0) {
                        throw new EvalError("modulo by zero", site);
                    }
                    BigInteger remainder = a.mod(b.abs());
                    return b.signum() < 0 && remainder.signum() != 0
                            ? remainder.subtract(b.abs())
                            : remainder;
                case "**":
                    if (b.signum() < 0) {
                        return Math.pow(a.doubleValue(), b.doubleValue());
                    }
                    if (b.compareTo(BigInteger.valueOf(1_000)) > 0) {
                        throw new EvalError("exponent too large for generation", site);
                    }
                    return a.pow(b.intValueExact());
                default:
                    throw new EvalError("Unsupported operator '" + operator + "'", site);
            }
        }
        double a = toDouble(left, site);
        double b = toDouble(right, site);
        switch (operator) {
            case "+":
                return a + b;
            case "-":
                return a - b;
            case "*":
                return a * b;
            case "//":
                if (b == 0.0) {
                    throw new EvalError("division by zero", site);
                }
                return Math.floor(a / b);
            case "%": {
                if (b == 0.0) {
                    throw new EvalError("modulo by zero", site);
                }
                double result = a % b;
                return result != 0.0 && Math.signum(result) != Math.signum(b)
                        ? result + b
                        : result;
            }
            case "**":
                return Math.pow(a, b);
            default:
                throw new EvalError("Unsupported operator '" + operator + "'", site);
        }
    }

    private String repeat(String text, BigInteger count, ASTNode site) {
        int times = clampRepeat(count, site);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) {
            builder.append(text);
        }
        return builder.toString();
    }

    private List<Object> repeatList(List<Object> list, BigInteger count, ASTNode site) {
        int times = clampRepeat(count, site);
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            result.addAll(list);
        }
        return result;
    }

    private int clampRepeat(BigInteger count, ASTNode site) {
        if (count.signum() < 0) {
            return 0;
        }
        if (count.compareTo(BigInteger.valueOf(10_000)) > 0) {
            throw new EvalError("repetition too large for generation", site);
        }
        return count.intValueExact();
    }

    private Object negate(Object operand, ASTNode site) {
        if (operand instanceof BigInteger) {
            return ((BigInteger) operand).negate();
        }
        if (operand instanceof Double) {
            return -((Double) operand);
        }
        if (operand instanceof Boolean) {
            return ((Boolean) operand) ? BigInteger.valueOf(-1) : BigInteger.ZERO;
        }
        throw new EvalError("Unsupported unary '-' on " + typeName(operand), site);
    }

    private void requireNumber(Object value, ASTNode site) {
        if (!(value instanceof BigInteger || value instanceof Double
                || value instanceof Boolean)) {
            throw new EvalError("Unsupported unary '+' on " + typeName(value), site);
        }
    }

    private int numberCompare(Object left, Object right, ASTNode site) {
        if (left instanceof BigInteger && right instanceof BigInteger) {
            return ((BigInteger) left).compareTo((BigInteger) right);
        }
        if (left instanceof String && right instanceof String) {
            return ((String) left).compareTo((String) right);
        }
        return Double.compare(toDouble(left, site), toDouble(right, site));
    }

    private int naturalCompare(Object left, Object right, ASTNode site) {
        return numberCompare(left, right, site);
    }

    private double toDouble(Object value, ASTNode site) {
        if (value instanceof BigInteger) {
            return ((BigInteger) value).doubleValue();
        }
        if (value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1.0 : 0.0;
        }
        throw new EvalError("A number is required, got " + typeName(value), site);
    }

    private BigInteger asInt(Object value, ASTNode site) {
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? BigInteger.ONE : BigInteger.ZERO;
        }
        throw new EvalError("An integer is required, got " + typeName(value), site);
    }

    private int listIndex(List<Object> list, Object key, ASTNode site) {
        return intIndex(key, list.size(), site);
    }

    private int intIndex(Object key, int size, ASTNode site) {
        BigInteger raw = asInt(key, site);
        int index = raw.intValueExact();
        if (index < 0) {
            index += size;
        }
        if (index < 0 || index >= size) {
            throw new EvalError("IndexError: index " + raw + " out of range", site);
        }
        return index;
    }

    private List<Object> iterate(Object value, ASTNode site) {
        if (value instanceof List) {
            return new ArrayList<>(asObjectList(value));
        }
        if (value instanceof Set) {
            return new ArrayList<>((Set<?>) value);
        }
        if (value instanceof Map) {
            return new ArrayList<>(((Map<?, ?>) value).keySet());
        }
        if (value instanceof String) {
            List<Object> chars = new ArrayList<>();
            for (char c : ((String) value).toCharArray()) {
                chars.add(String.valueOf(c));
            }
            return chars;
        }
        throw new EvalError("Not iterable: " + typeName(value), site);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asObjectList(Object value) {
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static void mapPut(Object map, Object key, Object value) {
        ((Map<Object, Object>) map).put(key, value);
    }

    private Object normalize(Object literal) {
        if (literal instanceof Integer) {
            return BigInteger.valueOf((Integer) literal);
        }
        if (literal instanceof Long) {
            return BigInteger.valueOf((Long) literal);
        }
        return literal;
    }

    private void step(ASTNode site) {
        if (++steps > MAX_STEPS) {
            throw new EvalError("Generation evaluation budget exceeded", site);
        }
    }

    // ==================== shared value semantics ====================

    /** Python truthiness over the plain-Java value model. */
    public static boolean truth(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof BigInteger) {
            return ((BigInteger) value).signum() != 0;
        }
        if (value instanceof Double) {
            return (Double) value != 0.0;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        if (value instanceof List) {
            return !((List<?>) value).isEmpty();
        }
        if (value instanceof Map) {
            return !((Map<?, ?>) value).isEmpty();
        }
        if (value instanceof Set) {
            return !((Set<?>) value).isEmpty();
        }
        return true;
    }

    /** Value equality with Python-flavored number semantics. */
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

    /** Python {@code str(value)}: None → "None", True/False, ints undotted. */
    public static String pyStr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "True" : "False";
        }
        if (value instanceof String) {
            return (String) value;
        }
        return pyRepr(value);
    }

    /** Python {@code repr(value)}: strings quoted, containers recursive. */
    public static String pyRepr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "True" : "False";
        }
        if (value instanceof String) {
            return "'" + ((String) value).replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        if (value instanceof BigInteger) {
            return value.toString();
        }
        if (value instanceof Double) {
            return Double.toString((Double) value);
        }
        if (value instanceof List) {
            StringBuilder builder = new StringBuilder("[");
            List<?> list = (List<?>) value;
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    builder.append(", ");
                }
                builder.append(pyRepr(list.get(index)));
            }
            return builder.append("]").toString();
        }
        if (value instanceof Set) {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Object item : (Set<?>) value) {
                if (!first) {
                    builder.append(", ");
                }
                builder.append(pyRepr(item));
                first = false;
            }
            return builder.append("}").toString();
        }
        if (value instanceof Map) {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    builder.append(", ");
                }
                builder.append(pyRepr(entry.getKey()))
                        .append(": ")
                        .append(pyRepr(entry.getValue()));
                first = false;
            }
            return builder.append("}").toString();
        }
        return value.toString();
    }

    /** Short Python-like type name for messages. */
    public static String typeName(Object value) {
        if (value == null) {
            return "None";
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
        if (value instanceof Set) {
            return "set";
        }
        return value.getClass().getSimpleName();
    }
}
