package compilers.flask.codegen.analysis;

import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.runtime.RuntimeSymbolSpec;

import java.util.Objects;

/**
 * One identity-bearing occurrence of a Python name.
 *
 * <p>The key uses AST-owner identity together with role and ordinal. This is
 * stable for the lifetime of an AST and also covers names represented as
 * helper strings rather than {@code IdentifierNode}s.</p>
 */
public final class NameOccurrence {

    public enum Role {
        LOAD,
        STORE,
        DELETE,
        PARAMETER,
        FUNCTION_DEFINITION,
        CLASS_DEFINITION,
        IMPORT_BINDING,
        EXCEPT_ALIAS,
        GLOBAL_DECLARATION
    }

    /** Identity-based lookup key for an occurrence. */
    public static final class Key {
        private final ASTNode owner;
        private final Role role;
        private final int ordinal;

        public Key(ASTNode owner, Role role, int ordinal) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.role = Objects.requireNonNull(role, "role");
            if (ordinal < 0) {
                throw new IllegalArgumentException("Occurrence ordinal cannot be negative");
            }
            this.ordinal = ordinal;
        }

        public ASTNode getOwner() {
            return owner;
        }

        public Role getRole() {
            return role;
        }

        public int getOrdinal() {
            return ordinal;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key)) {
                return false;
            }
            Key that = (Key) other;
            return owner == that.owner && role == that.role && ordinal == that.ordinal;
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(owner);
            result = 31 * result + role.hashCode();
            result = 31 * result + ordinal;
            return result;
        }

        @Override
        public String toString() {
            // Deliberately omit the JVM identity hash. Identity remains part
            // of equals/hashCode, while diagnostics and deterministic output
            // receive a stable structural label.
            return owner.getNodeType() + ":" + role + "#" + ordinal;
        }
    }

    private final Key key;
    private final String name;
    private final SourceSpan span;
    private final ScopeLayout scope;
    private final ResolvedName resolvedName;
    private final RuntimeSymbolSpec runtimeSymbol;

    public NameOccurrence(
            Key key,
            String name,
            SourceSpan span,
            ScopeLayout scope,
            ResolvedName resolvedName) {
        this(key, name, span, scope, resolvedName, null);
    }

    public NameOccurrence(
            Key key,
            String name,
            SourceSpan span,
            ScopeLayout scope,
            ResolvedName resolvedName,
            RuntimeSymbolSpec runtimeSymbol) {
        this.key = Objects.requireNonNull(key, "key");
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Occurrence name cannot be empty");
        }
        this.name = name;
        this.span = span == null ? SourceSpan.UNKNOWN : span;
        this.scope = Objects.requireNonNull(scope, "scope");
        this.resolvedName = Objects.requireNonNull(resolvedName, "resolvedName");
        this.runtimeSymbol = runtimeSymbol;
    }

    public Key getKey() {
        return key;
    }

    public ASTNode getOwner() {
        return key.getOwner();
    }

    public Role getRole() {
        return key.getRole();
    }

    public int getOrdinal() {
        return key.getOrdinal();
    }

    public String getName() {
        return name;
    }

    public SourceSpan getSpan() {
        return span;
    }

    public ScopeLayout getScope() {
        return scope;
    }

    public ResolvedName getResolvedName() {
        return resolvedName;
    }

    /**
     * Runtime provider required by this exact operation, if any.
     *
     * <p>This is set for builtin-fallback loads and for known native-module
     * import bindings. A same-spelled FAST/DEREF local does not acquire a
     * dependency merely because a builtin has that name.</p>
     */
    public RuntimeSymbolSpec getRuntimeSymbol() {
        return runtimeSymbol;
    }

    public boolean hasRuntimeSymbol() {
        return runtimeSymbol != null;
    }

    @Override
    public String toString() {
        return name + " " + getRole() + " -> " + resolvedName;
    }
}
