package compilers.flask.vm;

import compilers.flask.runtime.RuntimeProviderId;
import compilers.flask.runtime.RuntimeSymbolManifest;
import compilers.flask.runtime.RuntimeSymbolSpec;
import compilers.flask.vm.values.PyValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, provider-ID keyed runtime side of {@link RuntimeSymbolManifest}.
 *
 * <p>Registration accepts only the canonical manifest object for a symbol.
 * Materializing a module then proves exact provider parity: every executable
 * manifest entry has one value under its declared provider ID, and no provider
 * registered for that module is undeclared.</p>
 */
public final class RuntimeProviderRegistry {
    private static final class Entry {
        private final RuntimeSymbolSpec spec;
        private final PyValue provider;

        private Entry(RuntimeSymbolSpec spec, PyValue provider) {
            this.spec = spec;
            this.provider = provider;
        }
    }

    public static final class Builder {
        private final LinkedHashMap<RuntimeProviderId, Entry> entries =
                new LinkedHashMap<>();

        public Builder register(RuntimeSymbolSpec spec, PyValue provider) {
            RuntimeSymbolSpec declared = Objects.requireNonNull(spec, "spec");
            PyValue value = Objects.requireNonNull(provider, "provider");
            RuntimeSymbolSpec canonical = RuntimeSymbolManifest.lookup(
                    declared.moduleName(), declared.name());
            if (canonical != declared) {
                throw new IllegalArgumentException(
                        "Runtime provider must use the canonical manifest entry for "
                                + declared.qualifiedName());
            }
            if (!declared.isExecutable() || declared.providerId() == null) {
                throw new IllegalArgumentException(
                        "Cannot register a non-executable runtime symbol: "
                                + declared.qualifiedName());
            }
            Entry previous = entries.putIfAbsent(
                    declared.providerId(), new Entry(declared, value));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate runtime provider ID: " + declared.providerId());
            }
            return this;
        }

        public RuntimeProviderRegistry build() {
            return new RuntimeProviderRegistry(entries);
        }
    }

    private final Map<RuntimeProviderId, Entry> entries;

    private RuntimeProviderRegistry(Map<RuntimeProviderId, Entry> source) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<PyValue> find(RuntimeProviderId providerId) {
        Entry entry = entries.get(Objects.requireNonNull(providerId, "providerId"));
        return entry == null ? Optional.<PyValue>empty() : Optional.of(entry.provider);
    }

    /** Returns an exact, declaration-ordered module provider view. */
    public Map<String, PyValue> moduleProviders(String moduleName) {
        String name = Objects.requireNonNull(moduleName, "moduleName");
        Map<String, RuntimeSymbolSpec> declared =
                RuntimeSymbolManifest.moduleExports(name);
        if (declared.isEmpty()) {
            throw new IllegalArgumentException(
                    "No runtime symbol manifest module named '" + name + "'");
        }

        LinkedHashMap<String, PyValue> result = new LinkedHashMap<>();
        for (RuntimeSymbolSpec spec : declared.values()) {
            if (!spec.isExecutable() || spec.providerId() == null) {
                throw new IllegalStateException(
                        "Runtime module contains a non-executable symbol: "
                                + spec.qualifiedName());
            }
            Entry entry = entries.get(spec.providerId());
            if (entry == null || entry.spec != spec) {
                throw new IllegalStateException(
                        "No matching provider installed for " + spec.qualifiedName()
                                + " as " + spec.providerId());
            }
            result.put(spec.name(), entry.provider);
        }

        int registeredForModule = 0;
        for (Entry entry : entries.values()) {
            if (entry.spec.moduleName().equals(name)) registeredForModule++;
        }
        if (registeredForModule != result.size()) {
            throw new IllegalStateException(
                    "Provider/manifest parity failure for module '" + name + "'");
        }
        return Collections.unmodifiableMap(result);
    }

    public Namespace moduleNamespace(String moduleName) {
        return new Namespace(moduleProviders(moduleName));
    }
}
