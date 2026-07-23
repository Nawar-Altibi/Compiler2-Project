package compilers.flask.vm.modules;

import compilers.flask.codegen.bytecode.VerifiedBytecodeModule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable ordered registry of native and verified-bytecode modules. */
public final class ModuleRegistry {

    public enum Kind {
        NATIVE,
        VERIFIED_BYTECODE
    }

    /** Immutable description; mutable module state belongs only to a loader. */
    public static final class ModuleDefinition {
        private final String canonicalName;
        private final String packageName;
        private final Kind kind;
        private final NativeModuleInitializer nativeInitializer;
        private final VerifiedBytecodeModule bytecode;

        private ModuleDefinition(
                String canonicalName,
                String packageName,
                NativeModuleInitializer nativeInitializer,
                VerifiedBytecodeModule bytecode) {
            this.canonicalName = canonicalName;
            this.packageName = packageName;
            this.nativeInitializer = nativeInitializer;
            this.bytecode = bytecode;
            this.kind = nativeInitializer == null
                    ? Kind.VERIFIED_BYTECODE
                    : Kind.NATIVE;
            if ((nativeInitializer == null) == (bytecode == null)) {
                throw new IllegalArgumentException(
                        "Module definition must have exactly one initializer kind");
            }
        }

        public String getCanonicalName() {
            return canonicalName;
        }

        public String getPackageName() {
            return packageName;
        }

        public Kind getKind() {
            return kind;
        }

        public Optional<VerifiedBytecodeModule> getVerifiedBytecode() {
            return Optional.ofNullable(bytecode);
        }

        NativeModuleInitializer nativeInitializer() {
            return nativeInitializer;
        }

        VerifiedBytecodeModule bytecode() {
            return bytecode;
        }
    }

    public static final class Builder {
        private final LinkedHashMap<String, ModuleDefinition> definitions =
                new LinkedHashMap<>();

        public Builder registerNative(
                String canonicalName,
                NativeModuleInitializer initializer) {
            String normalized = requireCanonicalName(canonicalName);
            return registerNative(normalized, defaultPackageName(normalized), initializer);
        }

        public Builder registerNative(
                String canonicalName,
                String packageName,
                NativeModuleInitializer initializer) {
            String normalized = requireCanonicalName(canonicalName);
            String normalizedPackage = requirePackageName(packageName);
            return add(new ModuleDefinition(
                    normalized,
                    normalizedPackage,
                    Objects.requireNonNull(initializer, "initializer"),
                    null));
        }

        /** Registers a real, initially empty namespace package. */
        public Builder registerNativePackage(String canonicalName) {
            String normalized = requireCanonicalName(canonicalName);
            return registerNative(normalized, normalized, (module, loader) -> { });
        }

        public Builder registerNativePackage(
                String canonicalName,
                NativeModuleInitializer initializer) {
            String normalized = requireCanonicalName(canonicalName);
            return registerNative(normalized, normalized, initializer);
        }

        public Builder registerBytecode(
                String canonicalName,
                VerifiedBytecodeModule bytecode) {
            VerifiedBytecodeModule verified = requireVerified(bytecode);
            String normalized = requireCanonicalName(canonicalName);
            String declaredName = verified.getModule().getModuleName();
            if (!normalized.equals(declaredName)) {
                throw new IllegalArgumentException(
                        "Registered module name '" + normalized
                                + "' does not match verified bytecode module name '"
                                + declaredName + "'");
            }
            String packageName = verified.getModule().getMetadata().get("__package__");
            if (packageName == null) {
                packageName = defaultPackageName(normalized);
            }
            return registerBytecode(normalized, packageName, verified);
        }

        public Builder registerBytecode(
                String canonicalName,
                String packageName,
                VerifiedBytecodeModule bytecode) {
            String normalized = requireCanonicalName(canonicalName);
            String normalizedPackage = requirePackageName(packageName);
            VerifiedBytecodeModule verified = requireVerified(bytecode);
            if (!normalized.equals(verified.getModule().getModuleName())) {
                throw new IllegalArgumentException(
                        "Verified bytecode module name does not match registry key");
            }
            return add(new ModuleDefinition(
                    normalized, normalizedPackage, null, verified));
        }

        private Builder add(ModuleDefinition definition) {
            ModuleDefinition previous = definitions.putIfAbsent(
                    definition.getCanonicalName(), definition);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate module registration: "
                                + definition.getCanonicalName());
            }
            return this;
        }

        public ModuleRegistry build() {
            return new ModuleRegistry(definitions);
        }
    }

    private final Map<String, ModuleDefinition> definitions;

    private ModuleRegistry(Map<String, ModuleDefinition> definitions) {
        this.definitions = Collections.unmodifiableMap(
                new LinkedHashMap<>(definitions));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ModuleRegistry empty() {
        return builder().build();
    }

    public boolean contains(String canonicalName) {
        return definitions.containsKey(requireCanonicalName(canonicalName));
    }

    public Optional<ModuleDefinition> find(String canonicalName) {
        return Optional.ofNullable(definitions.get(
                requireCanonicalName(canonicalName)));
    }

    public Map<String, ModuleDefinition> definitions() {
        return definitions;
    }

    static String requireCanonicalName(String value) {
        String name = Objects.requireNonNull(value, "canonicalName");
        if (name.isEmpty() || !name.equals(name.trim())) {
            throw new IllegalArgumentException("Invalid module name: '" + name + "'");
        }
        String[] components = name.split("\\.", -1);
        for (String component : components) {
            if (!component.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid module name: '" + name + "'");
            }
        }
        return name;
    }

    static String defaultPackageName(String canonicalName) {
        int separator = canonicalName.lastIndexOf('.');
        return separator < 0 ? "" : canonicalName.substring(0, separator);
    }

    private static String requirePackageName(String value) {
        String packageName = Objects.requireNonNull(value, "packageName");
        if (packageName.isEmpty()) {
            return "";
        }
        return requireCanonicalName(packageName);
    }

    private static VerifiedBytecodeModule requireVerified(
            VerifiedBytecodeModule bytecode) {
        VerifiedBytecodeModule verified = Objects.requireNonNull(bytecode, "bytecode");
        if (!verified.hasValidVerificationStamp()) {
            throw new IllegalArgumentException(
                    "Module registry requires valid verified bytecode");
        }
        return verified;
    }
}
