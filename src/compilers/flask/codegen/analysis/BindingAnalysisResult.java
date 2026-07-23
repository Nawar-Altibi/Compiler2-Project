package compilers.flask.codegen.analysis;

import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.statements.ProgramNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable output of {@link BindingResolver}. */
public final class BindingAnalysisResult {

    private final ProgramNode program;
    private final ScopeLayout rootScope;
    private final List<ScopeLayout> scopes;
    private final List<NameOccurrence> occurrences;
    private final Map<ASTNode, ScopeLayout> scopeByOwner;
    private final Map<SymbolTable, ScopeLayout> scopeByTable;
    private final Map<NameOccurrence.Key, NameOccurrence> occurrenceByKey;
    private final Map<ASTNode, List<NameOccurrence>> occurrencesByOwner;

    BindingAnalysisResult(
            ProgramNode program,
            ScopeLayout rootScope,
            List<ScopeLayout> scopes,
            List<NameOccurrence> occurrences) {
        this.program = Objects.requireNonNull(program, "program");
        this.rootScope = Objects.requireNonNull(rootScope, "rootScope");
        this.scopes = Collections.unmodifiableList(new ArrayList<>(scopes));
        this.occurrences = Collections.unmodifiableList(new ArrayList<>(occurrences));

        IdentityHashMap<ASTNode, ScopeLayout> owners = new IdentityHashMap<>();
        IdentityHashMap<SymbolTable, ScopeLayout> tables = new IdentityHashMap<>();
        for (ScopeLayout scope : this.scopes) {
            if (owners.put(scope.getOwner(), scope) != null) {
                throw new IllegalArgumentException("An AST block owns more than one scope layout");
            }
            if (tables.put(scope.getSymbolTable(), scope) != null) {
                throw new IllegalArgumentException("A symbol table owns more than one scope layout");
            }
        }
        this.scopeByOwner = Collections.unmodifiableMap(owners);
        this.scopeByTable = Collections.unmodifiableMap(tables);

        Map<NameOccurrence.Key, NameOccurrence> keyed = new LinkedHashMap<>();
        IdentityHashMap<ASTNode, List<NameOccurrence>> byOwner = new IdentityHashMap<>();
        for (NameOccurrence occurrence : this.occurrences) {
            NameOccurrence previous = keyed.put(occurrence.getKey(), occurrence);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate name-occurrence identity: " + occurrence.getKey());
            }
            List<NameOccurrence> ownerOccurrences = byOwner.get(occurrence.getOwner());
            if (ownerOccurrences == null) {
                ownerOccurrences = new ArrayList<>();
                byOwner.put(occurrence.getOwner(), ownerOccurrences);
            }
            ownerOccurrences.add(occurrence);
        }
        IdentityHashMap<ASTNode, List<NameOccurrence>> frozenByOwner = new IdentityHashMap<>();
        for (Map.Entry<ASTNode, List<NameOccurrence>> entry : byOwner.entrySet()) {
            frozenByOwner.put(
                    entry.getKey(),
                    Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        this.occurrenceByKey = Collections.unmodifiableMap(keyed);
        this.occurrencesByOwner = Collections.unmodifiableMap(frozenByOwner);
    }

    public ProgramNode getProgram() {
        return program;
    }

    public ScopeLayout getRootScope() {
        return rootScope;
    }

    public List<ScopeLayout> getScopes() {
        return scopes;
    }

    public List<NameOccurrence> getOccurrences() {
        return occurrences;
    }

    public ScopeLayout getScope(ASTNode owner) {
        return scopeByOwner.get(owner);
    }

    public ScopeLayout requireScope(ASTNode owner) {
        ScopeLayout result = getScope(owner);
        if (result == null) {
            throw new IllegalArgumentException("AST node does not own an executable scope");
        }
        return result;
    }

    public ScopeLayout getScope(SymbolTable table) {
        return scopeByTable.get(table);
    }

    public NameOccurrence getOccurrence(NameOccurrence.Key key) {
        return occurrenceByKey.get(key);
    }

    public NameOccurrence getOccurrence(
            ASTNode owner, NameOccurrence.Role role, int ordinal) {
        return getOccurrence(new NameOccurrence.Key(owner, role, ordinal));
    }

    public NameOccurrence requireOccurrence(
            ASTNode owner, NameOccurrence.Role role, int ordinal) {
        NameOccurrence occurrence = getOccurrence(owner, role, ordinal);
        if (occurrence == null) {
            throw new IllegalArgumentException(
                    "Unknown occurrence " + role + "#" + ordinal
                            + " for " + owner.getNodeType());
        }
        return occurrence;
    }

    public ResolvedName getResolvedName(
            ASTNode owner, NameOccurrence.Role role, int ordinal) {
        NameOccurrence occurrence = getOccurrence(owner, role, ordinal);
        return occurrence == null ? null : occurrence.getResolvedName();
    }

    public List<NameOccurrence> getOccurrences(ASTNode owner) {
        List<NameOccurrence> result = occurrencesByOwner.get(owner);
        return result == null ? Collections.<NameOccurrence>emptyList() : result;
    }
}
