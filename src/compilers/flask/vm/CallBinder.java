package compilers.flask.vm;

import compilers.flask.codegen.bytecode.FunctionSignature;
import compilers.flask.vm.values.PyFunction;
import compilers.flask.vm.values.PyValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Python-like positional/keyword/default binding for the supported signature. */
public final class CallBinder {
    private CallBinder() {
    }

    public static Map<String, PyValue> bind(
            PyFunction function,
            List<PyValue> positionalArguments,
            Map<String, PyValue> keywordArguments) {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(positionalArguments, "positionalArguments");
        Objects.requireNonNull(keywordArguments, "keywordArguments");
        FunctionSignature signature = function.getSignature();
        List<String> parameters = signature.getParameterNames();
        if (positionalArguments.size() > parameters.size()) {
            throw RuntimeOps.error("TypeError", function.getName()
                    + "() takes " + parameters.size()
                    + " positional argument(s) but "
                    + positionalArguments.size() + " were given");
        }

        LinkedHashMap<String, PyValue> bound = new LinkedHashMap<>();
        for (int index = 0; index < positionalArguments.size(); index++) {
            bound.put(parameters.get(index), Objects.requireNonNull(
                    positionalArguments.get(index), "positional argument"));
        }
        for (Map.Entry<String, PyValue> keyword : keywordArguments.entrySet()) {
            String name = keyword.getKey();
            if (!parameters.contains(name)) {
                throw RuntimeOps.error("TypeError", function.getName()
                        + "() got an unexpected keyword argument '" + name + "'");
            }
            if (bound.containsKey(name)) {
                throw RuntimeOps.error("TypeError", function.getName()
                        + "() got multiple values for argument '" + name + "'");
            }
            bound.put(name, Objects.requireNonNull(
                    keyword.getValue(), "keyword argument"));
        }

        for (String parameter : parameters) {
            if (bound.containsKey(parameter)) {
                continue;
            }
            PyValue defaultValue = function.getDefaultValues().get(parameter);
            if (defaultValue == null) {
                throw RuntimeOps.error("TypeError", function.getName()
                        + "() missing required argument '" + parameter + "'");
            }
            bound.put(parameter, defaultValue);
        }
        return java.util.Collections.unmodifiableMap(bound);
    }
}
