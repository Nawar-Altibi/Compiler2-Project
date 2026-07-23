package compilers.flask.vm;

import compilers.flask.vm.values.PyValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable success/failure result of one isolated VM execution. */
public final class VmResult {
    private final PyValue returnValue;
    private final VmTraceback traceback;
    private final Map<String, PyValue> globals;
    private final long executedInstructionCount;
    private final String stdout;

    private VmResult(
            PyValue returnValue,
            VmTraceback traceback,
            Map<String, PyValue> globals,
            long executedInstructionCount,
            String stdout) {
        if ((returnValue == null) == (traceback == null)) {
            throw new IllegalArgumentException(
                    "VmResult must contain exactly one of return value or traceback");
        }
        if (executedInstructionCount < 0) {
            throw new IllegalArgumentException("Executed instruction count cannot be negative");
        }
        this.returnValue = returnValue;
        this.traceback = traceback;
        this.globals = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(globals, "globals")));
        this.executedInstructionCount = executedInstructionCount;
        this.stdout = Objects.requireNonNull(stdout, "stdout");
    }

    public static VmResult success(
            PyValue returnValue,
            Map<String, PyValue> globals,
            long executedInstructionCount) {
        return new VmResult(Objects.requireNonNull(returnValue, "returnValue"),
                null, globals, executedInstructionCount, "");
    }

    public static VmResult success(
            PyValue returnValue,
            Map<String, PyValue> globals,
            long executedInstructionCount,
            String stdout) {
        return new VmResult(Objects.requireNonNull(returnValue, "returnValue"),
                null, globals, executedInstructionCount, stdout);
    }

    public static VmResult failure(
            VmTraceback traceback,
            Map<String, PyValue> globals,
            long executedInstructionCount) {
        return new VmResult(null, Objects.requireNonNull(traceback, "traceback"),
                globals, executedInstructionCount, "");
    }

    public static VmResult failure(
            VmTraceback traceback,
            Map<String, PyValue> globals,
            long executedInstructionCount,
            String stdout) {
        return new VmResult(null, Objects.requireNonNull(traceback, "traceback"),
                globals, executedInstructionCount, stdout);
    }

    public boolean isSuccess() {
        return returnValue != null;
    }

    public boolean isFailure() {
        return traceback != null;
    }

    public Optional<PyValue> getReturnValueOptional() {
        return Optional.ofNullable(returnValue);
    }

    public PyValue getReturnValue() {
        if (returnValue == null) {
            throw new IllegalStateException("Failed VM result has no return value");
        }
        return returnValue;
    }

    public Optional<VmTraceback> getTracebackOptional() {
        return Optional.ofNullable(traceback);
    }

    public VmTraceback getTraceback() {
        if (traceback == null) {
            throw new IllegalStateException("Successful VM result has no traceback");
        }
        return traceback;
    }

    /** Immutable fresh module-global snapshot captured at execution end. */
    public Map<String, PyValue> getGlobals() {
        return globals;
    }

    public long getExecutedInstructionCount() {
        return executedInstructionCount;
    }

    /** Captured output written by the VM's print provider. */
    public String getStdout() {
        return stdout;
    }
}
