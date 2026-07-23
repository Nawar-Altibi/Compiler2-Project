package compilers.flask.vm;

import compilers.flask.vm.values.PyFunction;
import compilers.flask.vm.values.PyValue;

import java.util.List;
import java.util.Map;

/** Controlled services available to Python and native callable values. */
public interface VmCallContext {
    PyValue invoke(
            PyValue callable,
            List<PyValue> positionalArguments,
            Map<String, PyValue> keywordArguments);

    PyValue executeFunction(
            PyFunction function,
            List<PyValue> positionalArguments,
            Map<String, PyValue> keywordArguments);

    Namespace getCurrentGlobals();

    Namespace getCurrentLocals();

    void writeStdout(String text);

    /** Per-execution capabilities and framework contexts. */
    default RuntimeServices getRuntimeServices() {
        return RuntimeServices.unavailable();
    }
}
