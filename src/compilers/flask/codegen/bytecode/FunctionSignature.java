package compilers.flask.codegen.bytecode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Frozen function-parameter contract; empty for module and class-body code. */
public final class FunctionSignature {
    public static final FunctionSignature EMPTY =
            new FunctionSignature(Collections.<String>emptyList(),
                    Collections.<String>emptyList());

    private final List<String> parameterNames;
    private final List<String> defaultedParameterNames;

    public FunctionSignature(
            List<String> parameterNames, List<String> defaultedParameterNames) {
        this.parameterNames = immutableDistinct(parameterNames, "parameter");
        this.defaultedParameterNames = immutableDistinct(
                defaultedParameterNames, "defaulted parameter");
        if (!this.parameterNames.containsAll(this.defaultedParameterNames)) {
            throw new IllegalArgumentException(
                    "Every defaulted parameter must appear in the parameter table");
        }
        int defaultStart = this.parameterNames.size()
                - this.defaultedParameterNames.size();
        if (!this.parameterNames.subList(
                defaultStart, this.parameterNames.size())
                .equals(this.defaultedParameterNames)) {
            throw new IllegalArgumentException(
                    "Defaulted parameters must be the trailing parameter suffix");
        }
    }

    public List<String> getParameterNames() {
        return parameterNames;
    }

    public List<String> getDefaultedParameterNames() {
        return defaultedParameterNames;
    }

    public int getTotalParameterCount() {
        return parameterNames.size();
    }

    public int getRequiredParameterCount() {
        return parameterNames.size() - defaultedParameterNames.size();
    }

    private static List<String> immutableDistinct(List<String> source, String label) {
        Objects.requireNonNull(source, label + " names");
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String name : source) {
            if (name == null || name.isEmpty() || !distinct.add(name)) {
                throw new IllegalArgumentException("Invalid or duplicate " + label + ": " + name);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(distinct));
    }
}
