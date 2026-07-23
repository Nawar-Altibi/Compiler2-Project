package compilers.flask.vm;

import compilers.flask.vm.values.PyBaseException;
import compilers.flask.vm.values.PyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Internal Java control signal for a Python-visible runtime failure. */
@SuppressWarnings("serial")
public final class VmRuntimeException extends RuntimeException {
    private final PyValue raisedValue;
    private final PyBaseException exceptionValue;
    private final List<VmTraceback.Entry> tracebackEntries = new ArrayList<>();
    private VmRuntimeException explicitCause;
    private VmRuntimeException implicitContext;
    private boolean suppressContext;
    /*
     * The frame which most recently let this Python exception escape through
     * the pending-transfer engine.  It lets that same frame avoid recording a
     * second source entry when dispatchPending() throws back into its opcode
     * catch, while a caller frame still records its CALL-site entry normally.
     */
    private Frame escapedTransferFrame;

    public VmRuntimeException(PyBaseException exceptionValue) {
        this(exceptionValue, exceptionValue);
    }

    public VmRuntimeException(
            PyValue raisedValue, PyBaseException exceptionValue) {
        super(Objects.requireNonNull(exceptionValue, "exceptionValue").toString(),
                null, false, false);
        this.raisedValue = Objects.requireNonNull(raisedValue, "raisedValue");
        this.exceptionValue = exceptionValue;
    }

    /** Original Python value bound by {@code except ... as name}. */
    public PyValue getRaisedValue() {
        return raisedValue;
    }

    public PyBaseException getExceptionValue() {
        return exceptionValue;
    }

    /** Records one unwinding frame, innermost first. */
    void addTracebackEntry(VmTraceback.Entry entry) {
        tracebackEntries.add(Objects.requireNonNull(entry, "traceback entry"));
    }

    List<VmTraceback.Entry> getTracebackEntries() {
        return Collections.unmodifiableList(new ArrayList<>(tracebackEntries));
    }

    public VmRuntimeException getExplicitCause() {
        return explicitCause;
    }

    public VmRuntimeException getImplicitContext() {
        return implicitContext;
    }

    public boolean isContextSuppressed() {
        return suppressContext;
    }

    void markEscapedTransferFrame(Frame frame) {
        escapedTransferFrame = Objects.requireNonNull(frame, "frame");
    }

    boolean escapedTransferFrameIs(Frame frame) {
        return escapedTransferFrame == frame;
    }

    public void setExplicitCause(VmRuntimeException cause) {
        if (cause == this) {
            throw new IllegalArgumentException("An exception cannot cause itself");
        }
        explicitCause = cause;
        suppressContext = true;
    }

    public void suppressContext() {
        explicitCause = null;
        suppressContext = true;
    }

    public void setImplicitContext(VmRuntimeException context) {
        if (!suppressContext && context != null && context != this) {
            implicitContext = context;
        }
    }

    public static VmRuntimeException of(String typeName, String message) {
        return new VmRuntimeException(new PyBaseException(typeName, message));
    }
}
