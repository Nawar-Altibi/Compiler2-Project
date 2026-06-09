package compilers.diagnostics;

public enum CompilerPhase {
    LEXER("Lexer"),
    PARSER("Parser"),
    AST("AST"),
    SYMBOL_TABLE("Symbol Table"),
    SEMANTIC("Semantic");

    private final String displayName;

    CompilerPhase(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
