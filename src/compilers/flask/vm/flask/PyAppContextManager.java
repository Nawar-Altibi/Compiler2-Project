package compilers.flask.vm.flask;

import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.VmCallContext;
import compilers.flask.vm.values.PyAttributeProvider;
import compilers.flask.vm.values.PyBool;
import compilers.flask.vm.values.PyNativeFunction;
import compilers.flask.vm.values.PyValue;

import java.util.Optional;

/** Explicit app-context manager consumed by the later WITH engine too. */
public final class PyAppContextManager implements PyValue, PyAttributeProvider {
    private final PyFlaskApp app;
    private FlaskExecutionContext.Scope scope;
    private VmCallContext owner;

    public PyAppContextManager(PyFlaskApp app) { this.app = app; }

    @Override
    public Optional<PyValue> findAttribute(String name) {
        if ("__enter__".equals(name)) {
            return Optional.<PyValue>of(new PyNativeFunction("__enter__", (context, p, k) -> {
                PyFlaskApp.requireArity("__enter__", p, k, 0);
                if (scope != null) throw RuntimeOps.error("RuntimeError", "context is already entered");
                owner = context;
                scope = context.getRuntimeServices().getFlask().enterApp(app);
                return app;
            }));
        }
        if ("__exit__".equals(name)) {
            return Optional.<PyValue>of(new PyNativeFunction("__exit__", (context, p, k) -> {
                if (p.size() != 3 || !k.isEmpty()) {
                    throw RuntimeOps.error("TypeError", "__exit__() expects three arguments");
                }
                if (scope == null || owner != context) {
                    throw RuntimeOps.error("RuntimeError", "context is not active in this VM");
                }
                scope.close();
                scope = null;
                owner = null;
                return PyBool.FALSE;
            }));
        }
        return Optional.empty();
    }

    @Override public String getTypeName() { return "AppContext"; }
    @Override public String repr() { return "<AppContext " + app.repr() + ">"; }
    @Override public String toString() { return repr(); }
}
