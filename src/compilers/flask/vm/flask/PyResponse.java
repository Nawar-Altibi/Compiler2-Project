package compilers.flask.vm.flask;

import compilers.flask.vm.values.PyAttributeProvider;
import compilers.flask.vm.values.PyDict;
import compilers.flask.vm.values.PyInt;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyValue;

import java.util.Objects;
import java.util.Optional;

/** Deterministic teaching-runtime HTTP response. */
public final class PyResponse implements PyValue, PyAttributeProvider {
    private final PyValue body;
    private final int statusCode;
    private final PyDict headers;
    private final PyString contentType;

    public PyResponse(PyValue body, int statusCode, PyDict headers, String contentType) {
        if (statusCode < 100 || statusCode > 599) {
            throw compilers.flask.vm.RuntimeOps.error(
                    "ValueError", "invalid HTTP status code " + statusCode);
        }
        this.body = body == null ? PyNone.INSTANCE : body;
        this.statusCode = statusCode;
        this.headers = headers == null ? new PyDict() : headers;
        this.contentType = new PyString(contentType == null
                ? "text/plain; charset=utf-8" : contentType);
    }

    public PyValue getBody() { return body; }
    public int getStatusCode() { return statusCode; }
    public PyDict getHeaders() { return headers; }
    public String getContentType() { return contentType.getValue(); }

    @Override
    public Optional<PyValue> findAttribute(String name) {
        switch (name) {
            case "body":
            case "data": return Optional.of(body);
            case "status_code": return Optional.<PyValue>of(PyInt.valueOf(statusCode));
            case "headers": return Optional.<PyValue>of(headers);
            case "content_type": return Optional.<PyValue>of(contentType);
            default: return Optional.empty();
        }
    }

    @Override public String getTypeName() { return "Response"; }
    @Override public String repr() { return "<Response " + statusCode + ">"; }
    @Override public String toString() { return repr(); }
}
