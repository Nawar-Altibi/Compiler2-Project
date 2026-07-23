package compilers.flask.semantic;

import compilers.flask.runtime.RuntimeSymbolManifest;
import compilers.flask.runtime.RuntimeSymbolSpec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Registry for symbols exported by Flask that have a stable classification. */
public final class FlaskFrameworkSymbols {
    private static final Map<String, PythonBuiltins.SymbolInfo> EXPORTS;

    static {
        Map<String, PythonBuiltins.SymbolInfo> exports = new LinkedHashMap<>();
        for (Map.Entry<String, RuntimeSymbolSpec> entry
                : RuntimeSymbolManifest.moduleExports(
                        RuntimeSymbolManifest.FLASK_MODULE).entrySet()) {
            RuntimeSymbolSpec spec = entry.getValue();
            exports.put(entry.getKey(), symbolInfo(spec));
        }

        EXPORTS = Collections.unmodifiableMap(exports);
    }

    private FlaskFrameworkSymbols() {
    }

    public static PythonBuiltins.SymbolInfo lookup(String moduleName, String symbolName) {
        if (!RuntimeSymbolManifest.FLASK_MODULE.equals(moduleName)) {
            return null;
        }
        return EXPORTS.get(symbolName);
    }

    public static Map<String, PythonBuiltins.SymbolInfo> exports() {
        return EXPORTS;
    }

    private static PythonBuiltins.SymbolInfo symbolInfo(RuntimeSymbolSpec spec) {
        return PythonBuiltins.fromRuntimeSpec(spec);
    }
}
