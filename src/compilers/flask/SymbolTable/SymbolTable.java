package compilers.flask.SymbolTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Python symbol scope.
 *
 * <p>The structural parent is used for the scope tree and printing.  The
 * lexical parent is used for name lookup.  They deliberately differ for a
 * method: the method is structurally inside its class, but an unqualified
 * name in a Python method does not resolve through the class namespace.</p>
 */
public class SymbolTable {

    private final Map<String, SymbolEntry> symbols;
    private final Map<String, SymbolEntry> builtinSymbols;
    private final SymbolTable parent;
    private final SymbolTable lexicalParent;
    private final List<SymbolTable> children;
    private final Set<String> globalNames;
    private final String scopeName;
    private final ScopeType scopeType;
    private boolean wildcardImport;

    /** Creates the root (module/global) scope. */
    public SymbolTable() {
        this(null, null, "global", ScopeType.GLOBAL, new LinkedHashMap<>());
    }

    /**
     * Creates a child scope while preserving the original public API.
     */
    public SymbolTable(SymbolTable parent, String scopeName, ScopeType scopeType) {
        this(parent,
             defaultLexicalParent(parent, scopeType),
             scopeName,
             scopeType,
             parent == null ? new LinkedHashMap<>() : parent.builtinSymbols);
    }

    private SymbolTable(SymbolTable parent,
                        SymbolTable lexicalParent,
                        String scopeName,
                        ScopeType scopeType,
                        Map<String, SymbolEntry> builtinSymbols) {
        this.symbols = new LinkedHashMap<>();
        this.builtinSymbols = builtinSymbols;
        this.parent = parent;
        this.lexicalParent = lexicalParent;
        this.children = new ArrayList<>();
        this.globalNames = new LinkedHashSet<>();
        this.scopeName = scopeName;
        this.scopeType = scopeType;
        this.wildcardImport = false;

        if (parent != null) {
            parent.children.add(this);
        }
    }

    private static SymbolTable defaultLexicalParent(SymbolTable parent, ScopeType childType) {
        if (parent == null) {
            return null;
        }

        if (childType != ScopeType.FUNCTION && childType != ScopeType.CLASS) {
            return parent;
        }

        // Python class blocks are not lexical closures.  Methods and nested
        // classes therefore skip every enclosing class namespace, while a
        // class nested in a function still sees that function's scope.
        SymbolTable candidate = parent;
        while (candidate != null && candidate.scopeType == ScopeType.CLASS) {
            candidate = candidate.lexicalParent;
        }
        return candidate;
    }

    // ========================================
    // Symbol management
    // ========================================

    /**
     * Inserts a user-defined symbol into this scope.
     *
     * @return the new entry, or {@code null} if a user symbol with the same
     *         name already exists locally
     */
    public SymbolEntry insert(String name, SymbolEntry.SymbolKind kind) {
        if (symbols.containsKey(name)) {
            return null;
        }

        SymbolEntry entry = new SymbolEntry(name, kind);
        symbols.put(name, entry);
        entry.setDefined(true);
        return entry;
    }

    /** Inserts a prepared user-defined entry. */
    public boolean insert(SymbolEntry entry) {
        if (symbols.containsKey(entry.getName())) {
            return false;
        }
        symbols.put(entry.getName(), entry);
        entry.setDefined(true);
        return true;
    }

    /**
     * Registers a Python builtin outside the module's user namespace.
     * Keeping builtins separate is what makes legal shadowing such as
     * {@code print = logger.info} work correctly.
     */
    public SymbolEntry insertBuiltin(String name, SymbolEntry.SymbolKind kind) {
        SymbolEntry existing = builtinSymbols.get(name);
        if (existing != null) {
            return existing;
        }

        SymbolEntry entry = new SymbolEntry(name, kind);
        entry.setDefined(true);
        entry.setAttribute("builtin", true);
        builtinSymbols.put(name, entry);
        return entry;
    }

    /** Looks only in the user namespace of this scope. */
    public SymbolEntry lookupLocal(String name) {
        return symbols.get(name);
    }

    /** Looks up a name using Python lexical rules, then falls back to builtins. */
    public SymbolEntry lookup(String name) {
        SymbolEntry entry = lookupUserSymbol(name);
        if (entry != null) {
            return entry;
        }
        return getRootScope().builtinSymbols.get(name);
    }

    private SymbolEntry lookupUserSymbol(String name) {
        if (scopeType != ScopeType.GLOBAL && globalNames.contains(name)) {
            return getRootScope().symbols.get(name);
        }

        SymbolEntry entry = symbols.get(name);
        if (entry != null) {
            return entry;
        }

        return lexicalParent == null ? null : lexicalParent.lookupUserSymbol(name);
    }

    public SymbolEntry lookupBuiltin(String name) {
        return getRootScope().builtinSymbols.get(name);
    }

    public boolean isBuiltin(String name) {
        return lookupBuiltin(name) != null;
    }

    public boolean containsLocal(String name) {
        return symbols.containsKey(name);
    }

    public boolean contains(String name) {
        return lookup(name) != null;
    }

    /** Records a wildcard import whose exported names cannot be known here. */
    public void markWildcardImport() {
        setWildcardImport(true);
    }

    public boolean hasWildcardImport() {
        return wildcardImport;
    }

    public void setWildcardImport(boolean wildcardImport) {
        this.wildcardImport = wildcardImport;
    }

    /**
     * Returns whether a failed lookup may be satisfied by a visible
     * {@code from module import *}.  This prevents false undefined-name
     * diagnostics without inventing symbols that are not statically known.
     */
    public boolean hasVisibleWildcardImport() {
        if (wildcardImport) {
            return true;
        }
        return lexicalParent != null && lexicalParent.hasVisibleWildcardImport();
    }

    // ========================================
    // Scope management
    // ========================================

    public SymbolTable enterScope(String scopeName, ScopeType scopeType) {
        return new SymbolTable(this, scopeName, scopeType);
    }

    /** Returns the structural parent, preserving the original API. */
    public SymbolTable exitScope() {
        return parent;
    }

    public SymbolTable getRootScope() {
        SymbolTable current = this;
        while (current.parent != null) {
            current = current.parent;
        }
        return current;
    }

    /** Declares that bindings of {@code name} in this code block are global. */
    public void declareGlobal(String name) {
        if (name != null && !name.isEmpty()) {
            globalNames.add(name);
        }
    }

    public boolean isGlobalDeclared(String name) {
        return globalNames.contains(name);
    }

    /** Returns the scope in which a store to {@code name} must be recorded. */
    public SymbolTable getBindingScope(String name) {
        if (scopeType != ScopeType.GLOBAL && globalNames.contains(name)) {
            return getRootScope();
        }
        return this;
    }

    // ========================================
    // Getters
    // ========================================

    /** Structural parent used by the scope tree. */
    public SymbolTable getParent() {
        return parent;
    }

    /** Parent used by unqualified-name lookup. */
    public SymbolTable getLexicalParent() {
        return lexicalParent;
    }

    public List<SymbolTable> getChildren() {
        return Collections.unmodifiableList(new ArrayList<>(children));
    }

    public Set<String> getGlobalNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(globalNames));
    }

    public String getScopeName() {
        return scopeName;
    }

    public ScopeType getScopeType() {
        return scopeType;
    }

    public Map<String, SymbolEntry> getSymbols() {
        return new LinkedHashMap<>(symbols);
    }

    public Collection<SymbolEntry> getAllSymbols() {
        return new ArrayList<>(symbols.values());
    }

    public int getSymbolCount() {
        return symbols.size();
    }

    // ========================================
    // Utility methods
    // ========================================

    public void print() {
        print(System.out);
    }

    public void print(java.io.PrintStream output) {
        java.util.Objects.requireNonNull(output, "output");
        output.print(format());
    }

    public String format() {
        StringBuilder output = new StringBuilder();
        appendFormatted(output, 0);
        return output.toString();
    }

    private void appendFormatted(StringBuilder output, int indent) {
        StringBuilder indentBuilder = new StringBuilder();
        for (int i = 0; i < indent; i++) {
            indentBuilder.append("  ");
        }
        String indentStr = indentBuilder.toString();

        output.append(indentStr).append("Scope: ").append(scopeName)
                .append(" (").append(scopeType).append(")\n");

        if (symbols.isEmpty()) {
            output.append(indentStr).append("  (empty)\n");
        } else {
            for (SymbolEntry entry : symbols.values()) {
                output.append(indentStr).append("  ").append(entry).append('\n');
            }
        }

        for (SymbolTable child : children) {
            child.appendFormatted(output, indent + 1);
        }
    }

    public int getDepth() {
        int depth = 0;
        SymbolTable current = this;
        while (current.parent != null) {
            depth++;
            current = current.parent;
        }
        return depth;
    }

    /** Clears only the user symbols in this scope (the original behaviour). */
    public void clear() {
        symbols.clear();
    }

    public enum ScopeType {
        GLOBAL,
        FUNCTION,
        CLASS,
        MODULE
    }
}
