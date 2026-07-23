package compilers.flask.vm.values;

import compilers.flask.codegen.bytecode.CodeKind;
import compilers.flask.codegen.bytecode.CodeObject;
import compilers.flask.codegen.bytecode.FunctionSignature;
import compilers.flask.vm.Cell;
import compilers.flask.vm.Namespace;
import compilers.flask.vm.VmCallContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** User function with definition-time metadata and shared closure cells. */
public final class PyFunction implements PyCallable, PyAttributeProvider {
    private final CodeObject code;
    private final Namespace globals;
    private final Map<String, PyValue> defaultValues;
    private final Map<String, PyValue> annotations;
    private final List<Cell> closure;

    public PyFunction(
            CodeObject code,
            Namespace globals,
            Map<String, PyValue> defaultValues,
            Map<String, PyValue> annotations,
            List<Cell> closure) {
        this.code = Objects.requireNonNull(code, "code");
        if (!code.isVerified() || code.getKind() != CodeKind.FUNCTION) {
            throw new IllegalArgumentException(
                    "PyFunction requires a verified FUNCTION CodeObject");
        }
        this.globals = Objects.requireNonNull(globals, "globals");
        this.defaultValues = immutableValues(defaultValues, "default value");
        this.annotations = immutableValues(annotations, "annotation");
        this.closure = immutableCells(closure);
        if (!new ArrayList<>(this.defaultValues.keySet()).equals(
                code.getSignature().getDefaultedParameterNames())) {
            throw new IllegalArgumentException(
                    "Function defaults do not match its signature");
        }
        if (this.closure.size() != code.getFreeVariableNames().size()) {
            throw new IllegalArgumentException(
                    "Function closure does not match its free-variable table");
        }
    }

    public CodeObject getCode() { return code; }
    public String getName() { return code.getName(); }
    public String getQualifiedName() { return code.getQualifiedName(); }
    public FunctionSignature getSignature() { return code.getSignature(); }
    public Namespace getGlobals() { return globals; }
    public Map<String, PyValue> getDefaultValues() { return defaultValues; }
    public Map<String, PyValue> getAnnotations() { return annotations; }
    public List<Cell> getClosure() { return closure; }

    @Override
    public PyValue call(
            VmCallContext context,
            List<PyValue> positionalArguments,
            Map<String, PyValue> keywordArguments) {
        return context.executeFunction(
                this, positionalArguments, keywordArguments);
    }

    @Override
    public Optional<PyValue> findAttribute(String name) {
        if ("__name__".equals(name)) {
            return Optional.<PyValue>of(new PyString(getName()));
        }
        if ("__qualname__".equals(name)) {
            return Optional.<PyValue>of(new PyString(getQualifiedName()));
        }
        if ("__defaults__".equals(name)) {
            return Optional.<PyValue>of(defaultValues.isEmpty()
                    ? PyNone.INSTANCE
                    : new PyTuple(new ArrayList<>(defaultValues.values())));
        }
        if ("__annotations__".equals(name)) {
            PyDict result = new PyDict();
            for (Map.Entry<String, PyValue> entry : annotations.entrySet()) {
                result.put(new PyString(entry.getKey()), entry.getValue());
            }
            return Optional.<PyValue>of(result);
        }
        return Optional.empty();
    }

    @Override public String getTypeName() { return "function"; }
    @Override public String repr() { return "<function " + getQualifiedName() + ">"; }
    @Override public String toString() { return repr(); }

    private static Map<String, PyValue> immutableValues(
            Map<String, PyValue> source, String label) {
        Objects.requireNonNull(source, label + "s");
        LinkedHashMap<String, PyValue> copy = new LinkedHashMap<>();
        for (Map.Entry<String, PyValue> entry : source.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), label + " name");
            if (name.isEmpty() || copy.put(name, Objects.requireNonNull(
                    entry.getValue(), label)) != null) {
                throw new IllegalArgumentException("Invalid duplicate " + label + ": " + name);
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<Cell> immutableCells(List<Cell> source) {
        Objects.requireNonNull(source, "closure");
        List<Cell> copy = new ArrayList<>(source.size());
        for (Cell cell : source) {
            copy.add(Objects.requireNonNull(cell, "closure cell"));
        }
        return Collections.unmodifiableList(copy);
    }
}
