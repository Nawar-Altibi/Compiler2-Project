import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.codegen.bytecode.BytecodeFormat;
import compilers.flask.codegen.bytecode.CodeKind;
import compilers.flask.codegen.bytecode.CodeObject;
import compilers.flask.codegen.bytecode.ConstantPool;
import compilers.flask.codegen.bytecode.FunctionSignature;
import compilers.flask.codegen.bytecode.Instruction;
import compilers.flask.codegen.bytecode.InstructionLocation;
import compilers.flask.codegen.bytecode.NamePool;
import compilers.flask.codegen.bytecode.OpCode;
import compilers.flask.codegen.bytecode.Operand;
import compilers.flask.codegen.bytecode.SourceMap;
import compilers.flask.vm.Namespace;
import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.VmCallContext;
import compilers.flask.vm.VmRuntimeException;
import compilers.flask.vm.builtins.PythonBuiltinProviders;
import compilers.flask.vm.values.PyBoundMethod;
import compilers.flask.vm.values.PyCallable;
import compilers.flask.vm.values.PyClass;
import compilers.flask.vm.values.PyFunction;
import compilers.flask.vm.values.PyInstance;
import compilers.flask.vm.values.PyInt;
import compilers.flask.vm.values.PyNativeClass;
import compilers.flask.vm.values.PyNativeFunction;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyType;
import compilers.flask.vm.values.PyValue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Dependency-free direct tests for the Phase-5 class/object model. */
public final class FlaskClassModelHarness {
    private static int nextCodeId = 10_000;
    private static int passed;
    private static int failed;

    private FlaskClassModelHarness() {
    }

    public static void main(String[] args) {
        run("C3 diamond order and lookup",
                FlaskClassModelHarness::testC3DiamondAndLookup);
        run("C3 identity and inconsistent hierarchy rejection",
                FlaskClassModelHarness::testC3IdentityAndRejection);
        run("native bases participate in mixed C3 and instance relations",
                FlaskClassModelHarness::testMixedNativeBaseHierarchy);
        run("class attribute ownership and inheritance",
                FlaskClassModelHarness::testClassAttributeOwnership);
        run("instance attributes shadow and fall back",
                FlaskClassModelHarness::testInstanceAttributePrecedence);
        run("PyFunction becomes a bound method exactly once",
                FlaskClassModelHarness::testFunctionBinding);
        run("non-function callable is not descriptor-bound",
                FlaskClassModelHarness::testLimitedDescriptorBoundary);
        run("class call invokes inherited bound __init__",
                FlaskClassModelHarness::testInitializerContract);
        run("initializer must return None",
                FlaskClassModelHarness::testInitializerReturnContract);
        run("class without initializer accepts no arguments",
                FlaskClassModelHarness::testMissingInitializerContract);
        run("class and instance state are isolated and views immutable",
                FlaskClassModelHarness::testIsolationAndImmutability);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " class-model test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " Flask class-model tests passed.");
    }

    private static void testC3DiamondAndLookup() {
        PyClass root = klass("Root", List.of(), attrs("origin", text("root")));
        PyClass left = klass("Left", List.of(root), attrs("side", text("left")));
        PyClass right = klass("Right", List.of(root), attrs("side", text("right")));
        PyClass diamond = klass("Diamond", List.of(left, right), new Namespace());

        identityList(List.of(diamond, left, right, root),
                diamond.getMethodResolutionOrder(), "diamond C3 MRO");
        same(textValue(left, "side"), value(diamond, "side"),
                "left base must win the C3 lookup");
        equal("root", string(value(diamond, "origin")), "deep inherited attribute");
        check(diamond.isSubclassOf(diamond), "class must be its own subclass");
        check(diamond.isSubclassOf(root), "diamond must be a Root subclass");
        check(!root.isSubclassOf(diamond), "Root cannot be a Diamond subclass");

        PyInstance instance = new PyInstance(diamond);
        check(instance.isInstanceOf(left), "instance must honor transitive bases");
        check(instance.isInstanceOf(root), "instance must honor diamond root");
    }

    private static void testC3IdentityAndRejection() {
        PyClass firstSameName = klass("Same", List.of(), new Namespace());
        PyClass secondSameName = klass("Same", List.of(), new Namespace());
        PyClass legal = klass(
                "Legal", List.of(firstSameName, secondSameName), new Namespace());
        identityList(List.of(legal, firstSameName, secondSameName),
                legal.getMethodResolutionOrder(),
                "same-spelled classes must remain identity-distinct");

        expectTypeError(
                () -> klass("Duplicate", List.of(firstSameName, firstSameName),
                        new Namespace()),
                "duplicate base class");
        expectTypeError(
                () -> klass("BadBase", List.of(PyInt.valueOf(1)), new Namespace()),
                "base must be a class");

        PyClass x = klass("X", List.of(), new Namespace());
        PyClass y = klass("Y", List.of(), new Namespace());
        PyClass a = klass("A", List.of(x, y), new Namespace());
        PyClass b = klass("B", List.of(y, x), new Namespace());
        expectTypeError(
                () -> klass("Impossible", List.of(a, b), new Namespace()),
                "consistent method resolution order");
    }

    private static void testMixedNativeBaseHierarchy() {
        Namespace builtins = PythonBuiltinProviders.create();
        PyNativeClass object = nativeClass(builtins, "object");
        PyNativeClass baseException = nativeClass(builtins, "BaseException");
        PyNativeClass exception = nativeClass(builtins, "Exception");

        PyClass plain = klass(
                "Plain", List.of(object), attrs("plain", integer(1)));
        identityList(List.of(plain, object), plain.getMethodResolutionOrder(),
                "explicit object base MRO");
        check(plain.isSubclassOf(object), "Plain must subclass native object");

        PyClass left = klass(
                "LeftError", List.of(exception), attrs("side", text("left")));
        PyClass right = klass(
                "RightError", List.of(exception), attrs("side", text("right")));
        PyClass mixed = klass(
                "MixedError", List.of(left, right), new Namespace());

        identityList(
                List.of(mixed, left, right, exception, baseException, object),
                mixed.getMethodResolutionOrder(), "mixed native/user C3 MRO");
        equal("left", string(value(mixed, "side")),
                "mixed MRO attribute precedence");
        check(mixed.isSubclassOf(exception),
                "mixed class must subclass native Exception");
        check(mixed.isSubclassOf(baseException),
                "mixed class must subclass native BaseException");
        check(mixed.isSubclassOf(object),
                "mixed class must subclass native object");

        PyInstance instance = new PyInstance(mixed);
        check(instance.isInstanceOf(left), "mixed instance user-base relation");
        check(instance.isInstanceOf(exception),
                "mixed instance native Exception relation");
        check(instance.isInstanceOf(baseException),
                "mixed instance native BaseException relation");
        check(instance.isInstanceOf(object),
                "mixed instance native object relation");

        expectTypeError(
                () -> klass("BadMixed", List.of(object, exception), new Namespace()),
                "consistent method resolution order");
    }

    private static void testClassAttributeOwnership() {
        PyClass base = klass("Base", List.of(), attrs("value", integer(1)));
        PyClass child = klass("Child", List.of(base), new Namespace());

        equal(integer(1), value(child, "value"), "initial inherited value");
        check(!child.deleteAttribute("value"),
                "deleting an inherited attribute must not mutate its base");
        equal(integer(1), value(base, "value"), "base survived child delete");

        child.setAttribute("value", integer(2));
        equal(integer(2), value(child, "value"), "class-local shadow");
        equal(integer(1), value(base, "value"), "base remains independent");
        check(child.deleteAttribute("value"), "owned attribute delete");
        equal(integer(1), value(child, "value"), "lookup falls back after delete");

        base.setAttribute("later", text("visible"));
        equal("visible", string(value(child, "later")),
                "later base mutations remain visible through MRO");
    }

    private static void testInstanceAttributePrecedence() {
        PyClass type = klass("Record", List.of(), attrs("value", integer(10)));
        PyInstance first = new PyInstance(type);
        PyInstance second = new PyInstance(type);

        equal(integer(10), value(first, "value"), "first inherited value");
        first.setAttribute("value", integer(20));
        equal(integer(20), value(first, "value"), "instance shadow");
        equal(integer(10), value(second, "value"), "second instance isolation");
        equal(integer(10), value(type, "value"), "class isolation");

        check(first.deleteAttribute("value"), "instance-owned delete");
        equal(integer(10), value(first, "value"), "fallback after instance delete");
        check(!first.deleteAttribute("value"),
                "inherited attribute cannot be deleted through instance dictionary");
        check(!first.findAttribute("missing").isPresent(), "missing instance attribute");
    }

    private static void testFunctionBinding() {
        PyFunction function = function(
                "method", List.of("self", "value"), Collections.emptyList());
        PyClass type = klass("Bound", List.of(), attrs("method", function));
        PyInstance instance = new PyInstance(type);

        PyValue loaded = value(instance, "method");
        check(loaded instanceof PyBoundMethod,
                "instance PyFunction load must create PyBoundMethod");
        PyBoundMethod bound = (PyBoundMethod) loaded;
        same(function, bound.getCallable(), "bound callable identity");
        same(instance, bound.getReceiver(), "bound receiver identity");

        RecordingContext context = new RecordingContext();
        context.returnValue = integer(99);
        PyValue returned = context.invoke(
                loaded, List.of(integer(7)), Map.of("named", text("kept")));
        equal(integer(99), returned, "bound method return");
        same(function, context.lastFunction, "executed function");
        equal(2, context.lastPositional.size(), "bound positional count");
        same(instance, context.lastPositional.get(0), "self prepended once");
        equal(integer(7), context.lastPositional.get(1), "user positional value");
        equal("kept", string(context.lastKeywords.get("named")),
                "keywords preserved");

        Optional<PyValue> secondLoad = instance.findAttribute("method");
        check(secondLoad.isPresent() && secondLoad.get() instanceof PyBoundMethod,
                "repeated lookup remains bound");
        same(function, ((PyBoundMethod) secondLoad.get()).getCallable(),
                "function is never double-wrapped");
    }

    private static void testLimitedDescriptorBoundary() {
        PyNativeFunction nativeCallable = new PyNativeFunction(
                "native", (context, positional, keywords) -> PyNone.INSTANCE);
        PyClass type = klass("NativeHolder", List.of(), attrs("native", nativeCallable));
        PyInstance instance = new PyInstance(type);
        same(nativeCallable, value(instance, "native"),
                "only PyFunction participates in the supported binding subset");
    }

    private static void testInitializerContract() {
        PyFunction initializer = function(
                "__init__", List.of("self", "value"), Collections.emptyList());
        PyClass base = klass("Initializable", List.of(), attrs("__init__", initializer));
        PyClass child = klass("ChildInitializable", List.of(base), new Namespace());

        RecordingContext context = new RecordingContext();
        context.returnValue = PyNone.INSTANCE;
        context.assignInitializerValue = true;
        PyValue created = child.call(context, List.of(integer(42)), Collections.emptyMap());
        check(created instanceof PyInstance, "class call must create an instance");
        PyInstance instance = (PyInstance) created;
        same(child, instance.getPythonClass(), "fresh instance class");
        same(initializer, context.lastFunction, "inherited initializer function");
        same(instance, context.lastPositional.get(0), "initializer self");
        equal(integer(42), context.lastPositional.get(1), "initializer argument");
        equal(integer(42), value(instance, "initialized"),
                "initializer can mutate its fresh instance");
    }

    private static void testInitializerReturnContract() {
        PyFunction initializer = function(
                "__init__", List.of("self"), Collections.emptyList());
        PyClass type = klass("BadInit", List.of(), attrs("__init__", initializer));
        RecordingContext context = new RecordingContext();
        context.returnValue = integer(1);
        expectTypeError(
                () -> type.call(context, Collections.emptyList(), Collections.emptyMap()),
                "should return None");

        PyClass nonCallable = klass(
                "NonCallableInit", List.of(), attrs("__init__", integer(5)));
        expectTypeError(
                () -> nonCallable.call(
                        context, Collections.emptyList(), Collections.emptyMap()),
                "not callable");
    }

    private static void testMissingInitializerContract() {
        PyClass empty = klass("Empty", List.of(), new Namespace());
        RecordingContext context = new RecordingContext();
        PyValue first = empty.call(
                context, Collections.emptyList(), Collections.emptyMap());
        PyValue second = empty.call(
                context, Collections.emptyList(), Collections.emptyMap());
        check(first instanceof PyInstance && second instanceof PyInstance,
                "argument-free construction");
        check(first != second, "every class call must allocate a fresh instance");

        expectTypeError(
                () -> empty.call(context, List.of(integer(1)), Collections.emptyMap()),
                "takes no arguments");
        expectTypeError(
                () -> empty.call(context, Collections.emptyList(), Map.of("x", integer(1))),
                "takes no arguments");
    }

    private static void testIsolationAndImmutability() {
        Namespace seed = attrs("seed", integer(1));
        PyClass first = klass("First", List.of(), seed);
        seed.put("seed", integer(99));
        equal(integer(1), value(first, "seed"),
                "class must own a completed namespace copy");

        PyClass second = klass("Second", List.of(), new Namespace());
        first.setAttribute("onlyFirst", text("yes"));
        check(!second.findAttribute("onlyFirst").isPresent(),
                "unrelated class namespaces cannot leak");

        expectUnsupported(() -> first.getBases().add(second), "immutable bases");
        expectUnsupported(
                () -> first.getMethodResolutionOrder().clear(), "immutable MRO");
        expectUnsupported(
                () -> first.getOwnAttributes().put("bad", integer(0)),
                "immutable class snapshot");

        PyInstance instance = new PyInstance(first);
        instance.setAttribute("own", integer(3));
        expectUnsupported(
                () -> instance.getOwnAttributes().clear(),
                "immutable instance snapshot");
    }

    private static PyClass klass(
            String name, List<? extends PyValue> bases, Namespace attributes) {
        return new PyClass(name, bases, attributes);
    }

    private static Namespace attrs(String name, PyValue value) {
        Namespace result = new Namespace();
        result.put(name, value);
        return result;
    }

    private static PyFunction function(
            String name, List<String> parameters, List<String> defaults) {
        FunctionSignature signature = new FunctionSignature(parameters, defaults);
        List<Instruction> instructions = List.of(
                new Instruction(OpCode.LOAD_CONST, new Operand.ConstOperand(0)),
                new Instruction(OpCode.RETURN_VALUE));
        InstructionLocation location = new InstructionLocation(
                SourceSpan.UNKNOWN, true);
        CodeObject code = new CodeObject(
                BytecodeFormat.CURRENT_VERSION,
                nextCodeId++,
                CodeKind.FUNCTION,
                name,
                name,
                "<class-model>",
                instructions,
                new ConstantPool(List.of(PyNone.INSTANCE)),
                new NamePool(Collections.emptyList()),
                signature,
                parameters,
                Collections.emptyList(),
                Collections.emptyList(),
                0,
                Collections.emptyList(),
                Collections.emptyList(),
                new SourceMap(List.of(location, location)),
                Collections.emptyList(),
                1);
        return new PyFunction(
                code,
                new Namespace(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyList());
    }

    private static PyValue value(PyAttributeLookup provider, String name) {
        return provider.find(name).orElseThrow(
                () -> new AssertionError("missing attribute '" + name + "'"));
    }

    private static PyValue value(PyClass provider, String name) {
        return value(provider::findAttribute, name);
    }

    private static PyValue value(PyInstance provider, String name) {
        return value(provider::findAttribute, name);
    }

    private static PyValue textValue(PyClass provider, String name) {
        return value(provider, name);
    }

    private static PyInt integer(int value) {
        return new PyInt(BigInteger.valueOf(value));
    }

    private static PyString text(String value) {
        return new PyString(value);
    }

    private static String string(PyValue value) {
        check(value instanceof PyString, "expected str, got " + value.getTypeName());
        return ((PyString) value).getValue();
    }

    private static void expectTypeError(Runnable action, String messagePart) {
        try {
            action.run();
            throw new AssertionError("expected TypeError containing: " + messagePart);
        } catch (VmRuntimeException failure) {
            equal("TypeError", failure.getExceptionValue().getExceptionTypeName(),
                    "runtime error type");
            check(failure.getExceptionValue().getMessageText().contains(messagePart),
                    "TypeError message '" + failure.getExceptionValue().getMessageText()
                            + "' lacks '" + messagePart + "'");
        }
    }

    private static void expectUnsupported(Runnable action, String label) {
        try {
            action.run();
            throw new AssertionError(label + " did not reject mutation");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static PyNativeClass nativeClass(Namespace builtins, String name) {
        PyValue value = builtins.find(name).orElseThrow(
                () -> new AssertionError("missing native class " + name));
        check(value instanceof PyNativeClass, name + " is not a native class");
        return (PyNativeClass) value;
    }

    private static void identityList(
            List<? extends PyType> expected,
            List<? extends PyType> actual,
            String label) {
        equal(expected.size(), actual.size(), label + " size");
        for (int index = 0; index < expected.size(); index++) {
            same(expected.get(index), actual.get(index), label + " entry " + index);
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
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void same(Object expected, Object actual, String label) {
        if (expected != actual) {
            throw new AssertionError(
                    label + ": expected identity " + expected + ", got " + actual);
        }
    }

    @FunctionalInterface
    private interface PyAttributeLookup {
        Optional<PyValue> find(String name);
    }

    private static final class RecordingContext implements VmCallContext {
        private final Namespace globals = new Namespace();
        private final Namespace locals = new Namespace();
        private PyFunction lastFunction;
        private List<PyValue> lastPositional = Collections.emptyList();
        private Map<String, PyValue> lastKeywords = Collections.emptyMap();
        private PyValue returnValue = PyNone.INSTANCE;
        private boolean assignInitializerValue;

        @Override
        public PyValue invoke(
                PyValue callable,
                List<PyValue> positionalArguments,
                Map<String, PyValue> keywordArguments) {
            if (!(callable instanceof PyCallable)) {
                throw RuntimeOps.error(
                        "TypeError", "'" + callable.getTypeName()
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
            lastFunction = function;
            lastPositional = Collections.unmodifiableList(
                    new ArrayList<>(positionalArguments));
            lastKeywords = Collections.unmodifiableMap(
                    new LinkedHashMap<>(keywordArguments));
            if (assignInitializerValue && "__init__".equals(function.getName())) {
                check(positionalArguments.size() >= 2,
                        "initializer recording requires self and value");
                PyValue receiver = positionalArguments.get(0);
                check(receiver instanceof PyInstance,
                        "initializer receiver must be PyInstance");
                ((PyInstance) receiver).setAttribute(
                        "initialized", positionalArguments.get(1));
            }
            return returnValue;
        }

        @Override
        public Namespace getCurrentGlobals() {
            return globals;
        }

        @Override
        public Namespace getCurrentLocals() {
            return locals;
        }

        @Override
        public void writeStdout(String text) {
            // Model tests do not produce output.
        }
    }
}
