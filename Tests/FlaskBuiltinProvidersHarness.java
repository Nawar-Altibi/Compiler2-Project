import compilers.flask.SymbolTable.SymbolEntry;
import compilers.flask.runtime.RuntimeSymbolManifest;
import compilers.flask.runtime.RuntimeSymbolSpec;
import compilers.flask.vm.Namespace;
import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.VmCallContext;
import compilers.flask.vm.VmRuntimeException;
import compilers.flask.vm.builtins.PythonBuiltinProviders;
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
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyRange;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyTuple;
import compilers.flask.vm.values.PyValue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Parameterized provider-contract tests for all mandatory Python builtins. */
public final class FlaskBuiltinProvidersHarness {
    private static int passed;
    private static int failed;

    private FlaskBuiltinProvidersHarness() {
    }

    public static void main(String[] args) {
        run("manifest/provider parity and callable identity",
                FlaskBuiltinProvidersHarness::testManifestParity);
        run("success or capability contract for every builtin",
                FlaskBuiltinProvidersHarness::testEveryProviderSuccessVector);
        run("runtime error contract for every builtin",
                FlaskBuiltinProvidersHarness::testEveryProviderErrorVector);
        run("numeric and conversion behavior",
                FlaskBuiltinProvidersHarness::testNumericAndConversions);
        run("iteration and higher-order behavior",
                FlaskBuiltinProvidersHarness::testIterationAndHigherOrder);
        run("native type and exception hierarchy",
                FlaskBuiltinProvidersHarness::testTypeRelationships);
        run("attribute and namespace behavior",
                FlaskBuiltinProvidersHarness::testAttributesAndNamespaces);
        run("print output and filesystem denial",
                FlaskBuiltinProvidersHarness::testOutputAndCapabilityDenial);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " builtin-provider test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " Flask builtin-provider tests passed.");
    }

    private static void testManifestParity() {
        Namespace namespace = PythonBuiltinProviders.create();
        Map<String, PyValue> providers = namespace.snapshot();
        equal(RuntimeSymbolManifest.builtins().keySet(), providers.keySet(),
                "manifest/provider names");
        equal(66, providers.size(), "frozen mandatory builtin count");
        for (Map.Entry<String, RuntimeSymbolSpec> entry
                : RuntimeSymbolManifest.builtins().entrySet()) {
            PyValue provider = providers.get(entry.getKey());
            check(provider instanceof PyCallable,
                    entry.getKey() + " provider is not callable");
            if (entry.getValue().kind() == SymbolEntry.SymbolKind.CLASS) {
                check(provider instanceof PyNativeClass,
                        entry.getKey() + " class provider has wrong runtime kind");
            }
        }
        same(providers.get("OSError"), providers.get("IOError"),
                "IOError must alias OSError exactly");

        Namespace fresh = PythonBuiltinProviders.create();
        check(fresh != namespace, "create() must return a fresh Namespace");
        namespace.put("local_only", PyNone.INSTANCE);
        check(!fresh.contains("local_only"), "registry namespaces cannot leak mutations");
    }

    private static void testEveryProviderSuccessVector() {
        Namespace builtins = PythonBuiltinProviders.create();
        RecordingContext context = context();
        for (String name : RuntimeSymbolManifest.builtins().keySet()) {
            try {
                if ("open".equals(name)) {
                    expectRuntime("PermissionError", () -> invoke(
                            builtins, context, name, List.of(text("safe.txt")), Map.of()));
                } else {
                    PyValue result = invokeSuccessVector(builtins, context, name);
                    check(result != null, name + " returned Java null");
                }
            } catch (Throwable failure) {
                throw new AssertionError("success vector failed for " + name, failure);
            }
        }
    }

    private static PyValue invokeSuccessVector(
            Namespace builtins, RecordingContext context, String name) {
        if (isExceptionClass(name)) {
            return invoke(builtins, context, name, List.of(text("message")), Map.of());
        }
        switch (name) {
            case "print": return invoke(builtins, context, name, List.of(text("ok")), Map.of());
            case "len": return invoke(builtins, context, name,
                    List.of(new PyList(List.of(integer(1)))), Map.of());
            case "range": return invoke(builtins, context, name, List.of(integer(3)), Map.of());
            case "enumerate": return invoke(builtins, context, name,
                    List.of(new PyTuple(List.of(text("a")))), Map.of());
            case "zip": return invoke(builtins, context, name,
                    List.of(new PyList(), new PyTuple(List.of())), Map.of());
            case "map": return invoke(builtins, context, name,
                    List.of(identityFunction(), new PyList(List.of(integer(1)))), Map.of());
            case "filter": return invoke(builtins, context, name,
                    List.of(PyNone.INSTANCE, new PyList(List.of(integer(1)))), Map.of());
            case "sorted": return invoke(builtins, context, name,
                    List.of(new PyList(List.of(integer(2), integer(1)))), Map.of());
            case "reversed": return invoke(builtins, context, name,
                    List.of(new PyTuple(List.of(integer(1)))), Map.of());
            case "sum": return invoke(builtins, context, name,
                    List.of(new PyTuple(List.of(integer(1), integer(2)))), Map.of());
            case "min":
            case "max": return invoke(builtins, context, name,
                    List.of(integer(2), integer(1)), Map.of());
            case "abs": return invoke(builtins, context, name, List.of(integer(-1)), Map.of());
            case "round": return invoke(builtins, context, name,
                    List.of(new PyFloat(1.5)), Map.of());
            case "isinstance": return invoke(builtins, context, name,
                    List.of(integer(1), provider(builtins, "int")), Map.of());
            case "issubclass": return invoke(builtins, context, name,
                    List.of(provider(builtins, "bool"), provider(builtins, "int")), Map.of());
            case "type": return invoke(builtins, context, name, List.of(integer(1)), Map.of());
            case "hasattr": return invoke(builtins, context, name,
                    List.of(provider(builtins, "int"), text("__name__")), Map.of());
            case "getattr": return invoke(builtins, context, name,
                    List.of(provider(builtins, "int"), text("__name__")), Map.of());
            case "setattr": {
                PyClass target = userClass("SetTarget");
                return invoke(builtins, context, name,
                        List.of(target, text("x"), integer(1)), Map.of());
            }
            case "delattr": {
                PyClass target = userClass("DeleteTarget");
                target.setAttribute("x", integer(1));
                return invoke(builtins, context, name,
                        List.of(target, text("x")), Map.of());
            }
            case "repr": return invoke(builtins, context, name, List.of(text("x")), Map.of());
            case "format": return invoke(builtins, context, name, List.of(integer(1)), Map.of());
            case "any":
            case "all": return invoke(builtins, context, name,
                    List.of(new PyList(List.of(PyBool.TRUE))), Map.of());
            case "next": return invoke(builtins, context, name,
                    List.of(new PyIterator(List.of(integer(1)))), Map.of());
            case "iter": return invoke(builtins, context, name,
                    List.of(new PyList()), Map.of());
            case "id": return invoke(builtins, context, name, List.of(PyNone.INSTANCE), Map.of());
            case "ord": return invoke(builtins, context, name, List.of(text("A")), Map.of());
            case "chr": return invoke(builtins, context, name, List.of(integer(65)), Map.of());
            case "callable": return invoke(builtins, context, name,
                    List.of(provider(builtins, "print")), Map.of());
            case "hash": return invoke(builtins, context, name, List.of(integer(1)), Map.of());
            case "globals":
            case "locals": return invoke(builtins, context, name, List.of(), Map.of());
            case "pow": return invoke(builtins, context, name,
                    List.of(integer(2), integer(3)), Map.of());
            case "divmod": return invoke(builtins, context, name,
                    List.of(integer(7), integer(3)), Map.of());
            case "str":
            case "int":
            case "float":
            case "bool":
            case "list":
            case "dict":
            case "set":
            case "tuple":
            case "object": return invoke(builtins, context, name, List.of(), Map.of());
            default: throw new AssertionError("Missing success vector for " + name);
        }
    }

    private static void testEveryProviderErrorVector() {
        Namespace builtins = PythonBuiltinProviders.create();
        RecordingContext context = context();
        for (String name : RuntimeSymbolManifest.builtins().keySet()) {
            try {
                if ("dict".equals(name)) {
                    expectRuntime("TypeError", () -> invoke(
                            builtins, context, name,
                            List.of(PyNone.INSTANCE, PyNone.INSTANCE), Map.of()));
                } else {
                    expectRuntime("TypeError", () -> invoke(
                            builtins, context, name,
                            Collections.emptyList(), Map.of("__bad__", PyNone.INSTANCE)));
                }
            } catch (Throwable failure) {
                throw new AssertionError("error vector failed for " + name, failure);
            }
        }
    }

    private static void testNumericAndConversions() {
        Namespace builtins = PythonBuiltinProviders.create();
        RecordingContext context = context();
        equal(integer(255), invoke(builtins, context, "int",
                List.of(text("ff"), integer(16)), Map.of()), "int base conversion");
        equal(new PyFloat(2.5), invoke(builtins, context, "float",
                List.of(text("2.5")), Map.of()), "float conversion");
        same(PyBool.FALSE, invoke(builtins, context, "bool",
                List.of(new PyList()), Map.of()), "bool conversion");
        equal(integer(8), invoke(builtins, context, "pow",
                List.of(integer(2), integer(3)), Map.of()), "pow");
        equal(integer(4), invoke(builtins, context, "pow",
                List.of(integer(2), integer(5), integer(7)), Map.of()), "modular pow");
        PyTuple divmod = (PyTuple) invoke(builtins, context, "divmod",
                List.of(integer(-7), integer(3)), Map.of());
        equal(List.of(integer(-3), integer(2)), divmod.getElements(), "floor divmod");
        equal(integer(3), invoke(builtins, context, "sum",
                List.of(new PyTuple(List.of(integer(1), integer(2)))), Map.of()), "sum");
        expectRuntime("ValueError", () -> invoke(builtins, context, "chr",
                List.of(integer(0x110000)), Map.of()));
    }

    private static void testIterationAndHigherOrder() {
        Namespace builtins = PythonBuiltinProviders.create();
        RecordingContext context = context();
        PyRange range = (PyRange) invoke(builtins, context, "range",
                List.of(integer(1), integer(6), integer(2)), Map.of());
        equal(BigInteger.valueOf(3), range.getLength(), "range length");
        equal(List.of(integer(1), integer(3), integer(5)),
                drain((PyIterator) invoke(builtins, context, "iter", List.of(range), Map.of())),
                "range iteration");

        PyNativeFunction doubleValue = new PyNativeFunction(
                "double", (callContext, positional, keywords) ->
                RuntimeOps.binary("MULTIPLY", positional.get(0), integer(2)));
        PyIterator mapped = (PyIterator) invoke(builtins, context, "map",
                List.of(doubleValue, new PyList(List.of(integer(1), integer(2)))), Map.of());
        equal(List.of(integer(2), integer(4)), drain(mapped), "map values");

        AtomicInteger lazyCalls = new AtomicInteger();
        PyNativeFunction countingIdentity = new PyNativeFunction(
                "counting_identity", (active, positional, keywords) -> {
                    lazyCalls.incrementAndGet();
                    return positional.get(0);
                });
        PyIterator lazyMapped = (PyIterator) invoke(builtins, context, "map",
                List.of(countingIdentity,
                        new PyList(List.of(integer(7), integer(8)))), Map.of());
        equal(0, lazyCalls.get(), "map must not invoke function eagerly");
        equal(integer(7), lazyMapped.tryNext().orElse(null), "lazy map first value");
        equal(1, lazyCalls.get(), "map must invoke once for one consumed value");

        PyIterator enumerateSource = new PyIterator(List.of(integer(3)));
        PyIterator lazyEnumerate = (PyIterator) invoke(builtins, context, "enumerate",
                List.of(enumerateSource), Map.of());
        equal(0, enumerateSource.getConsumedCount(), "enumerate consumed eagerly");
        check(lazyEnumerate.tryNext().isPresent(), "enumerate first value missing");
        equal(1, enumerateSource.getConsumedCount(), "enumerate lazy consumption");

        PyIterator zipped = (PyIterator) invoke(builtins, context, "zip",
                List.of(new PyList(List.of(integer(1), integer(2))),
                        new PyList(List.of(text("a")))), Map.of());
        List<PyValue> rows = drain(zipped);
        equal(1, rows.size(), "zip shortest iterable");
        equal(List.of(integer(1), text("a")),
                ((PyTuple) rows.get(0)).getElements(), "zip row");

        PyList sorted = (PyList) invoke(builtins, context, "sorted",
                List.of(new PyList(List.of(integer(3), integer(1), integer(2)))),
                Map.of("reverse", PyBool.TRUE));
        equal(List.of(integer(3), integer(2), integer(1)),
                sorted.getElements(), "sorted reverse");

        AtomicInteger keyCalls = new AtomicInteger();
        PyNativeFunction tupleKey = new PyNativeFunction(
                "tuple_key", (active, positional, keywords) -> {
                    keyCalls.incrementAndGet();
                    return ((PyTuple) positional.get(0)).getElements().get(0);
                });
        PyTuple firstTwo = new PyTuple(List.of(integer(2), text("first")));
        PyTuple secondTwo = new PyTuple(List.of(integer(2), text("second")));
        PyTuple one = new PyTuple(List.of(integer(1), text("one")));
        PyList keyed = (PyList) invoke(builtins, context, "sorted",
                List.of(new PyList(List.of(firstTwo, one, secondTwo))),
                Map.of("key", tupleKey, "reverse", PyBool.TRUE));
        equal(List.of(firstTwo, secondTwo, one), keyed.getElements(),
                "sorted reverse must remain stable for equal keys");
        equal(3, keyCalls.get(), "sorted key must run exactly once per item");
    }

    private static void testTypeRelationships() {
        Namespace builtins = PythonBuiltinProviders.create();
        RecordingContext context = context();
        PyValue integerType = provider(builtins, "int");
        PyValue booleanType = provider(builtins, "bool");
        same(integerType, invoke(builtins, context, "type",
                List.of(integer(1)), Map.of()), "type(int value)");
        same(PyBool.TRUE, invoke(builtins, context, "issubclass",
                List.of(booleanType, integerType), Map.of()), "bool subclass int");
        same(PyBool.TRUE, invoke(builtins, context, "isinstance",
                List.of(PyBool.TRUE, integerType), Map.of()), "bool instance int");

        PyValue zeroDivision = invoke(builtins, context, "ZeroDivisionError",
                List.of(text("zero")), Map.of());
        same(PyBool.TRUE, invoke(builtins, context, "isinstance",
                List.of(zeroDivision, provider(builtins, "ArithmeticError")), Map.of()),
                "exception inheritance");
        same(provider(builtins, "OSError"), provider(builtins, "IOError"),
                "exception alias");

        PyClass base = userClass("Base");
        PyClass child = new PyClass("Child", List.of(base), new Namespace());
        PyInstance instance = new PyInstance(child);
        same(PyBool.TRUE, invoke(builtins, context, "isinstance",
                List.of(instance, base), Map.of()), "user class isinstance");
        same(PyBool.TRUE, invoke(builtins, context, "issubclass",
                List.of(child, base), Map.of()), "user class issubclass");
    }

    private static void testAttributesAndNamespaces() {
        Namespace builtins = PythonBuiltinProviders.create();
        RecordingContext context = context();
        PyClass target = userClass("Target");
        invoke(builtins, context, "setattr",
                List.of(target, text("answer"), integer(42)), Map.of());
        equal(integer(42), invoke(builtins, context, "getattr",
                List.of(target, text("answer")), Map.of()), "getattr after setattr");
        same(PyBool.TRUE, invoke(builtins, context, "hasattr",
                List.of(target, text("answer")), Map.of()), "hasattr true");
        invoke(builtins, context, "delattr",
                List.of(target, text("answer")), Map.of());
        equal(text("fallback"), invoke(builtins, context, "getattr",
                List.of(target, text("answer"), text("fallback")), Map.of()),
                "getattr default");

        context.globals.put("global_value", integer(1));
        context.locals.put("local_value", integer(2));
        PyDict globals = (PyDict) invoke(
                builtins, context, "globals", List.of(), Map.of());
        PyDict locals = (PyDict) invoke(
                builtins, context, "locals", List.of(), Map.of());
        equal(integer(1), globals.find(text("global_value")).orElse(null),
                "globals snapshot");
        equal(integer(2), locals.find(text("local_value")).orElse(null),
                "locals snapshot");
    }

    private static void testOutputAndCapabilityDenial() {
        Namespace builtins = PythonBuiltinProviders.create();
        RecordingContext context = context();
        invoke(builtins, context, "print",
                List.of(text("a"), integer(2)),
                Map.of("sep", text("|"), "end", text("!")));
        equal("a|2!", context.stdout.toString(), "print output");
        expectRuntime("PermissionError", () -> invoke(
                builtins, context, "open", List.of(text("secret.txt")), Map.of()));
    }

    private static boolean isExceptionClass(String name) {
        switch (name) {
            case "BaseException": case "Exception": case "ArithmeticError":
            case "AssertionError": case "AttributeError": case "ImportError":
            case "LookupError": case "IndexError": case "KeyError":
            case "NameError": case "UnboundLocalError": case "OSError":
            case "IOError": case "FileNotFoundError": case "PermissionError":
            case "RuntimeError": case "StopIteration": case "TypeError":
            case "ValueError": case "ZeroDivisionError": return true;
            default: return false;
        }
    }

    private static PyNativeFunction identityFunction() {
        return new PyNativeFunction(
                "identity", (context, positional, keywords) -> positional.get(0));
    }

    private static PyClass userClass(String name) {
        return new PyClass(name, List.of(), new Namespace());
    }

    private static RecordingContext context() {
        return new RecordingContext();
    }

    private static PyValue provider(Namespace builtins, String name) {
        return builtins.find(name).orElseThrow(
                () -> new AssertionError("missing provider " + name));
    }

    private static PyValue invoke(
            Namespace builtins,
            RecordingContext context,
            String name,
            List<PyValue> positional,
            Map<String, PyValue> keywords) {
        return context.invoke(provider(builtins, name), positional, keywords);
    }

    private static List<PyValue> drain(PyIterator iterator) {
        List<PyValue> values = new ArrayList<>();
        Optional<PyValue> next;
        while ((next = iterator.tryNext()).isPresent()) values.add(next.get());
        return values;
    }

    private static PyInt integer(long value) {
        return PyInt.valueOf(value);
    }

    private static PyString text(String value) {
        return new PyString(value);
    }

    private static void expectRuntime(String typeName, Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected " + typeName);
        } catch (VmRuntimeException failure) {
            equal(typeName, failure.getExceptionValue().getExceptionTypeName(),
                    "runtime error type");
        }
    }

    private static void run(String name, Runnable test) {
        try {
            test.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable failure) {
            failed++;
            System.err.println("[FAIL] " + name + ": " + failure);
            failure.printStackTrace(System.err);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void same(Object expected, Object actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": identities differ");
        }
    }

    private static final class RecordingContext implements VmCallContext {
        private final Namespace globals = new Namespace();
        private final Namespace locals = new Namespace();
        private final StringBuilder stdout = new StringBuilder();

        @Override
        public PyValue invoke(
                PyValue callable,
                List<PyValue> positionalArguments,
                Map<String, PyValue> keywordArguments) {
            if (!(callable instanceof PyCallable)) {
                throw RuntimeOps.error("TypeError", "'" + callable.getTypeName()
                        + "' object is not callable");
            }
            return ((PyCallable) callable).call(
                    this, positionalArguments, keywordArguments);
        }

        @Override
        public PyValue executeFunction(
                PyFunction function,
                List<PyValue> positionalArguments,
                Map<String, PyValue> keywordArguments) {
            throw new AssertionError("No bytecode PyFunction is used by this harness");
        }

        @Override public Namespace getCurrentGlobals() { return globals; }
        @Override public Namespace getCurrentLocals() { return locals; }
        @Override public void writeStdout(String text) { stdout.append(text); }
    }
}
