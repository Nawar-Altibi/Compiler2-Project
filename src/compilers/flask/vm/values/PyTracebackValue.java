package compilers.flask.vm.values;

import compilers.flask.vm.VmTraceback;

import java.util.Objects;

/** Opaque Python-visible traceback argument supplied to context managers. */
public final class PyTracebackValue implements PyValue {
    private final VmTraceback traceback;

    public PyTracebackValue(VmTraceback traceback) {
        this.traceback = Objects.requireNonNull(traceback, "traceback");
    }

    public VmTraceback getTraceback() { return traceback; }

    @Override public String getTypeName() { return "traceback"; }
    @Override public String repr() { return "<traceback object>"; }
    @Override public String toString() { return repr(); }
}
