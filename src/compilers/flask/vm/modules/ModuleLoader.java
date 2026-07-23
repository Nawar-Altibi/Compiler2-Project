package compilers.flask.vm.modules;

import compilers.flask.codegen.bytecode.VerifiedBytecodeModule;
import compilers.flask.vm.Namespace;
import compilers.flask.vm.RuntimeOps;
import compilers.flask.vm.values.PyIterator;
import compilers.flask.vm.values.PyModule;
import compilers.flask.vm.values.PyString;
import compilers.flask.vm.values.PyValue;

import java.util.Collections;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-execution module cache with transactional initialization semantics.
 *
 * <p>A module is cached as {@link State#IN_PROGRESS} before either native or
 * bytecode initialization starts. Recursive imports receive that same object.
 * A failed initialization removes that cache entry and any newly created
 * canonical descendants coupled to it by parent linkage. Successfully loaded
 * unrelated dependencies remain cached. The original failure is rethrown so a
 * later import can retry the failed subtree with fresh objects.</p>
 */
public final class ModuleLoader {

    public enum State {
        IN_PROGRESS,
        INITIALIZED
    }

    public enum ImportResult {
        /** Return the module named by the complete dotted import. */
        LEAF,
        /** Load the complete dotted import but return its first component. */
        TOP_LEVEL
    }

    private static final BytecodeModuleInitializer MISSING_BYTECODE_BRIDGE =
            (bytecode, module, loader) -> {
                throw RuntimeOps.error(
                        "ImportError",
                        "no bytecode module initializer is installed for '"
                                + module.getCanonicalName() + "'");
            };

    private static final class CacheEntry {
        private final PyModule module;
        private final long creationSequence;
        private State state;

        private CacheEntry(PyModule module, long creationSequence) {
            this.module = module;
            this.creationSequence = creationSequence;
            this.state = State.IN_PROGRESS;
        }
    }

    private final ModuleRegistry registry;
    private final BytecodeModuleInitializer bytecodeInitializer;
    private final LinkedHashMap<String, CacheEntry> cache = new LinkedHashMap<>();
    private final Deque<CacheEntry> initializationStack = new ArrayDeque<>();
    private long nextCreationSequence;

    public ModuleLoader(ModuleRegistry registry) {
        this(registry, MISSING_BYTECODE_BRIDGE);
    }

    public ModuleLoader(
            ModuleRegistry registry,
            BytecodeModuleInitializer bytecodeInitializer) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.bytecodeInitializer = Objects.requireNonNull(
                bytecodeInitializer, "bytecodeInitializer");
    }

    public ModuleRegistry getRegistry() {
        return registry;
    }

    /** Loads and returns the exact (leaf) module. */
    public PyModule load(String canonicalName) {
        String name = ModuleRegistry.requireCanonicalName(canonicalName);
        CacheEntry cached = cache.get(name);
        if (cached != null) {
            return cached.module;
        }

        ModuleRegistry.ModuleDefinition definition = registry.find(name)
                .orElseThrow(() -> importError("No module named '" + name + "'"));

        PyModule parent = null;
        String parentName = parentName(name);
        if (parentName != null) {
            parent = load(parentName);

            // A parent initializer may have imported this child while the
            // original load was waiting for the parent to complete.
            cached = cache.get(name);
            if (cached != null) {
                return cached.module;
            }
        }

        PyModule module = new PyModule(name, definition.getPackageName());
        CacheEntry entry = new CacheEntry(module, nextCreationSequence++);
        cache.put(name, entry);

        initializationStack.addLast(entry);
        try {
            initialize(definition, module);
            if (parent != null) {
                attachToParent(parent, childName(name), module);
            }
            entry.state = State.INITIALIZED;
            return module;
        } catch (RuntimeException | Error failure) {
            rollbackFailedEntryAndNewDescendants(name, entry);
            throw failure;
        } finally {
            if (initializationStack.peekLast() != entry) {
                throw new IllegalStateException(
                        "Module initialization stack corruption");
            }
            initializationStack.removeLast();
        }
    }

    public PyModule importModule(String canonicalName, ImportResult result) {
        Objects.requireNonNull(result, "result");
        String name = ModuleRegistry.requireCanonicalName(canonicalName);
        PyModule leaf = load(name);
        if (result == ImportResult.LEAF) {
            return leaf;
        }
        int separator = name.indexOf('.');
        if (separator < 0) {
            return leaf;
        }
        String topLevelName = name.substring(0, separator);
        CacheEntry topLevel = cache.get(topLevelName);
        if (topLevel == null) {
            throw new IllegalStateException(
                    "Dotted module loaded without its registered parent package");
        }
        return topLevel.module;
    }

    public PyModule loadTopLevel(String canonicalName) {
        return importModule(canonicalName, ImportResult.TOP_LEVEL);
    }

    /** Implements the value lookup performed by {@code IMPORT_FROM}. */
    public PyValue importFrom(PyModule module, String name) {
        Objects.requireNonNull(module, "module");
        String importedName = requireImportName(name);
        Optional<PyValue> existing = module.find(importedName);
        if (existing.isPresent()) {
            return existing.get();
        }

        String childModuleName = module.getCanonicalName() + "." + importedName;
        if (registry.contains(childModuleName)) {
            return load(childModuleName);
        }

        throw importError("cannot import name '" + importedName + "' from '"
                + module.getCanonicalName() + "'");
    }

    /** Implements module-scope {@code IMPORT_STAR}. */
    public void importStar(PyModule module, Namespace target) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(target, "target");

        Optional<PyValue> declared = module.find("__all__");
        if (declared.isPresent()) {
            PyIterator names = RuntimeOps.iter(declared.get());
            Optional<PyValue> next;
            while ((next = names.tryNext()).isPresent()) {
                PyValue value = next.get();
                if (!(value instanceof PyString)
                        || ((PyString) value).getValue().isEmpty()) {
                    throw RuntimeOps.error(
                            "TypeError", "module __all__ entries must be non-empty strings");
                }
                String name = ((PyString) value).getValue();
                PyValue exported = module.find(name).orElseThrow(() ->
                        RuntimeOps.error("AttributeError", "module '"
                                + module.getCanonicalName()
                                + "' has no attribute '" + name + "'"));
                target.put(name, exported);
            }
            return;
        }

        // Snapshot before copying so importing from the current module into
        // itself remains deterministic and cannot mutate the iteration view.
        for (Map.Entry<String, PyValue> export : module.snapshot().entrySet()) {
            if (!export.getKey().startsWith("_")) {
                target.put(export.getKey(), export.getValue());
            }
        }
    }

    public boolean isLoaded(String canonicalName) {
        return cache.containsKey(ModuleRegistry.requireCanonicalName(canonicalName));
    }

    public Optional<State> state(String canonicalName) {
        CacheEntry entry = cache.get(
                ModuleRegistry.requireCanonicalName(canonicalName));
        return entry == null ? Optional.<State>empty() : Optional.of(entry.state);
    }

    /** Immutable insertion-ordered snapshot, useful for host inspection/tests. */
    public Map<String, PyModule> cacheSnapshot() {
        LinkedHashMap<String, PyModule> result = new LinkedHashMap<>();
        for (Map.Entry<String, CacheEntry> cached : cache.entrySet()) {
            result.put(cached.getKey(), cached.getValue().module);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Removes the failed module plus descendants created while that particular
     * module object was being initialized. Descendants are coupled to their
     * parent object by attribute linkage, so retaining one would leave a retry
     * linked to the discarded parent. Successfully initialized imports outside
     * the failed module's canonical subtree remain cached, matching normal
     * import-cache semantics.
     */
    private void rollbackFailedEntryAndNewDescendants(
            String failedName,
            CacheEntry failedEntry) {
        String descendantPrefix = failedName + ".";
        java.util.Iterator<Map.Entry<String, CacheEntry>> iterator =
                cache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CacheEntry> cached = iterator.next();
            boolean failedIdentity = cached.getValue() == failedEntry;
            boolean newDescendant = cached.getKey().startsWith(descendantPrefix)
                    && cached.getValue().creationSequence
                    >= failedEntry.creationSequence;
            if (failedIdentity || newDescendant) {
                iterator.remove();
            }
        }
    }

    private void initialize(
            ModuleRegistry.ModuleDefinition definition,
            PyModule module) {
        if (definition.getKind() == ModuleRegistry.Kind.NATIVE) {
            definition.nativeInitializer().initialize(module, this);
            return;
        }
        VerifiedBytecodeModule bytecode = definition.bytecode();
        if (bytecode == null || !bytecode.hasValidVerificationStamp()) {
            throw new IllegalStateException(
                    "Registry exposed invalid verified bytecode for "
                            + definition.getCanonicalName());
        }
        bytecodeInitializer.initialize(bytecode, module, this);
    }

    private static void attachToParent(
            PyModule parent,
            String childName,
            PyModule child) {
        Optional<PyValue> previous = parent.find(childName);
        if (previous.isPresent() && previous.get() != child) {
            throw importError("cannot attach module '" + child.getCanonicalName()
                    + "': parent module '" + parent.getCanonicalName()
                    + "' already defines '" + childName + "'");
        }
        parent.put(childName, child);
    }

    private static String parentName(String canonicalName) {
        int separator = canonicalName.lastIndexOf('.');
        return separator < 0 ? null : canonicalName.substring(0, separator);
    }

    private static String childName(String canonicalName) {
        int separator = canonicalName.lastIndexOf('.');
        return separator < 0 ? canonicalName : canonicalName.substring(separator + 1);
    }

    private static String requireImportName(String value) {
        String name = Objects.requireNonNull(value, "name");
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid imported name: '" + name + "'");
        }
        return name;
    }

    private static compilers.flask.vm.VmRuntimeException importError(String message) {
        return RuntimeOps.error("ImportError", message);
    }
}
