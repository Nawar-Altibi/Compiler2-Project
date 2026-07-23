package compilers.flask.vm.flask;

import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.values.PyDict;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** LIFO app/request context state isolated to one VM execution. */
public final class FlaskExecutionContext {
    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    private static final class AppState {
        private final PyFlaskApp app;
        private final PyNamespaceValue g = new PyNamespaceValue("flask.g");
        private AppState(PyFlaskApp app) { this.app = app; }
    }

    private static final class RequestState {
        private final PyRequest request;
        private final PyDict session;
        private RequestState(PyRequest request, PyDict session) {
            this.request = request;
            this.session = session;
        }
    }

    private final Deque<AppState> apps = new ArrayDeque<>();
    private final Deque<RequestState> requests = new ArrayDeque<>();

    public Scope enterApp(PyFlaskApp app) {
        AppState state = new AppState(Objects.requireNonNull(app, "app"));
        int requestDepth = requests.size();
        apps.addLast(state);
        return once(() -> {
            if (requests.size() != requestDepth) {
                throw new IllegalStateException(
                        "Cannot leave an app context with an active request");
            }
            if (apps.peekLast() != state) {
                throw new IllegalStateException("Flask app-context stack corruption");
            }
            apps.removeLast();
        });
    }

    public Scope enterRequest(PyRequest request, PyDict session) {
        requireApp();
        RequestState state = new RequestState(
                Objects.requireNonNull(request, "request"),
                Objects.requireNonNull(session, "session"));
        requests.addLast(state);
        return once(() -> {
            if (requests.peekLast() != state) {
                throw new IllegalStateException("Flask request-context stack corruption");
            }
            requests.removeLast();
        });
    }

    public boolean hasApp() { return !apps.isEmpty(); }
    public boolean hasRequest() { return !requests.isEmpty(); }

    public PyFlaskApp currentApp() {
        return requireApp().app;
    }

    public PyNamespaceValue currentG() {
        return requireApp().g;
    }

    public PyRequest currentRequest() {
        RequestState state = requests.peekLast();
        if (state == null) {
            throw RuntimeOps.error("RuntimeError",
                    "working outside of request context");
        }
        return state.request;
    }

    public PyDict currentSession() {
        RequestState state = requests.peekLast();
        if (state == null) {
            throw RuntimeOps.error("RuntimeError",
                    "working outside of request context");
        }
        return state.session;
    }

    private AppState requireApp() {
        AppState state = apps.peekLast();
        if (state == null) {
            throw RuntimeOps.error("RuntimeError",
                    "working outside of application context");
        }
        return state;
    }

    private static Scope once(Runnable closeAction) {
        return new Scope() {
            private boolean closed;
            @Override public void close() {
                if (!closed) {
                    closeAction.run();
                    closed = true;
                }
            }
        };
    }
}
