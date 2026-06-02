package compilers.html_css.SymbolTable;

public class Symbol {
    private String name;
    private SymbolType type;
    private String scope;
    private int lineNumber;

    public Symbol(String name, SymbolType type, String scope, int lineNumber) {
        this.name = name;
        this.type = type;
        this.scope = scope;
        this.lineNumber = lineNumber;
    }

    public String getName() {
        return name;
    }

    public SymbolType getType() {
        return type;
    }

    public String getScope() {
        return scope;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public String toString() {
        return String.format("%s [%s] in scope '%s' at line %d", name, type, scope, lineNumber);
    }
}

