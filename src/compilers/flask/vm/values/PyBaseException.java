package compilers.flask.vm.values;

import java.util.Objects;

/** Basic Python-visible exception value used by the core VM. */
public final class PyBaseException implements PyValue {
    private final String exceptionTypeName;
    private final String message;

    public PyBaseException(String exceptionTypeName, String message) {
        if (exceptionTypeName == null || exceptionTypeName.isEmpty()) {
            throw new IllegalArgumentException("Exception type name cannot be empty");
        }
        this.exceptionTypeName = exceptionTypeName;
        this.message = Objects.requireNonNull(message, "message");
    }

    public String getExceptionTypeName() {
        return exceptionTypeName;
    }

    public String getMessageText() {
        return message;
    }

    @Override
    public String getTypeName() {
        return exceptionTypeName;
    }

    @Override
    public String repr() {
        if (message.isEmpty()) {
            return exceptionTypeName + "()";
        }
        return exceptionTypeName + "(" + new PyString(message).repr() + ")";
    }

    @Override
    public String str() {
        return message;
    }

    @Override
    public String toString() {
        return message.isEmpty() ? exceptionTypeName : exceptionTypeName + ": " + message;
    }
}
