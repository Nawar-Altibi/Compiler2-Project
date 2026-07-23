package compilers.flask.generation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One captured {@code render_template(templateName, **context)} call:
 * the unit of work handed to the Jinja renderer.
 *
 * <p>The context is plain Java data ({@code String}, {@code BigInteger},
 * {@code Double}, {@code Boolean}, {@code null}, {@code List},
 * {@code Map}) — the frozen cross-half contract of plan section 6.4.</p>
 */
public final class RenderJob {
    private final String templateName;
    private final Map<String, Object> context;
    private final String endpoint;
    private final int line;
    private final int column;

    public RenderJob(
            String templateName,
            Map<String, Object> context,
            String endpoint,
            int line,
            int column) {
        this.templateName = Objects.requireNonNull(templateName, "templateName");
        this.context = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(context, "context")));
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.line = line;
        this.column = column;
    }

    public String getTemplateName() {
        return templateName;
    }

    /** Insertion-ordered, unmodifiable view of the render context. */
    public Map<String, Object> getContext() {
        return context;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    /** Output page name: {@code index.jinja} / {@code index.html} → {@code index.html}. */
    public String getOutputFileName() {
        String base = templateName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base + ".html";
    }

    @Override
    public String toString() {
        return "render \"" + templateName + "\" context=" + context.keySet()
                + " (endpoint " + endpoint + ")";
    }
}
