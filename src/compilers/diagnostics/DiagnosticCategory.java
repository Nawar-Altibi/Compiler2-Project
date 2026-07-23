package compilers.diagnostics;

/** Single registry of diagnostic categories and their display labels. */
public enum DiagnosticCategory {
    UNDEFINED_VARIABLE("Undefined Variable"),
    SCOPE_ERROR("Scope Error"),

    TYPE_ERROR("Type Error"),
    TYPE_MISMATCH("Type Mismatch"),

    FUNCTION_CALL_ERROR("Function Call Error"),
    DUPLICATE_SYMBOL("Duplicate Symbol"),

    INVALID_AST_STRUCTURE("Invalid AST Structure"),
    INVALID_CONTROL_FLOW("Invalid Control Flow"),
    INVALID_ASSIGNMENT_TARGET("Invalid Assignment Target"),
    INVALID_CALL_ARGUMENTS("Invalid Call Arguments"),
    INVALID_EXCEPTION_HANDLER_ORDER("Invalid Exception Handler Order"),
    INVALID_PARAMETER_ORDER("Invalid Parameter Order"),
    INVALID_IMPORT_SCOPE("Invalid Import Scope"),
    GLOBAL_DECLARATION_CONFLICT("Global Declaration Conflict"),

    UNSUPPORTED_AST_NODE("Unsupported AST Node"),
    UNRESOLVED_BINDING("Unresolved Binding"),
    INVALID_SCOPE_LAYOUT("Invalid Scope Layout"),
    RUNTIME_SYMBOL_UNAVAILABLE("Runtime Symbol Unavailable"),
    INVALID_CODEGEN_CONTEXT("Invalid Code Generation Context"),
    INVALID_BYTECODE("Invalid Bytecode"),
    UNRESOLVED_LABEL("Unresolved Label"),
    UNSUPPORTED_BYTECODE_VERSION("Unsupported Bytecode Version"),
    BYTECODE_VERIFICATION_ERROR("Bytecode Verification Error"),
    INTERNAL_COMPILER_ERROR("Internal Compiler Error"),

    MISSING_TEMPLATE_VARIABLE("Missing Template Variable"),
    UNDEFINED_JINJA_VARIABLE("Undefined Jinja Variable"),

    INDENTATION_ERROR("Indentation Error"),
    SYNTAX_ERROR("Syntax Error"),
    MISMATCHED_TAG("Mismatched Tag"),
    CSS_PARSE_ERROR("CSS Parse Error");

    private final String displayName;

    DiagnosticCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
