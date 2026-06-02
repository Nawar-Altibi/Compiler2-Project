package compilers.html_css.SymbolTable;

import java.util.*;

public class SymbolTable {
    private Stack<Map<String, Symbol>> scopeStack;
    private Stack<String> scopeNameStack;
    private List<Symbol> allSymbols;
    private String currentScopeName;

    public SymbolTable() {
        this.scopeStack = new Stack<>();
        this.scopeNameStack = new Stack<>();
        this.allSymbols = new ArrayList<>();
        this.currentScopeName = "global";
        enterScope("global");
    }

    public void enterScope(String scopeName) {
        scopeStack.push(new HashMap<>());
        scopeNameStack.push(scopeName);
        this.currentScopeName = scopeName;
    }

    public void exitScope() {
        if (scopeStack.size() > 1) {
            scopeStack.pop();
            scopeNameStack.pop();
            this.currentScopeName = !scopeNameStack.isEmpty() ? scopeNameStack.peek() : "global";
        }
    }

    public String getCurrentScopeName() {
        return currentScopeName;
    }

    public void setCurrentScopeName(String scopeName) {
        this.currentScopeName = scopeName;
    }

    public void define(Symbol symbol) {
        if (scopeStack.isEmpty()) {
            enterScope("global");
        }
        Map<String, Symbol> currentScope = scopeStack.peek();
        symbol.setScope(currentScopeName);
        currentScope.put(symbol.getName(), symbol);
        allSymbols.add(symbol);
    }

    public String printSymbolTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("Symbol Table\n============\n\n");

        if (allSymbols.isEmpty()){
            sb.append("(Empty symbol table)\n");
            return sb.toString();
        }

        Map<String, List<Symbol>> symbolsByScope = new LinkedHashMap<>();
        for (Symbol symbol : allSymbols) {
            String scope = symbol.getScope();
            symbolsByScope.putIfAbsent(scope, new ArrayList<>());
            symbolsByScope.get(scope).add(symbol);
        }

        for (Map.Entry<String, List<Symbol>> entry : symbolsByScope.entrySet()) {
            String scopeName = entry.getKey();
            List<Symbol> symbols = entry.getValue();

            sb.append("Scope: ").append(scopeName).append("\n");
            sb.append("  ");
            for (int i = 0; i < 50; i++) sb.append("-");
            sb.append("\n");

            symbols.sort(Comparator.comparingInt(Symbol::getLineNumber));

            for (Symbol symbol : symbols) {
                sb.append(String.format("  %-20s %-18s line %-5d\n",
                        symbol.getName(),
                        "[" + symbol.getType().getDisplayName() + "]",
                        symbol.getLineNumber()));
            }

            sb.append("\n");
        }

        sb.append("Total symbols: ").append(allSymbols.size()).append("\n");
        sb.append("Total scopes: ").append(symbolsByScope.size()).append("\n");

        return sb.toString();
    }
}

