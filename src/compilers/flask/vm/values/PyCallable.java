package compilers.flask.vm.values;

import compilers.flask.vm.VmCallContext;

import java.util.List;
import java.util.Map;

/** Explicit callable protocol; the VM never invokes host reflection. */
public interface PyCallable extends PyValue {
    PyValue call(
            VmCallContext context,
            List<PyValue> positionalArguments,
            Map<String, PyValue> keywordArguments);
}
