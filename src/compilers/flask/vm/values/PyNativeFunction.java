package compilers.flask.vm.values;

import compilers.flask.vm.VmCallContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Named host-approved callable backed by an explicit Java body. */
public final class PyNativeFunction implements PyCallable {
    @FunctionalInterface
    public interface Body {
        PyValue invoke(
                VmCallContext context,
                List<PyValue> positionalArguments,
                Map<String, PyValue> keywordArguments);
    }

    private final String name;
    private final Body body;

    public PyNativeFunction(String name, Body body) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Native function name cannot be empty");
        }
        this.name = name;
        this.body = Objects.requireNonNull(body, "body");
    }

    public String getName() { return name; }

    @Override
    public PyValue call(
            VmCallContext context,
            List<PyValue> positionalArguments,
            Map<String, PyValue> keywordArguments) {
        return Objects.requireNonNull(
                body.invoke(context, positionalArguments, keywordArguments),
                "native return value");
    }

    @Override public String getTypeName() { return "builtin_function_or_method"; }
    @Override public String repr() { return "<built-in function " + name + ">"; }
    @Override public String toString() { return repr(); }
}
