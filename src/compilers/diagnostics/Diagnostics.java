package compilers.diagnostics;

/**
 * Factory methods for all compiler diagnostics.
 * Edit message templates here to change diagnostic text project-wide.
 */
public final class Diagnostics {
    private Diagnostics() {}

    // --- Semantic: variables & scope ---

    public static Diagnostic undefinedVariable(String name, int line, int column, String sourceFile) {
        return semantic(DiagnosticCategory.UNDEFINED_VARIABLE, DiagnosticSeverity.ERROR,
                sourceFile, line, column, "'" + name + "' is not defined");
    }

    public static Diagnostic scopeError(String message, int line, int column, String sourceFile) {
        return semantic(DiagnosticCategory.SCOPE_ERROR, DiagnosticSeverity.ERROR,
                sourceFile, line, column, message);
    }

    // --- Semantic: types ---

    public static Diagnostic typeError(String message, int line, int column, String sourceFile) {
        return semantic(DiagnosticCategory.TYPE_ERROR, DiagnosticSeverity.ERROR,
                sourceFile, line, column, message);
    }

    public static Diagnostic typeMismatch(
            String variableName, String expected, String received,
            int line, int column, String sourceFile) {
        return semantic(DiagnosticCategory.TYPE_MISMATCH, DiagnosticSeverity.ERROR,
                sourceFile, line, column,
                "Variable '" + variableName + "' expected " + expected + " but received " + received);
    }

    // --- Semantic: functions ---

    public static Diagnostic functionCallError(String message, int line, int column, String sourceFile) {
        return semantic(DiagnosticCategory.FUNCTION_CALL_ERROR, DiagnosticSeverity.ERROR,
                sourceFile, line, column, message);
    }

    // --- Semantic: templates / Jinja ---

    public static Diagnostic missingTemplateVariable(
            String templateName, String variableName,
            int line, int column, String sourceFile) {
        return semantic(DiagnosticCategory.MISSING_TEMPLATE_VARIABLE, DiagnosticSeverity.ERROR,
                sourceFile, line, column,
                "Template '" + templateName + "' requires variable '" + variableName
                        + "' but Flask route does not provide it");
    }

    public static Diagnostic undefinedJinjaVariable(
            String variableName, int line, int column, String sourceFile) {
        return semantic(DiagnosticCategory.UNDEFINED_JINJA_VARIABLE, DiagnosticSeverity.ERROR,
                sourceFile, line, column,
                "'" + variableName + "' is not supplied by Flask");
    }

    // --- Symbol table ---

    public static Diagnostic duplicateSymbol(String kind, String name, int line, String sourceFile) {
        return new Diagnostic(
                DiagnosticCategory.DUPLICATE_SYMBOL,
                DiagnosticSeverity.ERROR,
                CompilerPhase.SYMBOL_TABLE,
                sourceFile,
                line,
                0,
                kind + " '" + name + "' is already defined in this scope");
    }

    // --- Lexer ---

    public static Diagnostic indentationError(String message, int line, String sourceFile) {
        return new Diagnostic(
                DiagnosticCategory.INDENTATION_ERROR,
                DiagnosticSeverity.ERROR,
                CompilerPhase.LEXER,
                sourceFile,
                line,
                0,
                message);
    }

    // --- AST ---

    public static Diagnostic mismatchedTag(
            String openTag, String closeTag, int line, int column, String sourceFile) {
        return new Diagnostic(
                DiagnosticCategory.MISMATCHED_TAG,
                DiagnosticSeverity.ERROR,
                CompilerPhase.AST,
                sourceFile,
                line,
                column,
                "Mismatched closing tag: <" + openTag + "> closed by </" + closeTag + ">");
    }

    public static Diagnostic cssParseError(int line, String sourceFile) {
        return new Diagnostic(
                DiagnosticCategory.CSS_PARSE_ERROR,
                DiagnosticSeverity.ERROR,
                CompilerPhase.AST,
                sourceFile,
                line,
                0,
                "CSS parsing failed inside <style> block");
    }

    private static Diagnostic semantic(
            DiagnosticCategory category,
            DiagnosticSeverity severity,
            String sourceFile,
            int line,
            int column,
            String message) {
        return new Diagnostic(category, severity, CompilerPhase.SEMANTIC, sourceFile, line, column, message);
    }
}
