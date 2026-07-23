package compilers.flask.runtime;

import compilers.flask.SymbolTable.SymbolEntry;
import compilers.flask.SymbolTable.SymbolType;

import java.util.Objects;

/** Immutable semantic/runtime contract for one builtin or native-module symbol. */
public final class RuntimeSymbolSpec {
    public enum SupportStatus {
        PLANNED,
        EXECUTABLE
    }

    public enum Capability {
        NONE,
        FILESYSTEM_READ,
        REQUEST_CONTEXT,
        APP_CONTEXT
    }

    private final String moduleName;
    private final String name;
    private final SymbolEntry.SymbolKind kind;
    private final SymbolType type;
    private final SupportStatus supportStatus;
    private final RuntimeProviderId providerId;
    private final Capability requiredCapability;

    private RuntimeSymbolSpec(
            String moduleName,
            String name,
            SymbolEntry.SymbolKind kind,
            SymbolType type,
            SupportStatus supportStatus,
            RuntimeProviderId providerId,
            Capability requiredCapability) {
        this.moduleName = requireName(moduleName, "moduleName");
        this.name = requireName(name, "name");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.type = Objects.requireNonNull(type, "type");
        this.supportStatus = Objects.requireNonNull(supportStatus, "supportStatus");
        this.providerId = providerId;
        this.requiredCapability = Objects.requireNonNull(requiredCapability, "requiredCapability");

        if (supportStatus == SupportStatus.EXECUTABLE && providerId == null) {
            throw new IllegalArgumentException(
                    "Executable runtime symbol requires a provider: " + qualifiedName());
        }
    }

    public static RuntimeSymbolSpec planned(
            String moduleName,
            String name,
            SymbolEntry.SymbolKind kind,
            SymbolType type,
            Capability requiredCapability) {
        return new RuntimeSymbolSpec(
                moduleName,
                name,
                kind,
                type,
                SupportStatus.PLANNED,
                null,
                requiredCapability);
    }

    public static RuntimeSymbolSpec executable(
            String moduleName,
            String name,
            SymbolEntry.SymbolKind kind,
            SymbolType type,
            RuntimeProviderId providerId,
            Capability requiredCapability) {
        return new RuntimeSymbolSpec(
                moduleName,
                name,
                kind,
                type,
                SupportStatus.EXECUTABLE,
                Objects.requireNonNull(providerId, "providerId"),
                requiredCapability);
    }

    private static String requireName(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " cannot be empty");
        }
        return normalized;
    }

    public String moduleName() {
        return moduleName;
    }

    public String name() {
        return name;
    }

    public SymbolEntry.SymbolKind kind() {
        return kind;
    }

    public SymbolType type() {
        return type;
    }

    public SupportStatus supportStatus() {
        return supportStatus;
    }

    public RuntimeProviderId providerId() {
        return providerId;
    }

    public Capability requiredCapability() {
        return requiredCapability;
    }

    public boolean isExecutable() {
        return supportStatus == SupportStatus.EXECUTABLE;
    }

    public String qualifiedName() {
        return moduleName + "." + name;
    }

    @Override
    public String toString() {
        return qualifiedName() + " [" + supportStatus + "]";
    }
}
