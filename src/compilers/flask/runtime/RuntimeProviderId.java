package compilers.flask.runtime;

import java.util.Objects;

/** Stable identifier for a concrete VM/native runtime provider. */
public final class RuntimeProviderId {
    private final String value;

    private RuntimeProviderId(String value) {
        this.value = value;
    }

    public static RuntimeProviderId of(String value) {
        String normalized = Objects.requireNonNull(value, "value").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Runtime provider id cannot be empty");
        }
        return new RuntimeProviderId(normalized);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RuntimeProviderId)) {
            return false;
        }
        RuntimeProviderId that = (RuntimeProviderId) other;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
