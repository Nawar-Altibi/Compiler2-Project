package compilers.flask.runtime;

import compilers.flask.SymbolTable.SymbolEntry;
import compilers.flask.SymbolTable.SymbolType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical ordered registry shared by semantic analysis and the VM.
 * Every advertised Part-3 symbol has a concrete, behavior-tested provider.
 */
public final class RuntimeSymbolManifest {
    public static final String BUILTINS_MODULE = "builtins";
    public static final String FLASK_MODULE = "flask";

    private static final Map<String, Map<String, RuntimeSymbolSpec>> MODULES;

    static {
        Map<String, Map<String, RuntimeSymbolSpec>> modules = new LinkedHashMap<>();
        modules.put(BUILTINS_MODULE, buildBuiltins());
        modules.put(FLASK_MODULE, buildFlaskExports());
        MODULES = immutableNestedCopy(modules);
    }

    private RuntimeSymbolManifest() {
    }

    public static RuntimeSymbolSpec lookup(String moduleName, String symbolName) {
        Map<String, RuntimeSymbolSpec> symbols = MODULES.get(moduleName);
        return symbols == null ? null : symbols.get(symbolName);
    }

    public static Map<String, RuntimeSymbolSpec> builtins() {
        return moduleExports(BUILTINS_MODULE);
    }

    public static Map<String, RuntimeSymbolSpec> moduleExports(String moduleName) {
        Map<String, RuntimeSymbolSpec> symbols = MODULES.get(moduleName);
        return symbols == null ? Collections.<String, RuntimeSymbolSpec>emptyMap() : symbols;
    }

    public static Map<String, Map<String, RuntimeSymbolSpec>> modules() {
        return MODULES;
    }

    private static Map<String, RuntimeSymbolSpec> buildBuiltins() {
        Map<String, RuntimeSymbolSpec> symbols = new LinkedHashMap<>();

        registerFunctions(symbols, RuntimeSymbolSpec.Capability.NONE,
                "print", "len", "range", "enumerate", "zip", "map", "filter",
                "sorted", "reversed", "sum", "min", "max", "abs", "round");
        registerFunctions(symbols, RuntimeSymbolSpec.Capability.FILESYSTEM_READ, "open");
        registerFunctions(symbols, RuntimeSymbolSpec.Capability.NONE,
                "isinstance", "issubclass", "type", "hasattr", "getattr", "setattr",
                "delattr", "repr", "format", "any", "all", "next", "iter", "id",
                "ord", "chr", "callable", "hash", "globals", "locals", "pow", "divmod");

        registerClasses(symbols,
                "str", "int", "float", "bool", "list", "dict", "set", "tuple", "object");

        registerClasses(symbols,
                "BaseException", "Exception", "ArithmeticError", "AssertionError",
                "AttributeError", "ImportError", "LookupError", "IndexError", "KeyError",
                "NameError", "UnboundLocalError", "OSError", "IOError", "FileNotFoundError",
                "PermissionError", "RuntimeError", "StopIteration", "TypeError",
                "ValueError", "ZeroDivisionError");

        return Collections.unmodifiableMap(symbols);
    }

    private static Map<String, RuntimeSymbolSpec> buildFlaskExports() {
        Map<String, RuntimeSymbolSpec> symbols = new LinkedHashMap<>();

        register(symbols, FLASK_MODULE, "Flask",
                SymbolEntry.SymbolKind.CLASS, SymbolType.CLASS,
                RuntimeSymbolSpec.Capability.NONE);
        register(symbols, FLASK_MODULE, "Blueprint",
                SymbolEntry.SymbolKind.CLASS, SymbolType.CLASS,
                RuntimeSymbolSpec.Capability.NONE);
        register(symbols, FLASK_MODULE, "Response",
                SymbolEntry.SymbolKind.CLASS, SymbolType.CLASS,
                RuntimeSymbolSpec.Capability.NONE);

        registerFlaskFunctions(symbols, RuntimeSymbolSpec.Capability.NONE,
                "render_template", "redirect", "jsonify", "abort", "make_response");
        registerFlaskFunctions(symbols, RuntimeSymbolSpec.Capability.APP_CONTEXT,
                "url_for");
        registerFlaskFunctions(symbols, RuntimeSymbolSpec.Capability.REQUEST_CONTEXT,
                "flash");
        registerFlaskFunctions(symbols, RuntimeSymbolSpec.Capability.FILESYSTEM_READ,
                "send_file", "send_from_directory");

        registerFlaskVariable(symbols, "request", RuntimeSymbolSpec.Capability.REQUEST_CONTEXT);
        registerFlaskVariable(symbols, "session", RuntimeSymbolSpec.Capability.REQUEST_CONTEXT);
        registerFlaskVariable(symbols, "g", RuntimeSymbolSpec.Capability.APP_CONTEXT);
        registerFlaskVariable(symbols, "current_app", RuntimeSymbolSpec.Capability.APP_CONTEXT);

        return Collections.unmodifiableMap(symbols);
    }

    private static void registerFunctions(
            Map<String, RuntimeSymbolSpec> symbols,
            RuntimeSymbolSpec.Capability capability,
            String... names) {
        for (String name : names) {
            register(symbols, BUILTINS_MODULE, name,
                    SymbolEntry.SymbolKind.FUNCTION, SymbolType.FUNCTION, capability);
        }
    }

    private static void registerClasses(Map<String, RuntimeSymbolSpec> symbols, String... names) {
        for (String name : names) {
            register(symbols, BUILTINS_MODULE, name,
                    SymbolEntry.SymbolKind.CLASS, SymbolType.CLASS,
                    RuntimeSymbolSpec.Capability.NONE);
        }
    }

    private static void registerFlaskFunctions(
            Map<String, RuntimeSymbolSpec> symbols,
            RuntimeSymbolSpec.Capability capability,
            String... names) {
        for (String name : names) {
            register(symbols, FLASK_MODULE, name,
                    SymbolEntry.SymbolKind.FUNCTION, SymbolType.FUNCTION, capability);
        }
    }

    private static void registerFlaskVariable(
            Map<String, RuntimeSymbolSpec> symbols,
            String name,
            RuntimeSymbolSpec.Capability capability) {
        register(symbols, FLASK_MODULE, name,
                SymbolEntry.SymbolKind.VARIABLE, SymbolType.UNKNOWN, capability);
    }

    private static void register(
            Map<String, RuntimeSymbolSpec> symbols,
            String moduleName,
            String name,
            SymbolEntry.SymbolKind kind,
            SymbolType type,
            RuntimeSymbolSpec.Capability capability) {
        RuntimeSymbolSpec previous = symbols.put(name,
                RuntimeSymbolSpec.executable(
                        moduleName,
                        name,
                        kind,
                        type,
                        RuntimeProviderId.of(moduleName + "." + name),
                        capability));
        if (previous != null) {
            throw new IllegalStateException("Duplicate runtime symbol: " + moduleName + "." + name);
        }
    }

    private static Map<String, Map<String, RuntimeSymbolSpec>> immutableNestedCopy(
            Map<String, Map<String, RuntimeSymbolSpec>> modules) {
        Map<String, Map<String, RuntimeSymbolSpec>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, RuntimeSymbolSpec>> entry : modules.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(
                    new LinkedHashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }
}
