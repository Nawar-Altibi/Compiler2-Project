package compilers.flask.vm.values;

import compilers.flask.vm.VmCallContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Method wrapper that prepends its bound receiver exactly once. */
public final class PyBoundMethod implements PyCallable {
    private final PyValue callable;
    private final PyValue receiver;

    public PyBoundMethod(PyValue callable, PyValue receiver) {
        if (!(callable instanceof PyCallable)) {
            throw new IllegalArgumentException("Bound target must be callable");
        }
        this.callable = callable;
        this.receiver = Objects.requireNonNull(receiver, "receiver");
    }

    public PyValue getCallable() { return callable; }
    public PyValue getReceiver() { return receiver; }

    @Override
    public PyValue call(
            VmCallContext context,
            List<PyValue> positionalArguments,
            Map<String, PyValue> keywordArguments) {
        List<PyValue> bound = new ArrayList<>(positionalArguments.size() + 1);
        bound.add(receiver);
        bound.addAll(positionalArguments);
        // The outer VM invocation already accounts for this bound method.
        // Dispatch the underlying function directly so this wrapper does not
        // consume a second synthetic call-depth level of its own.
        return ((PyCallable) callable).call(context, bound, keywordArguments);
    }

    @Override public String getTypeName() { return "method"; }
    @Override public String repr() { return "<bound method " + callable.repr() + ">"; }
    @Override public String toString() { return repr(); }
}
