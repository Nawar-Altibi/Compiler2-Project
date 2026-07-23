package compilers.diagnostics;

/** Compiler phase that produced a diagnostic. */
public enum CompilerPhase {
    LEXER("Lexer"),
    PARSER("Parser"),
    AST("AST"),
    AST_VALIDATION("AST Validation"),
    SYMBOL_TABLE("Symbol Table"),
    SEMANTIC("Semantic"),
    BINDING_RESOLUTION("Binding Resolution"),
    CODE_GENERATION("Code Generation"),
    BYTECODE_VERIFICATION("Bytecode Verification"),
    PIPELINE("Pipeline");

    private final String displayName;

    CompilerPhase(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
