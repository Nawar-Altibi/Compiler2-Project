package compilers.flask.generation;

import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One discovered Flask route: {@code @app.route("/path", methods=[...])}
 * (or {@code @app.get}/{@code @app.post}) attached to a view function.
 *
 * <p>Plain data, per plan section 5.5. The endpoint name is the view
 * function's name, exactly like Flask's default endpoint naming.</p>
 */
public final class RouteInfo {
    private final String endpoint;
    private final String path;
    private final List<String> methods;
    private final FunctionDefNode function;

    public RouteInfo(
            String endpoint,
            String path,
            List<String> methods,
            FunctionDefNode function) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.path = Objects.requireNonNull(path, "path");
        this.methods = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(methods, "methods")));
        this.function = Objects.requireNonNull(function, "function");
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getPath() {
        return path;
    }

    public List<String> getMethods() {
        return methods;
    }

    public FunctionDefNode getFunction() {
        return function;
    }

    @Override
    public String toString() {
        return String.join(",", methods) + " " + path + " -> " + endpoint;
    }
}
