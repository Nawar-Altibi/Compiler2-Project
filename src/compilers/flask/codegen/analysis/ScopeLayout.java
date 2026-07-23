package compilers.flask.codegen.analysis;

import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.ast.nodes.ASTNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, deterministic runtime layout for one executable code block.
 */
public final class ScopeLayout {

    public enum ScopeKind {
        MODULE,
        FUNCTION,
        CLASS
    }

    private final int ordinal;
    private final ScopeKind kind;
    private final ASTNode owner;
    private final SymbolTable symbolTable;
    private final ScopeLayout structuralParent;
    private final ScopeLayout lexicalParent;
    private final List<String> parameterNames;
    private final List<String> localNames;
    private final List<String> fastLocalNames;
    private final List<String> globalDeclarations;
    private final List<String> cellVars;
    private final List<String> freeVars;
    private final int temporarySlotCount;
    private final Map<String, Integer> fastSlots;
    private final Map<String, Integer> cellSlots;
    private final Map<String, Integer> freeSlots;
    private final Map<String, Integer> derefSlots;

    ScopeLayout(
            int ordinal,
            ScopeKind kind,
            ASTNode owner,
            SymbolTable symbolTable,
            ScopeLayout structuralParent,
            ScopeLayout lexicalParent,
            List<String> parameterNames,
            List<String> localNames,
            List<String> globalDeclarations,
            List<String> cellVars,
            List<String> freeVars,
            int temporarySlotCount) {
        if (ordinal < 0) {
            throw new IllegalArgumentException("Scope ordinal cannot be negative");
        }
        if (temporarySlotCount < 0) {
            throw new IllegalArgumentException("Temporary-slot count cannot be negative");
        }
        this.ordinal = ordinal;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.symbolTable = Objects.requireNonNull(symbolTable, "symbolTable");
        this.structuralParent = structuralParent;
        this.lexicalParent = lexicalParent;
        this.parameterNames = immutableDistinct(parameterNames, "parameter");
        this.localNames = immutableDistinct(localNames, "local");
        this.globalDeclarations = immutableDistinct(globalDeclarations, "global");
        this.cellVars = immutableDistinct(cellVars, "cell");
        this.freeVars = immutableDistinct(freeVars, "free");
        this.temporarySlotCount = temporarySlotCount;

        Set<String> localSet = new LinkedHashSet<>(this.localNames);
        if (!localSet.containsAll(this.parameterNames)) {
            throw new IllegalArgumentException("Every parameter must be a local name");
        }
        if (!localSet.containsAll(this.cellVars)) {
            throw new IllegalArgumentException("Every cell variable must be locally bound");
        }
        Set<String> overlap = new LinkedHashSet<>(this.cellVars);
        overlap.retainAll(this.freeVars);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("A name cannot be both cell and free: " + overlap);
        }

        List<String> fast = new ArrayList<>();
        Set<String> cells = new LinkedHashSet<>(this.cellVars);
        if (kind == ScopeKind.FUNCTION) {
            for (String local : this.localNames) {
                if (!cells.contains(local)) {
                    fast.add(local);
                }
            }
        }
        this.fastLocalNames = Collections.unmodifiableList(fast);
        this.fastSlots = indexByOrder(this.fastLocalNames, 0);
        this.cellSlots = indexByOrder(this.cellVars, 0);
        this.freeSlots = indexByOrder(this.freeVars, 0);

        Map<String, Integer> deref = new LinkedHashMap<>();
        int index = 0;
        for (String name : this.cellVars) {
            deref.put(name, index++);
        }
        for (String name : this.freeVars) {
            deref.put(name, index++);
        }
        this.derefSlots = Collections.unmodifiableMap(deref);
    }

    private static List<String> immutableDistinct(List<String> source, String label) {
        List<String> values = source == null
                ? Collections.<String>emptyList()
                : new ArrayList<>(source);
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException(label + " name cannot be empty");
            }
            if (!distinct.add(value)) {
                throw new IllegalArgumentException("Duplicate " + label + " name: " + value);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(distinct));
    }

    private static Map<String, Integer> indexByOrder(List<String> values, int offset) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            result.put(values.get(i), offset + i);
        }
        return Collections.unmodifiableMap(result);
    }

    public int getOrdinal() {
        return ordinal;
    }

    public ScopeKind getKind() {
        return kind;
    }

    public ASTNode getOwner() {
        return owner;
    }

    public SymbolTable getSymbolTable() {
        return symbolTable;
    }

    public ScopeLayout getStructuralParent() {
        return structuralParent;
    }

    public ScopeLayout getLexicalParent() {
        return lexicalParent;
    }

    public List<String> getParameterNames() {
        return parameterNames;
    }

    /** All names statically local to this block, including cell variables. */
    public List<String> getLocalNames() {
        return localNames;
    }

    /** Function locals represented by ordinary fast slots (cells excluded). */
    public List<String> getFastLocalNames() {
        return fastLocalNames;
    }

    public List<String> getGlobalDeclarations() {
        return globalDeclarations;
    }

    public List<String> getCellVars() {
        return cellVars;
    }

    public List<String> getFreeVars() {
        return freeVars;
    }

    public int getTemporarySlotCount() {
        return temporarySlotCount;
    }

    public boolean isLocal(String name) {
        return localNames.contains(name);
    }

    public boolean isGlobalDeclared(String name) {
        return globalDeclarations.contains(name);
    }

    public boolean isCellVar(String name) {
        return cellSlots.containsKey(name);
    }

    public boolean isFreeVar(String name) {
        return freeSlots.containsKey(name);
    }

    public int getFastSlot(String name) {
        return requiredIndex(fastSlots, name, "fast-local");
    }

    public int getCellSlot(String name) {
        return requiredIndex(cellSlots, name, "cell");
    }

    /** Index within the free-var table, not the combined deref table. */
    public int getFreeSlot(String name) {
        return requiredIndex(freeSlots, name, "free");
    }

    /** Combined cell-vars-first, free-vars-second dereference slot. */
    public int getDerefSlot(String name) {
        return requiredIndex(derefSlots, name, "deref");
    }

    private static int requiredIndex(Map<String, Integer> slots, String name, String label) {
        Integer index = slots.get(name);
        if (index == null) {
            throw new IllegalArgumentException("Unknown " + label + " name: " + name);
        }
        return index;
    }

    @Override
    public String toString() {
        return kind + "#" + ordinal + "(" + symbolTable.getScopeName() + ")";
    }
}
