package compilers.flask.generation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The complete result of context extraction over one {@code app.py}:
 * evaluated module globals, the discovered route map, and every captured
 * {@link RenderJob} — plus the ordered extraction log lines consumed later
 * by {@code generation_log.txt} (plan sections 5.5 and 7.3).
 */
public final class ProjectContext {
    private final Map<String, Object> globals;
    private final Map<String, RouteInfo> routes;
    private final List<RenderJob> renderJobs;
    private final List<String> logLines;

    public ProjectContext(
            Map<String, Object> globals,
            Map<String, RouteInfo> routes,
            List<RenderJob> renderJobs,
            List<String> logLines) {
        this.globals = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(globals, "globals")));
        this.routes = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(routes, "routes")));
        this.renderJobs = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(renderJobs, "renderJobs")));
        this.logLines = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(logLines, "logLines")));
    }

    /** Evaluated module-level data (markers filtered out), insertion-ordered. */
    public Map<String, Object> getGlobals() {
        return globals;
    }

    /** endpoint name → route, in declaration order. */
    public Map<String, RouteInfo> getRoutes() {
        return routes;
    }

    public List<RenderJob> getRenderJobs() {
        return renderJobs;
    }

    /** Ordered extraction trace lines ({@code [extract]} / {@code [warn]} …). */
    public List<String> getLogLines() {
        return logLines;
    }

    /**
     * A symbolic {@code url_for(endpoint, **params)} value produced while
     * evaluating Python code (plan section 5.2: url_for stays symbolic in
     * extraction). The pipeline resolves these to plain string hrefs before
     * contexts cross the plain-Java boundary to the renderer.
     */
    public static final class UrlRef {
        private final String endpoint;
        private final Map<String, Object> params;

        public UrlRef(String endpoint, Map<String, Object> params) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            this.params = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(params, "params")));
        }

        public String getEndpoint() {
            return endpoint;
        }

        public Map<String, Object> getParams() {
            return params;
        }

        @Override
        public String toString() {
            return "url_for('" + endpoint + "')";
        }
    }
}
