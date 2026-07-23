package compilers.flask.vm.flask;

import compilers.flask.vm.VmCallContext;
import compilers.flask.vm.values.PyValue;

import java.util.Objects;

/** Stable Flask LocalProxy-like value resolved against the active VM call. */
public final class PyContextProxy implements PyValue {
    public enum Kind { REQUEST, SESSION, G, CURRENT_APP }

    private final Kind kind;

    public PyContextProxy(Kind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public Kind getKind() { return kind; }

    public PyValue resolve(VmCallContext context) {
        FlaskExecutionContext flask = Objects.requireNonNull(
                context, "context").getRuntimeServices().getFlask();
        switch (kind) {
            case REQUEST: return flask.currentRequest();
            case SESSION: return flask.currentSession();
            case G: return flask.currentG();
            case CURRENT_APP: return flask.currentApp();
            default: throw new IllegalStateException("Unknown Flask proxy kind");
        }
    }

    @Override public String getTypeName() { return "LocalProxy"; }
    @Override public String repr() { return "<LocalProxy " + kind.name().toLowerCase() + ">"; }
    @Override public String toString() { return repr(); }
}
