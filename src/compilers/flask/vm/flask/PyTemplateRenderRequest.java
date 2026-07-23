package compilers.flask.vm.flask;

import compilers.flask.vm.values.PyAttributeProvider;
import compilers.flask.vm.values.PyDict;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyValue;

import java.util.Optional;

/** Boundary value handed to the independent HTML/Jinja side. */
public final class PyTemplateRenderRequest implements PyValue, PyAttributeProvider {
    private final PyString templateName;
    private final PyDict context;

    public PyTemplateRenderRequest(String templateName, PyDict context) {
        if (templateName == null || templateName.isEmpty()) {
            throw compilers.flask.vm.RuntimeOps.error(
                    "ValueError", "template name cannot be empty");
        }
        this.templateName = new PyString(templateName);
        this.context = context == null ? new PyDict() : context;
    }

    @Override
    public Optional<PyValue> findAttribute(String name) {
        if ("template_name".equals(name) || "name".equals(name)) {
            return Optional.<PyValue>of(templateName);
        }
        if ("context".equals(name)) {
            return Optional.<PyValue>of(context);
        }
        return Optional.empty();
    }

    @Override public String getTypeName() { return "TemplateRenderRequest"; }
    @Override public String repr() { return "<TemplateRenderRequest " + templateName.repr() + ">"; }
    @Override public String toString() { return repr(); }
}
