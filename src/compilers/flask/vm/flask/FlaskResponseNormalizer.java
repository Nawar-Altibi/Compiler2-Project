package compilers.flask.vm.flask;

import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.values.PyBool;
import compilers.flask.vm.values.PyDict;
import compilers.flask.vm.values.PyInt;
import compilers.flask.vm.values.PyNone;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyTuple;
import compilers.flask.vm.values.PyValue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Shared Flask route-result normalization and deterministic host formatting. */
public final class FlaskResponseNormalizer {
    private FlaskResponseNormalizer() {
    }

    /**
     * Normalizes the teaching runtime's supported Flask return shapes:
     * a response, a body value, {@code (body, status|headers)}, or
     * {@code (body, status, headers)}.
     */
    public static PyResponse normalize(PyValue value) {
        if (value == null) {
            throw new NullPointerException("route result");
        }
        if (value instanceof PyResponse) {
            return (PyResponse) value;
        }
        if (!(value instanceof PyTuple)) {
            return new PyResponse(value, 200, new PyDict(), defaultContentType(value));
        }

        List<PyValue> elements = ((PyTuple) value).getElements();
        if (elements.size() < 2 || elements.size() > 3) {
            throw RuntimeOps.error("TypeError",
                    "response tuple must contain 2 or 3 elements");
        }

        PyValue bodyValue = elements.get(0);
        PyResponse base = bodyValue instanceof PyResponse
                ? (PyResponse) bodyValue : null;
        PyValue body = base == null ? bodyValue : base.getBody();
        int status = base == null ? 200 : base.getStatusCode();
        PyDict headers = base == null ? new PyDict() : base.getHeaders();
        String contentType = base == null
                ? defaultContentType(body) : base.getContentType();

        if (elements.size() == 2 && elements.get(1) instanceof PyDict) {
            headers = (PyDict) elements.get(1);
        } else {
            status = status(elements.get(1));
            if (elements.size() == 3) {
                if (!(elements.get(2) instanceof PyDict)) {
                    throw RuntimeOps.error("TypeError", "response headers must be dict");
                }
                headers = (PyDict) elements.get(2);
            }
        }
        return new PyResponse(body, status, headers, contentType);
    }

    /**
     * Formats a normalized response without locale, platform line-ending, or
     * dictionary-order dependencies. Header names are sorted by their text.
     */
    public static String format(PyResponse response) {
        if (response == null) throw new NullPointerException("response");
        StringBuilder result = new StringBuilder();
        result.append("HTTP ").append(response.getStatusCode()).append('\n');
        result.append("Content-Type: ").append(response.getContentType()).append('\n');

        List<PyDict.Entry> headers = new ArrayList<>(response.getHeaders().getEntries());
        headers.sort(Comparator
                .comparing((PyDict.Entry entry) -> entry.getKey().str())
                .thenComparing(entry -> entry.getValue().str()));
        for (PyDict.Entry header : headers) {
            if ("content-type".equalsIgnoreCase(header.getKey().str())) continue;
            result.append(header.getKey().str()).append(": ")
                    .append(header.getValue().str()).append('\n');
        }
        result.append('\n');
        PyValue body = response.getBody();
        if (body != PyNone.INSTANCE) result.append(body.str());
        return result.toString();
    }

    private static int status(PyValue value) {
        BigInteger number;
        if (value instanceof PyInt) {
            number = ((PyInt) value).getValue();
        } else if (value instanceof PyBool) {
            number = ((PyBool) value).getValue() ? BigInteger.ONE : BigInteger.ZERO;
        } else {
            throw RuntimeOps.error("TypeError", "response status must be int");
        }
        try {
            return number.intValueExact();
        } catch (ArithmeticException overflow) {
            throw RuntimeOps.error("OverflowError", "response status is too large");
        }
    }

    private static String defaultContentType(PyValue body) {
        if (body instanceof PyTemplateRenderRequest) {
            return "text/html; charset=utf-8";
        }
        if (body instanceof PyString || body == PyNone.INSTANCE) {
            return "text/plain; charset=utf-8";
        }
        return "text/plain; charset=utf-8";
    }
}
