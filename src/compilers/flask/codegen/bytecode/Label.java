package compilers.flask.codegen.bytecode;

import java.util.Objects;

/** Builder-owned symbolic branch target. */
public final class Label {
    private final Object ownerToken;
    private final int id;
    private final String hint;

    Label(Object ownerToken, int id, String hint) {
        this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        if (id < 0) {
            throw new IllegalArgumentException("Label id cannot be negative");
        }
        this.id = id;
        this.hint = hint == null || hint.isEmpty() ? "L" + id : hint;
    }

    boolean isOwnedBy(Object token) {
        return ownerToken == token;
    }

    public int getId() {
        return id;
    }

    public String getHint() {
        return hint;
    }

    @Override
    public String toString() {
        return hint + "#" + id;
    }
}
