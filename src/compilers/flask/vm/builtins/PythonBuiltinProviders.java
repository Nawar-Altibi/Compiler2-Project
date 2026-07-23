package compilers.flask.vm.builtins;

import compilers.flask.runtime.RuntimeSymbolManifest;
import compilers.flask.runtime.RuntimeSymbolSpec;
import compilers.flask.vm.Namespace;
import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.RuntimeProviderRegistry;
import compilers.flask.vm.VmCallContext;
import compilers.flask.vm.VmRuntimeException;
import compilers.flask.vm.values.PyAttributeProvider;
import compilers.flask.vm.values.PyBaseException;
import compilers.flask.vm.values.PyBool;
import compilers.flask.vm.values.PyCallable;
import compilers.flask.vm.values.PyClass;
import compilers.flask.vm.values.PyDict;
import compilers.flask.vm.values.PyFloat;
import compilers.flask.vm.values.PyFunction;
import compilers.flask.vm.values.PyInstance;
import compilers.flask.vm.values.PyInt;
import compilers.flask.vm.values.PyIterator;
import compilers.flask.vm.values.PyList;
import compilers.flask.vm.values.PyNativeClass;
import compilers.flask.vm.values.PyNativeFunction;
import compilers.flask.vm.values.PyNativeObject;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyRange;
import compilers.flask.vm.values.PySet;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyTuple;
import compilers.flask.vm.values.PyType;
import compilers.flask.vm.values.PyValue;
import compilers.flask.vm.flask.PyReadableFile;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Complete Java-only provider registry for the mandatory builtin manifest.
 * Host reflection and filesystem access are intentionally absent.
 */
public final class PythonBuiltinProviders {
    private PythonBuiltinProviders() {
    }

    /** Returns a fresh namespace containing every declared builtin name. */
    public static Namespace create() {
        return createRegistry().moduleNamespace(RuntimeSymbolManifest.BUILTINS_MODULE);
    }

    /** Builds the concrete provider-ID registry for the builtin manifest. */
    public static RuntimeProviderRegistry createRegistry() {
        LinkedHashMap<String, PyNativeClass> types = buildTypes();
        RuntimeProviderRegistry.Builder result = RuntimeProviderRegistry.builder();
        for (Map.Entry<String, RuntimeSymbolSpec> declaration
                : RuntimeSymbolManifest.builtins().entrySet()) {
            String name = declaration.getKey();
            PyValue provider = types.get(name);
            if (provider == null) {
                provider = functionProvider(name, types);
            }
            if (provider == null) {
                throw new IllegalStateException(
                        "No builtin provider installed for " + name);
            }
            result.register(declaration.getValue(), provider);
        }
        RuntimeProviderRegistry registry = result.build();
        // Materialization is the exact parity gate, including provider IDs.
        registry.moduleProviders(RuntimeSymbolManifest.BUILTINS_MODULE);
        return registry;
    }

    private static LinkedHashMap<String, PyNativeClass> buildTypes() {
        LinkedHashMap<String, PyNativeClass> types = new LinkedHashMap<>();
        PyNativeClass object = addType(types, "object", Collections.emptyList(),
                (context, positional, keywords) -> {
                    exact("object", positional, 0);
                    noKeywords("object", keywords);
                    return new PyNativeObject();
                });

        addType(types, "type", List.of(object),
                (context, positional, keywords) -> {
                    exact("type", positional, 1);
                    noKeywords("type", keywords);
                    return typeOf(RuntimeOps.resolveDynamic(
                            positional.get(0), context), types);
                });
        addType(types, "str", List.of(object),
                (context, positional, keywords) -> constructString(positional, keywords));
        PyNativeClass integer = addType(types, "int", List.of(object),
                (context, positional, keywords) -> constructInteger(positional, keywords));
        addType(types, "float", List.of(object),
                (context, positional, keywords) -> constructFloat(positional, keywords));
        addType(types, "bool", List.of(integer),
                (context, positional, keywords) -> constructBool(positional, keywords));
        addType(types, "list", List.of(object),
                (context, positional, keywords) -> constructList(positional, keywords));
        addType(types, "dict", List.of(object),
                (context, positional, keywords) -> constructDict(positional, keywords));
        addType(types, "set", List.of(object),
                (context, positional, keywords) -> constructSet(positional, keywords));
        addType(types, "tuple", List.of(object),
                (context, positional, keywords) -> constructTuple(positional, keywords));

        PyNativeClass baseException = exceptionType(types, "BaseException", object);
        PyNativeClass exception = exceptionType(types, "Exception", baseException);
        PyNativeClass arithmetic = exceptionType(types, "ArithmeticError", exception);
        exceptionType(types, "AssertionError", exception);
        exceptionType(types, "AttributeError", exception);
        exceptionType(types, "ImportError", exception);
        PyNativeClass lookup = exceptionType(types, "LookupError", exception);
        exceptionType(types, "IndexError", lookup);
        exceptionType(types, "KeyError", lookup);
        PyNativeClass nameError = exceptionType(types, "NameError", exception);
        exceptionType(types, "UnboundLocalError", nameError);
        PyNativeClass osError = exceptionType(types, "OSError", exception);
        types.put("IOError", osError); // Python 3 alias, deliberately identical.
        exceptionType(types, "FileNotFoundError", osError);
        exceptionType(types, "PermissionError", osError);
        exceptionType(types, "RuntimeError", exception);
        exceptionType(types, "StopIteration", exception);
        exceptionType(types, "TypeError", exception);
        exceptionType(types, "ValueError", exception);
        exceptionType(types, "ZeroDivisionError", arithmetic);
        return types;
    }

    private static PyNativeClass addType(
            Map<String, PyNativeClass> types,
            String name,
            List<PyNativeClass> bases,
            PyNativeClass.Constructor constructor) {
        PyNativeClass value = new PyNativeClass(name, bases, constructor);
        if (types.put(name, value) != null) {
            throw new IllegalStateException("Duplicate builtin type " + name);
        }
        return value;
    }

    private static PyNativeClass exceptionType(
            Map<String, PyNativeClass> types,
            String name,
            PyNativeClass base) {
        return addType(types, name, List.of(base),
                (context, positional, keywords) -> {
                    between(name, positional, 0, 1);
                    noKeywords(name, keywords);
                    String message = positional.isEmpty()
                            ? "" : positional.get(0).str();
                    return new PyBaseException(name, message);
                });
    }

    private static PyValue functionProvider(
            String name, LinkedHashMap<String, PyNativeClass> types) {
        switch (name) {
            case "print": return nativeFunction(name, PythonBuiltinProviders::print);
            case "len": return nativeFunction(name, PythonBuiltinProviders::length);
            case "range": return nativeFunction(name, PythonBuiltinProviders::range);
            case "enumerate": return nativeFunction(name, PythonBuiltinProviders::enumerate);
            case "zip": return nativeFunction(name, PythonBuiltinProviders::zip);
            case "map": return nativeFunction(name, PythonBuiltinProviders::map);
            case "filter": return nativeFunction(name, PythonBuiltinProviders::filter);
            case "sorted": return nativeFunction(name, PythonBuiltinProviders::sorted);
            case "reversed": return nativeFunction(name, PythonBuiltinProviders::reversed);
            case "sum": return nativeFunction(name, PythonBuiltinProviders::sum);
            case "min": return nativeFunction(name,
                    (context, positional, keywords) -> minMax(
                            context, positional, keywords, true));
            case "max": return nativeFunction(name,
                    (context, positional, keywords) -> minMax(
                            context, positional, keywords, false));
            case "abs": return nativeFunction(name, PythonBuiltinProviders::absolute);
            case "round": return nativeFunction(name, PythonBuiltinProviders::round);
            case "open": return nativeFunction(name, PythonBuiltinProviders::open);
            case "isinstance": return nativeFunction(name,
                    (context, positional, keywords) -> instanceCheck(
                            context, positional, keywords, types));
            case "issubclass": return nativeFunction(name,
                    (context, positional, keywords) -> subclassCheck(
                            positional, keywords, types));
            case "hasattr": return nativeFunction(name, PythonBuiltinProviders::hasAttribute);
            case "getattr": return nativeFunction(name, PythonBuiltinProviders::getAttribute);
            case "setattr": return nativeFunction(name, PythonBuiltinProviders::setAttribute);
            case "delattr": return nativeFunction(name, PythonBuiltinProviders::deleteAttribute);
            case "repr": return nativeFunction(name, PythonBuiltinProviders::representation);
            case "format": return nativeFunction(name, PythonBuiltinProviders::format);
            case "any": return nativeFunction(name,
                    (context, positional, keywords) -> anyAll(
                            context, positional, keywords, true));
            case "all": return nativeFunction(name,
                    (context, positional, keywords) -> anyAll(
                            context, positional, keywords, false));
            case "next": return nativeFunction(name, PythonBuiltinProviders::next);
            case "iter": return nativeFunction(name, PythonBuiltinProviders::iter);
            case "id": return nativeFunction(name, PythonBuiltinProviders::identity);
            case "ord": return nativeFunction(name, PythonBuiltinProviders::ordinal);
            case "chr": return nativeFunction(name, PythonBuiltinProviders::character);
            case "callable": return nativeFunction(name, PythonBuiltinProviders::callable);
            case "hash": return nativeFunction(name, PythonBuiltinProviders::hash);
            case "globals": return nativeFunction(name, PythonBuiltinProviders::globals);
            case "locals": return nativeFunction(name, PythonBuiltinProviders::locals);
            case "pow": return nativeFunction(name, PythonBuiltinProviders::power);
            case "divmod": return nativeFunction(name, PythonBuiltinProviders::divmod);
            default: return null;
        }
    }

    private static PyNativeFunction nativeFunction(
            String name, PyNativeFunction.Body body) {
        return new PyNativeFunction(name, body);
    }

    private static PyValue print(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        Set<String> allowed = Set.of("sep", "end");
        rejectUnknownKeywords("print", keywords, allowed);
        String separator = stringKeyword("print", keywords, "sep", " ");
        String ending = stringKeyword("print", keywords, "end", "\n");
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < positional.size(); index++) {
            if (index > 0) output.append(separator);
            output.append(RuntimeOps.resolveDynamic(
                    positional.get(index), context).str());
        }
        context.writeStdout(output.append(ending).toString());
        return PyNone.INSTANCE;
    }

    private static PyValue length(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("len", positional, 1);
        noKeywords("len", keywords);
        PyValue value = RuntimeOps.resolveDynamic(positional.get(0), context);
        if (value instanceof PyString) {
            return new PyInt(BigInteger.valueOf(((PyString) value).codePointLength()));
        }
        if (value instanceof PyList) return PyInt.valueOf(((PyList) value).size());
        if (value instanceof PyTuple) return PyInt.valueOf(((PyTuple) value).size());
        if (value instanceof PySet) return PyInt.valueOf(((PySet) value).size());
        if (value instanceof PyDict) return PyInt.valueOf(((PyDict) value).size());
        if (value instanceof PyRange) return new PyInt(((PyRange) value).getLength());
        throw typeError("object of type '" + value.getTypeName() + "' has no len()");
    }

    private static PyValue range(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        between("range", positional, 1, 3);
        noKeywords("range", keywords);
        BigInteger start;
        BigInteger stop;
        BigInteger step = BigInteger.ONE;
        if (positional.size() == 1) {
            start = BigInteger.ZERO;
            stop = asInteger(positional.get(0), "range");
        } else {
            start = asInteger(positional.get(0), "range");
            stop = asInteger(positional.get(1), "range");
            if (positional.size() == 3) {
                step = asInteger(positional.get(2), "range");
            }
        }
        return new PyRange(start, stop, step);
    }

    private static PyValue enumerate(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        between("enumerate", positional, 1, 2);
        noKeywords("enumerate", keywords);
        final BigInteger[] index = { positional.size() == 2
                ? asInteger(positional.get(1), "enumerate") : BigInteger.ZERO
        };
        final PyIterator iterator = iterator(positional.get(0));
        return PyIterator.fromSource(() -> {
            Optional<PyValue> next = iterator.tryNext();
            if (!next.isPresent()) return Optional.empty();
            PyValue result = new PyTuple(List.of(new PyInt(index[0]), next.get()));
            index[0] = index[0].add(BigInteger.ONE);
            return Optional.of(result);
        });
    }

    private static PyValue zip(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        noKeywords("zip", keywords);
        final List<PyIterator> iterators = new ArrayList<>();
        for (PyValue value : positional) iterators.add(iterator(value));
        return PyIterator.fromSource(() -> {
            if (iterators.isEmpty()) return Optional.empty();
            List<PyValue> row = new ArrayList<>(iterators.size());
            for (PyIterator iterator : iterators) {
                Optional<PyValue> next = iterator.tryNext();
                if (!next.isPresent()) return Optional.empty();
                row.add(next.get());
            }
            return Optional.<PyValue>of(new PyTuple(row));
        });
    }

    private static PyValue map(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        atLeast("map", positional, 2);
        noKeywords("map", keywords);
        final PyValue callable = requireCallable("map", positional.get(0));
        final List<PyIterator> iterators = new ArrayList<>();
        for (int index = 1; index < positional.size(); index++) {
            iterators.add(iterator(positional.get(index)));
        }
        return PyIterator.fromSource(() -> {
            List<PyValue> arguments = new ArrayList<>(iterators.size());
            for (PyIterator iterator : iterators) {
                Optional<PyValue> next = iterator.tryNext();
                if (!next.isPresent()) return Optional.empty();
                arguments.add(next.get());
            }
            return Optional.of(context.invoke(
                    callable, arguments, Collections.emptyMap()));
        });
    }

    private static PyValue filter(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("filter", positional, 2);
        noKeywords("filter", keywords);
        final PyValue predicate = positional.get(0);
        if (predicate != PyNone.INSTANCE) requireCallable("filter", predicate);
        final PyIterator iterator = iterator(positional.get(1));
        return PyIterator.fromSource(() -> {
            Optional<PyValue> next;
            while ((next = iterator.tryNext()).isPresent()) {
                PyValue item = next.get();
                PyValue decision = predicate == PyNone.INSTANCE
                        ? item
                        : context.invoke(predicate, List.of(item), Collections.emptyMap());
                if (RuntimeOps.isTruthy(decision)) return Optional.of(item);
            }
            return Optional.empty();
        });
    }

    private static PyValue sorted(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("sorted", positional, 1);
        rejectUnknownKeywords("sorted", keywords, Set.of("key", "reverse"));
        PyValue key = keywords.get("key");
        if (key != null && key != PyNone.INSTANCE) requireCallable("sorted", key);
        boolean reverse = keywords.containsKey("reverse")
                && RuntimeOps.isTruthy(keywords.get("reverse"));
        List<PyValue> values = collect(positional.get(0));
        List<DecoratedSortValue> decorated = new ArrayList<>(values.size());
        for (PyValue value : values) {
            PyValue sortKey = key == null || key == PyNone.INSTANCE
                    ? value
                    : context.invoke(key, List.of(value), Collections.emptyMap());
            decorated.add(new DecoratedSortValue(value, sortKey));
        }
        final int direction = reverse ? -1 : 1;
        decorated.sort((left, right) -> {
            if (RuntimeOps.compare("LT", left.key, right.key).getValue()) {
                return -direction;
            }
            if (RuntimeOps.compare("GT", left.key, right.key).getValue()) {
                return direction;
            }
            return 0;
        });
        List<PyValue> result = new ArrayList<>(decorated.size());
        for (DecoratedSortValue value : decorated) result.add(value.value);
        return new PyList(result);
    }

    private static final class DecoratedSortValue {
        private final PyValue value;
        private final PyValue key;

        private DecoratedSortValue(PyValue value, PyValue key) {
            this.value = value;
            this.key = key;
        }
    }

    private static PyValue reversed(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("reversed", positional, 1);
        noKeywords("reversed", keywords);
        List<PyValue> values = collect(positional.get(0));
        Collections.reverse(values);
        return new PyIterator(values);
    }

    private static PyValue sum(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        between("sum", positional, 1, 2);
        noKeywords("sum", keywords);
        PyValue total = positional.size() == 2 ? positional.get(1) : PyInt.ZERO;
        PyIterator iterator = iterator(positional.get(0));
        Optional<PyValue> next;
        while ((next = iterator.tryNext()).isPresent()) {
            total = RuntimeOps.binary("ADD", total, next.get());
        }
        return total;
    }

    private static PyValue minMax(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords,
            boolean minimum) {
        atLeast(minimum ? "min" : "max", positional, 1);
        String name = minimum ? "min" : "max";
        rejectUnknownKeywords(name, keywords, Set.of("key"));
        PyValue key = keywords.get("key");
        if (key != null && key != PyNone.INSTANCE) requireCallable(name, key);
        List<PyValue> values = positional.size() == 1
                ? collect(positional.get(0)) : new ArrayList<>(positional);
        if (values.isEmpty()) {
            throw RuntimeOps.error("ValueError", name + "() arg is an empty sequence");
        }
        PyValue best = values.get(0);
        PyValue bestKey = applyKey(context, key, best);
        for (int index = 1; index < values.size(); index++) {
            PyValue candidate = values.get(index);
            PyValue candidateKey = applyKey(context, key, candidate);
            String operation = minimum ? "LT" : "GT";
            if (RuntimeOps.compare(operation, candidateKey, bestKey).getValue()) {
                best = candidate;
                bestKey = candidateKey;
            }
        }
        return best;
    }

    private static PyValue applyKey(
            VmCallContext context, PyValue key, PyValue value) {
        return key == null || key == PyNone.INSTANCE
                ? value
                : context.invoke(key, List.of(value), Collections.emptyMap());
    }

    private static PyValue absolute(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("abs", positional, 1);
        noKeywords("abs", keywords);
        PyValue value = positional.get(0);
        if (value instanceof PyInt) {
            return new PyInt(((PyInt) value).getValue().abs());
        }
        if (value instanceof PyBool) {
            return ((PyBool) value).getValue() ? PyInt.ONE : PyInt.ZERO;
        }
        if (value instanceof PyFloat) {
            return new PyFloat(Math.abs(((PyFloat) value).getValue()));
        }
        throw typeError("bad operand type for abs(): '" + value.getTypeName() + "'");
    }

    private static PyValue round(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        between("round", positional, 1, 2);
        noKeywords("round", keywords);
        PyValue number = positional.get(0);
        if (number instanceof PyInt) return number;
        if (number instanceof PyBool) {
            return ((PyBool) number).getValue() ? PyInt.ONE : PyInt.ZERO;
        }
        if (!(number instanceof PyFloat)) {
            throw typeError("type '" + number.getTypeName() + "' doesn't define __round__");
        }
        double value = ((PyFloat) number).getValue();
        if (positional.size() == 1) {
            if (!Double.isFinite(value)) {
                throw RuntimeOps.error("ValueError", "cannot convert non-finite float to int");
            }
            return new PyInt(BigDecimal.valueOf(value)
                    .setScale(0, RoundingMode.HALF_EVEN).toBigInteger());
        }
        int digits = exactInt(asInteger(positional.get(1), "round"), "round");
        if (!Double.isFinite(value) || Math.abs((long) digits) > 1_000L) {
            return new PyFloat(value);
        }
        return new PyFloat(BigDecimal.valueOf(value)
                .setScale(digits, RoundingMode.HALF_EVEN).doubleValue());
    }

    private static PyValue open(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        between("open", positional, 1, 2);
        rejectUnknownKeywords("open", keywords, Set.of("mode"));
        if (!(positional.get(0) instanceof PyString)) {
            throw typeError("open() path must be str");
        }
        if (positional.size() == 2 && keywords.containsKey("mode")) {
            throw typeError("open() got multiple values for argument 'mode'");
        }
        PyValue modeValue = positional.size() == 2
                ? positional.get(1) : keywords.get("mode");
        if (modeValue != null && !(modeValue instanceof PyString)) {
            throw typeError("open() mode must be str");
        }
        String mode = modeValue == null
                ? "r" : ((PyString) modeValue).getValue();
        if (!("r".equals(mode) || "rt".equals(mode))) {
            throw RuntimeOps.error("PermissionError",
                    "open() supports read-only text mode in this VM");
        }
        String path = ((PyString) positional.get(0)).getValue();
        String content = context.getRuntimeServices()
                .getCapabilities().readText(path);
        return new PyReadableFile(path, content);
    }

    private static PyValue instanceCheck(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords,
            Map<String, PyNativeClass> types) {
        exact("isinstance", positional, 2);
        noKeywords("isinstance", keywords);
        return PyBool.valueOf(matchesInstance(
                RuntimeOps.resolveDynamic(positional.get(0), context),
                positional.get(1), types));
    }

    private static boolean matchesInstance(
            PyValue value, PyValue classSpec, Map<String, PyNativeClass> types) {
        if (classSpec instanceof PyTuple) {
            for (PyValue element : ((PyTuple) classSpec).getElements()) {
                if (matchesInstance(value, element, types)) return true;
            }
            return false;
        }
        if (!(classSpec instanceof PyType)) {
            throw typeError("isinstance() arg 2 must be a type or tuple of types");
        }
        if (value instanceof PyInstance) {
            return ((PyInstance) value).isInstanceOf((PyType) classSpec);
        }
        if (classSpec instanceof PyClass) {
            return false;
        }
        PyNativeClass expected = (PyNativeClass) classSpec;
        if (expected == types.get("object")) return true;
        PyNativeClass actual = nativeTypeOf(value, types);
        return actual != null
                ? actual.isSubclassOf(expected)
                : expected.getName().equals(value.getTypeName());
    }

    private static PyValue subclassCheck(
            List<PyValue> positional,
            Map<String, PyValue> keywords,
            Map<String, PyNativeClass> types) {
        exact("issubclass", positional, 2);
        noKeywords("issubclass", keywords);
        return PyBool.valueOf(matchesSubclass(
                positional.get(0), positional.get(1), types));
    }

    private static boolean matchesSubclass(
            PyValue candidate, PyValue classSpec, Map<String, PyNativeClass> types) {
        if (classSpec instanceof PyTuple) {
            for (PyValue element : ((PyTuple) classSpec).getElements()) {
                if (matchesSubclass(candidate, element, types)) return true;
            }
            return false;
        }
        if (!(candidate instanceof PyType)) {
            throw typeError("issubclass() arg 1 must be a class");
        }
        if (!(classSpec instanceof PyType)) {
            throw typeError("issubclass() arg 2 must be a class or tuple of classes");
        }
        return ((PyType) candidate).isSubclassOf((PyType) classSpec);
    }

    private static PyValue hasAttribute(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("hasattr", positional, 2);
        noKeywords("hasattr", keywords);
        String name = attributeName("hasattr", positional.get(1));
        try {
            RuntimeOps.loadAttribute(positional.get(0), name, context);
            return PyBool.TRUE;
        } catch (VmRuntimeException failure) {
            if ("AttributeError".equals(
                    failure.getExceptionValue().getExceptionTypeName())) {
                return PyBool.FALSE;
            }
            throw failure;
        }
    }

    private static PyValue getAttribute(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        between("getattr", positional, 2, 3);
        noKeywords("getattr", keywords);
        String name = attributeName("getattr", positional.get(1));
        try {
            return RuntimeOps.loadAttribute(positional.get(0), name, context);
        } catch (VmRuntimeException failure) {
            if (positional.size() == 3 && "AttributeError".equals(
                    failure.getExceptionValue().getExceptionTypeName())) {
                return positional.get(2);
            }
            throw failure;
        }
    }

    private static PyValue setAttribute(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("setattr", positional, 3);
        noKeywords("setattr", keywords);
        RuntimeOps.storeAttribute(
                positional.get(0), attributeName("setattr", positional.get(1)),
                positional.get(2), context);
        return PyNone.INSTANCE;
    }

    private static PyValue deleteAttribute(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("delattr", positional, 2);
        noKeywords("delattr", keywords);
        RuntimeOps.deleteAttribute(
                positional.get(0), attributeName("delattr", positional.get(1)),
                context);
        return PyNone.INSTANCE;
    }

    private static PyValue representation(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("repr", positional, 1);
        noKeywords("repr", keywords);
        return new PyString(RuntimeOps.resolveDynamic(
                positional.get(0), context).repr());
    }

    private static PyValue format(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        between("format", positional, 1, 2);
        noKeywords("format", keywords);
        if (positional.size() == 2) {
            if (!(positional.get(1) instanceof PyString)) {
                throw typeError("format() argument 2 must be str");
            }
            if (!((PyString) positional.get(1)).getValue().isEmpty()) {
                throw RuntimeOps.error(
                        "ValueError", "non-empty format specifications are not supported");
            }
        }
        return new PyString(RuntimeOps.resolveDynamic(
                positional.get(0), context).str());
    }

    private static PyValue anyAll(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords,
            boolean any) {
        String name = any ? "any" : "all";
        exact(name, positional, 1);
        noKeywords(name, keywords);
        PyIterator iterator = iterator(RuntimeOps.resolveDynamic(
                positional.get(0), context));
        Optional<PyValue> next;
        while ((next = iterator.tryNext()).isPresent()) {
            boolean truth = RuntimeOps.isTruthy(next.get(), context);
            if (any && truth) return PyBool.TRUE;
            if (!any && !truth) return PyBool.FALSE;
        }
        return any ? PyBool.FALSE : PyBool.TRUE;
    }

    private static PyValue next(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        between("next", positional, 1, 2);
        noKeywords("next", keywords);
        if (!(positional.get(0) instanceof PyIterator)) {
            throw typeError("'" + positional.get(0).getTypeName()
                    + "' object is not an iterator");
        }
        Optional<PyValue> value = ((PyIterator) positional.get(0)).tryNext();
        if (value.isPresent()) return value.get();
        if (positional.size() == 2) return positional.get(1);
        throw RuntimeOps.error("StopIteration", "");
    }

    private static PyValue iter(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("iter", positional, 1);
        noKeywords("iter", keywords);
        return iterator(RuntimeOps.resolveDynamic(positional.get(0), context));
    }

    private static PyValue identity(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("id", positional, 1);
        noKeywords("id", keywords);
        return new PyInt(BigInteger.valueOf(
                Integer.toUnsignedLong(System.identityHashCode(positional.get(0)))));
    }

    private static PyValue ordinal(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("ord", positional, 1);
        noKeywords("ord", keywords);
        if (!(positional.get(0) instanceof PyString)
                || ((PyString) positional.get(0)).codePointLength() != 1) {
            throw typeError("ord() expected a character, but string of length "
                    + (positional.get(0) instanceof PyString
                    ? ((PyString) positional.get(0)).codePointLength() : 0)
                    + " found");
        }
        return PyInt.valueOf(((PyString) positional.get(0))
                .getValue().codePointAt(0));
    }

    private static PyValue character(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("chr", positional, 1);
        noKeywords("chr", keywords);
        BigInteger value = asInteger(positional.get(0), "chr");
        if (value.signum() < 0
                || value.compareTo(BigInteger.valueOf(0x10ffffL)) > 0) {
            throw RuntimeOps.error("ValueError", "chr() arg not in range(0x110000)");
        }
        return new PyString(new String(Character.toChars(value.intValue())));
    }

    private static PyValue callable(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("callable", positional, 1);
        noKeywords("callable", keywords);
        return PyBool.valueOf(RuntimeOps.resolveDynamic(
                positional.get(0), context) instanceof PyCallable);
    }

    private static PyValue hash(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("hash", positional, 1);
        noKeywords("hash", keywords);
        PyValue value = RuntimeOps.resolveDynamic(positional.get(0), context);
        if (value instanceof PyClass || value instanceof PyInstance
                || value instanceof PyNativeClass || value instanceof PyNativeObject
                || value instanceof PyCallable || value instanceof PyRange) {
            return PyInt.valueOf(System.identityHashCode(value));
        }
        return PyInt.valueOf(RuntimeOps.hash(value));
    }

    private static PyValue globals(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("globals", positional, 0);
        noKeywords("globals", keywords);
        return namespaceDict(context.getCurrentGlobals());
    }

    private static PyValue locals(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("locals", positional, 0);
        noKeywords("locals", keywords);
        return namespaceDict(context.getCurrentLocals());
    }

    private static PyDict namespaceDict(Namespace namespace) {
        PyDict result = new PyDict();
        for (Map.Entry<String, PyValue> entry : namespace.snapshot().entrySet()) {
            result.put(new PyString(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static PyValue power(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        between("pow", positional, 2, 3);
        noKeywords("pow", keywords);
        if (positional.size() == 2) {
            return RuntimeOps.binary("POWER", positional.get(0), positional.get(1));
        }
        BigInteger base = asInteger(positional.get(0), "pow");
        BigInteger exponent = asInteger(positional.get(1), "pow");
        BigInteger modulus = asInteger(positional.get(2), "pow");
        if (exponent.signum() < 0) {
            throw RuntimeOps.error(
                    "ValueError", "pow() 2nd argument cannot be negative when 3rd specified");
        }
        if (modulus.signum() == 0) {
            throw RuntimeOps.error("ValueError", "pow() 3rd argument cannot be 0");
        }
        BigInteger positiveModulus = modulus.abs();
        BigInteger result = base.mod(positiveModulus)
                .modPow(exponent, positiveModulus);
        if (modulus.signum() < 0 && result.signum() != 0) {
            result = result.subtract(positiveModulus);
        }
        return new PyInt(result);
    }

    private static PyValue divmod(
            VmCallContext context,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        exact("divmod", positional, 2);
        noKeywords("divmod", keywords);
        PyValue quotient = RuntimeOps.binary(
                "FLOOR_DIVIDE", positional.get(0), positional.get(1));
        PyValue remainder = RuntimeOps.binary(
                "MODULO", positional.get(0), positional.get(1));
        return new PyTuple(List.of(quotient, remainder));
    }

    private static PyValue constructString(
            List<PyValue> positional, Map<String, PyValue> keywords) {
        between("str", positional, 0, 1);
        noKeywords("str", keywords);
        return positional.isEmpty() ? PyString.EMPTY : new PyString(positional.get(0).str());
    }

    private static PyValue constructInteger(
            List<PyValue> positional, Map<String, PyValue> keywords) {
        between("int", positional, 0, 2);
        noKeywords("int", keywords);
        if (positional.isEmpty()) return PyInt.ZERO;
        PyValue value = positional.get(0);
        int base = positional.size() == 2
                ? exactInt(asInteger(positional.get(1), "int"), "int") : 10;
        if (base != 0 && (base < 2 || base > 36)) {
            throw RuntimeOps.error("ValueError", "int() base must be >= 2 and <= 36, or 0");
        }
        if (value instanceof PyInt) return value;
        if (value instanceof PyBool) {
            return ((PyBool) value).getValue() ? PyInt.ONE : PyInt.ZERO;
        }
        if (value instanceof PyFloat) {
            double number = ((PyFloat) value).getValue();
            if (!Double.isFinite(number)) {
                throw RuntimeOps.error("ValueError", "cannot convert non-finite float to int");
            }
            return new PyInt(BigDecimal.valueOf(number).toBigInteger());
        }
        if (value instanceof PyString) {
            String text = ((PyString) value).getValue().trim();
            try {
                return new PyInt(parseInteger(text, base));
            } catch (NumberFormatException invalid) {
                throw RuntimeOps.error("ValueError", "invalid literal for int(): '" + text + "'");
            }
        }
        throw typeError("int() argument must be a string or a number, not '"
                + value.getTypeName() + "'");
    }

    private static BigInteger parseInteger(String text, int base) {
        if (base == 0) {
            boolean negative = text.startsWith("-");
            boolean positive = text.startsWith("+");
            String unsigned = negative || positive ? text.substring(1) : text;
            int detected = 10;
            if (unsigned.startsWith("0x") || unsigned.startsWith("0X")) detected = 16;
            else if (unsigned.startsWith("0o") || unsigned.startsWith("0O")) detected = 8;
            else if (unsigned.startsWith("0b") || unsigned.startsWith("0B")) detected = 2;
            if (detected != 10) unsigned = unsigned.substring(2);
            BigInteger parsed = new BigInteger(unsigned.replace("_", ""), detected);
            return negative ? parsed.negate() : parsed;
        }
        return new BigInteger(text.replace("_", ""), base);
    }

    private static PyValue constructFloat(
            List<PyValue> positional, Map<String, PyValue> keywords) {
        between("float", positional, 0, 1);
        noKeywords("float", keywords);
        if (positional.isEmpty()) return new PyFloat(0.0d);
        PyValue value = positional.get(0);
        if (value instanceof PyFloat) return value;
        if (value instanceof PyInt) return new PyFloat(((PyInt) value).getValue().doubleValue());
        if (value instanceof PyBool) {
            return new PyFloat(((PyBool) value).getValue() ? 1.0d : 0.0d);
        }
        if (value instanceof PyString) {
            try {
                return new PyFloat(Double.parseDouble(
                        ((PyString) value).getValue().trim().replace("_", "")));
            } catch (NumberFormatException invalid) {
                throw RuntimeOps.error("ValueError", "could not convert string to float");
            }
        }
        throw typeError("float() argument must be a string or a real number, not '"
                + value.getTypeName() + "'");
    }

    private static PyValue constructBool(
            List<PyValue> positional, Map<String, PyValue> keywords) {
        between("bool", positional, 0, 1);
        noKeywords("bool", keywords);
        return PyBool.valueOf(!positional.isEmpty()
                && RuntimeOps.isTruthy(positional.get(0)));
    }

    private static PyValue constructList(
            List<PyValue> positional, Map<String, PyValue> keywords) {
        between("list", positional, 0, 1);
        noKeywords("list", keywords);
        return positional.isEmpty() ? new PyList() : new PyList(collect(positional.get(0)));
    }

    private static PyValue constructTuple(
            List<PyValue> positional, Map<String, PyValue> keywords) {
        between("tuple", positional, 0, 1);
        noKeywords("tuple", keywords);
        if (positional.isEmpty()) return PyTuple.EMPTY;
        if (positional.get(0) instanceof PyTuple) return positional.get(0);
        return new PyTuple(collect(positional.get(0)));
    }

    private static PyValue constructSet(
            List<PyValue> positional, Map<String, PyValue> keywords) {
        between("set", positional, 0, 1);
        noKeywords("set", keywords);
        return positional.isEmpty() ? new PySet() : new PySet(collect(positional.get(0)));
    }

    private static PyValue constructDict(
            List<PyValue> positional, Map<String, PyValue> keywords) {
        between("dict", positional, 0, 1);
        PyDict result = new PyDict();
        if (!positional.isEmpty()) {
            PyValue source = positional.get(0);
            if (source instanceof PyDict) {
                for (PyDict.Entry entry : ((PyDict) source).getEntries()) {
                    result.put(entry.getKey(), entry.getValue());
                }
            } else {
                PyIterator iterator = iterator(source);
                Optional<PyValue> next;
                int index = 0;
                while ((next = iterator.tryNext()).isPresent()) {
                    List<PyValue> pair;
                    try {
                        pair = RuntimeOps.unpack(next.get(), 2);
                    } catch (VmRuntimeException failure) {
                        throw RuntimeOps.error("ValueError",
                                "dictionary update sequence element #" + index
                                        + " has invalid length");
                    }
                    result.put(pair.get(0), pair.get(1));
                    index++;
                }
            }
        }
        for (Map.Entry<String, PyValue> entry : keywords.entrySet()) {
            result.put(new PyString(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static PyValue typeOf(
            PyValue value, Map<String, PyNativeClass> types) {
        if (value instanceof PyInstance) return ((PyInstance) value).getPythonClass();
        if (value instanceof PyClass || value instanceof PyNativeClass) {
            return types.get("type");
        }
        PyNativeClass nativeType = nativeTypeOf(value, types);
        return nativeType == null ? types.get("object") : nativeType;
    }

    private static PyNativeClass nativeTypeOf(
            PyValue value, Map<String, PyNativeClass> types) {
        if (value instanceof PyBool) return types.get("bool");
        if (value instanceof PyInt) return types.get("int");
        if (value instanceof PyFloat) return types.get("float");
        if (value instanceof PyString) return types.get("str");
        if (value instanceof PyList) return types.get("list");
        if (value instanceof PyTuple) return types.get("tuple");
        if (value instanceof PySet) return types.get("set");
        if (value instanceof PyDict) return types.get("dict");
        if (value instanceof PyNativeObject) return types.get("object");
        if (value instanceof PyBaseException) {
            return types.get(((PyBaseException) value).getExceptionTypeName());
        }
        return null;
    }

    private static PyIterator iterator(PyValue value) {
        return value instanceof PyRange
                ? ((PyRange) value).iterator()
                : RuntimeOps.iter(value);
    }

    private static List<PyValue> collect(PyValue value) {
        PyIterator iterator = iterator(value);
        List<PyValue> result = new ArrayList<>();
        Optional<PyValue> next;
        while ((next = iterator.tryNext()).isPresent()) result.add(next.get());
        return result;
    }

    private static String attributeName(String function, PyValue value) {
        if (!(value instanceof PyString)) {
            throw typeError(function + "(): attribute name must be string");
        }
        return ((PyString) value).getValue();
    }

    private static String stringKeyword(
            String function,
            Map<String, PyValue> keywords,
            String name,
            String fallback) {
        PyValue value = keywords.get(name);
        if (value == null) return fallback;
        if (!(value instanceof PyString)) {
            throw typeError(function + "() " + name + " must be str");
        }
        return ((PyString) value).getValue();
    }

    private static PyValue requireCallable(String function, PyValue value) {
        if (!(value instanceof PyCallable)) {
            throw typeError(function + "() expected a callable, got '"
                    + value.getTypeName() + "'");
        }
        return value;
    }

    private static BigInteger asInteger(PyValue value, String function) {
        if (value instanceof PyInt) return ((PyInt) value).getValue();
        if (value instanceof PyBool) {
            return ((PyBool) value).getValue() ? BigInteger.ONE : BigInteger.ZERO;
        }
        throw typeError("'" + value.getTypeName()
                + "' object cannot be interpreted as an integer in " + function + "()");
    }

    private static int exactInt(BigInteger value, String function) {
        try {
            return value.intValueExact();
        } catch (ArithmeticException tooLarge) {
            throw RuntimeOps.error("ValueError", function + "() integer is too large");
        }
    }

    private static VmRuntimeException typeError(String message) {
        return RuntimeOps.error("TypeError", message);
    }

    private static void exact(String name, List<PyValue> positional, int count) {
        if (positional.size() != count) {
            throw typeError(name + "() takes exactly " + count
                    + " argument(s) (" + positional.size() + " given)");
        }
    }

    private static void atLeast(String name, List<PyValue> positional, int minimum) {
        if (positional.size() < minimum) {
            throw typeError(name + "() expected at least " + minimum
                    + " argument(s), got " + positional.size());
        }
    }

    private static void between(
            String name, List<PyValue> positional, int minimum, int maximum) {
        if (positional.size() < minimum || positional.size() > maximum) {
            throw typeError(name + "() expected " + minimum + " to " + maximum
                    + " argument(s), got " + positional.size());
        }
    }

    private static void noKeywords(String name, Map<String, PyValue> keywords) {
        if (!keywords.isEmpty()) {
            throw typeError(name + "() got an unexpected keyword argument '"
                    + keywords.keySet().iterator().next() + "'");
        }
    }

    private static void rejectUnknownKeywords(
            String name,
            Map<String, PyValue> keywords,
            Set<String> allowed) {
        for (String keyword : keywords.keySet()) {
            if (!allowed.contains(keyword)) {
                throw typeError(name + "() got an unexpected keyword argument '"
                        + keyword + "'");
            }
        }
    }
}
