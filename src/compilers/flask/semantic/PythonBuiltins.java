package compilers.flask.semantic;

import compilers.flask.SymbolTable.SymbolEntry;
import compilers.flask.SymbolTable.SymbolType;
import compilers.flask.runtime.RuntimeProviderId;
import compilers.flask.runtime.RuntimeSymbolManifest;
import compilers.flask.runtime.RuntimeSymbolSpec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Data-driven registry of names supplied by Python itself. */
public final class PythonBuiltins {
    private static final Map<String, SymbolInfo> SYMBOLS;

    static {
        Map<String, SymbolInfo> symbols = new LinkedHashMap<>();
        for (Map.Entry<String, RuntimeSymbolSpec> entry
                : RuntimeSymbolManifest.builtins().entrySet()) {
            symbols.put(entry.getKey(), new SymbolInfo(entry.getValue()));
        }

        SYMBOLS = Collections.unmodifiableMap(symbols);
    }

    private PythonBuiltins() {
    }

    public static boolean contains(String name) {
        return SYMBOLS.containsKey(name);
    }

    public static SymbolInfo lookup(String name) {
        return SYMBOLS.get(name);
    }

    public static Map<String, SymbolInfo> symbols() {
        return SYMBOLS;
    }

    static SymbolInfo fromRuntimeSpec(RuntimeSymbolSpec spec) {
        return new SymbolInfo(spec);
    }

    /** Immutable symbol classification used by the symbol-table builder. */
    public static final class SymbolInfo {
        private final SymbolEntry.SymbolKind kind;
        private final SymbolType type;
        private final RuntimeSymbolSpec.SupportStatus supportStatus;
        private final RuntimeProviderId providerId;
        private final RuntimeSymbolSpec.Capability requiredCapability;

        private SymbolInfo(RuntimeSymbolSpec spec) {
            this.kind = spec.kind();
            this.type = spec.type();
            this.supportStatus = spec.supportStatus();
            this.providerId = spec.providerId();
            this.requiredCapability = spec.requiredCapability();
        }

        public SymbolEntry.SymbolKind getKind() {
            return kind;
        }

        public SymbolType getType() {
            return type;
        }

        public RuntimeSymbolSpec.SupportStatus getSupportStatus() {
            return supportStatus;
        }

        public RuntimeProviderId getProviderId() {
            return providerId;
        }

        public RuntimeSymbolSpec.Capability getRequiredCapability() {
            return requiredCapability;
        }

        public boolean isExecutable() {
            return supportStatus == RuntimeSymbolSpec.SupportStatus.EXECUTABLE;
        }
    }
}
