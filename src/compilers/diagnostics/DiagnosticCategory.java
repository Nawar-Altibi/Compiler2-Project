package compilers.diagnostics;

/**
 * Single registry of all compiler diagnostic categories.
 * Edit display names here to change output labels project-wide.
 */
public enum DiagnosticCategory {
    // Variables & scope
    UNDEFINED_VARIABLE("Undefined Variable"),
    SCOPE_ERROR("Scope Error"),

    // Types
    TYPE_ERROR("Type Error"),
    TYPE_MISMATCH("Type Mismatch"),

    // Functions & symbols
    FUNCTION_CALL_ERROR("Function Call Error"),
    DUPLICATE_SYMBOL("Duplicate Symbol"),

    // Templates / Jinja
    MISSING_TEMPLATE_VARIABLE("Missing Template Variable"),
    UNDEFINED_JINJA_VARIABLE("Undefined Jinja Variable"),

    // Structure
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
