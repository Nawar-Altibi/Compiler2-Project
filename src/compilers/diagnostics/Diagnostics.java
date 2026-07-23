package compilers.diagnostics;

/** Central factory for every compiler diagnostic message. */
public final class Diagnostics {
    private Diagnostics() {
    }

    public static Diagnostic undefinedVariable(
            String name, int line, int column, String sourceFile) {
        return semantic(
                DiagnosticCategory.UNDEFINED_VARIABLE,
                DiagnosticSeverity.ERROR,
                sourceFile,
                line,
                column,
                "'" + name + "' is not defined");
    }

    public static Diagnostic scopeError(
            String message, int line, int column, String sourceFile) {
        return semantic(
                DiagnosticCategory.SCOPE_ERROR,
                DiagnosticSeverity.ERROR,
                sourceFile,
                line,
                column,
                message);
    }

    public static Diagnostic typeError(
            String message, int line, int column, String sourceFile) {
        return semantic(
                DiagnosticCategory.TYPE_ERROR,
                DiagnosticSeverity.ERROR,
                sourceFile,
                line,
                column,
                message);
    }

    public static Diagnostic typeMismatch(
            String variableName,
            String expected,
            String received,
            int line,
            int column,
            String sourceFile) {
        return semantic(
                DiagnosticCategory.TYPE_MISMATCH,
                DiagnosticSeverity.WARNING,
                sourceFile,
                line,
                column,
                "Variable '" + variableName + "' was " + expected
                        + " but is reassigned to " + received);
    }

    public static Diagnostic functionCallError(
            String message, int line, int column, String sourceFile) {
        return semantic(
                DiagnosticCategory.FUNCTION_CALL_ERROR,
                DiagnosticSeverity.ERROR,
                sourceFile,
                line,
                column,
                message);
    }

    public static Diagnostic invalidAstStructure(
            String message, int line, int column, String sourceFile) {
        return astValidation(
                DiagnosticCategory.INVALID_AST_STRUCTURE,
                DiagnosticSeverity.ERROR,
                sourceFile,
                line,
                column,
                message);
    }

    public static Diagnostic invalidControlFlow(
            String statement,
            String executableBlock,
            int line,
            int column,
            String sourceFile) {
        String message;
        if ("return".equals(statement)) {
            message = "'return' is only valid in a function block, not in a "
                    + executableBlock + " block";
        } else {
            message = "'" + statement + "' is only valid inside a loop in the same "
                    + executableBlock + " block";
        }
        return astValidation(
                DiagnosticCategory.INVALID_CONTROL_FLOW,
                DiagnosticSeverity.ERROR,
                sourceFile,
                line,
                column,
                message);
    }

    public static Diagnostic invalidAssignmentTarget(
            String operation,
            String targetType,
            int line,
            int column,
            String sourceFile) {
        return astValidation(
                DiagnosticCategory.INVALID_ASSIGNMENT_TARGET,
                DiagnosticSeverity.ERROR,
                sourceFile,
                line,
                column,
                "Invalid " + operation + " target: " + targetType);
    }

    public static Diagnostic invalidCallArguments(
            String message, int line, int column, String sourceFile) {
        return astValidation(
                DiagnosticCategory.INVALID_CALL_ARGUMENTS,
                DiagnosticSeverity.ERROR,
                sourceFile,
                line,
                column,
                message);
    }

    public static Diagnostic invalidExceptionHandlerOrder(
            int line, int column, String sourceFile) {
        return astValidation(
                DiagnosticCategory.INVALID_EXCEPTION_HANDLER_ORDER,
                DiagnosticSeverity.ERROR,
                sourceFile,
                line,
                column,
                "A bare except handler must be the final handler");
    }

    public static Diagnostic invalidParameterOrder(
            String functionName,
            String parameterName,
            int line,
            int column,
            String sourceFile) {
        return astValidation(
                DiagnosticCategory.INVALID_PARAMETER_ORDER,
                DiagnosticSeverity.ERROR,
                sourceFile,
                line,
                column,
                "Required parameter '" + parameterName
                        + "' follows a default parameter in function '"
                        + functionName + "'");
    }

    public static Diagnostic invalidImportScope(
            String executableBlock,
            int line,
            int column,
            String sourceFile) {
        return astValidation(
                DiagnosticCategory.INVALID_IMPORT_SCOPE,
                DiagnosticSeverity.ERROR,
                sourceFile,
                line,
                column,
                "Wildcard imports are only allowed in the module block, not in a "
                        + executableBlock + " block");
    }

    public static Diagnostic globalDeclarationConflict(
            String name,
            String reason,
            int line,
            int column,
            String sourceFile) {
        return semantic(
                DiagnosticCategory.GLOBAL_DECLARATION_CONFLICT,
                DiagnosticSeverity.ERROR,
                sourceFile,
                line,
                column,
                "Name '" + name + "' " + reason + " global declaration");
    }

    public static Diagnostic unresolvedBinding(
            String name, int line, int column, String sourceFile) {
        return bindingResolution(
                DiagnosticCategory.UNRESOLVED_BINDING,
                sourceFile,
                line,
                column,
                "Cannot resolve binding for name '" + name + "'");
    }

    public static Diagnostic invalidScopeLayout(
            String message, int line, int column, String sourceFile) {
        return bindingResolution(
                DiagnosticCategory.INVALID_SCOPE_LAYOUT,
                sourceFile,
                line,
                column,
                message);
    }

    public static Diagnostic unsupportedAstNode(
            String nodeType, int line, int column, String sourceFile) {
        return codeGeneration(
                DiagnosticCategory.UNSUPPORTED_AST_NODE,
                sourceFile,
                line,
                column,
                "No bytecode lowering is available for AST node '" + nodeType + "'");
    }

    public static Diagnostic runtimeSymbolUnavailable(
            String qualifiedName, int line, int column, String sourceFile) {
        return codeGeneration(
                DiagnosticCategory.RUNTIME_SYMBOL_UNAVAILABLE,
                sourceFile,
                line,
                column,
                "Runtime symbol '" + qualifiedName + "' has no executable provider");
    }

    public static Diagnostic invalidCodegenContext(
            String message, int line, int column, String sourceFile) {
        return codeGeneration(
                DiagnosticCategory.INVALID_CODEGEN_CONTEXT,
                sourceFile,
                line,
                column,
                message);
    }

    public static Diagnostic unresolvedLabel(
            String label, int line, int column, String sourceFile) {
        return codeGeneration(
                DiagnosticCategory.UNRESOLVED_LABEL,
                sourceFile,
                line,
                column,
                "Unresolved or invalid bytecode label '" + label + "'");
    }

    public static Diagnostic invalidBytecode(
            String message, int line, int column, String sourceFile) {
        return bytecodeVerification(
                DiagnosticCategory.INVALID_BYTECODE,
                sourceFile,
                line,
                column,
                message);
    }

    public static Diagnostic unsupportedBytecodeVersion(
            int actualVersion, int expectedVersion, String sourceFile) {
        return bytecodeVerification(
                DiagnosticCategory.UNSUPPORTED_BYTECODE_VERSION,
                sourceFile,
                0,
                0,
                "Unsupported bytecode version " + actualVersion
                        + "; expected exact version " + expectedVersion);
    }

    public static Diagnostic bytecodeVerificationError(
            String message, int line, int column, String sourceFile) {
        return bytecodeVerification(
                DiagnosticCategory.BYTECODE_VERIFICATION_ERROR,
                sourceFile,
                line,
                column,
                message);
    }

    public static Diagnostic internalCompilerError(
            CompilerPhase phase,
            String message,
            int line,
            int column,
            String sourceFile) {
        return new Diagnostic(
                DiagnosticCategory.INTERNAL_COMPILER_ERROR,
                DiagnosticSeverity.ERROR,
                phase == null ? CompilerPhase.PIPELINE : phase,
                sourceFile,
                line,
                column,
                message);
    }

    public static Diagnostic missingTemplateVariable(
            String templateName,
            String variableName,
            int line,
            int column,
            String sourceFile) {
        return semantic(
                DiagnosticCategory.MISSING_TEMPLATE_VARIABLE,
                DiagnosticSeverity.ERROR,
                sourceFile,
                line,
                column,
                "Template '" + templateName + "' requires variable '" + variableName
                        + "' but Flask route does not provide it");
    }

    public static Diagnostic undefinedJinjaVariable(
            String variableName, int line, int column, String sourceFile) {
        return semantic(
                DiagnosticCategory.UNDEFINED_JINJA_VARIABLE,
                DiagnosticSeverity.ERROR,
                sourceFile,
                line,
                column,
                "'" + variableName + "' is not supplied by Flask");
    }

    /**
     * Template value problem during generation (plan section 6.3.4):
     * undefined variable, bad attribute, unknown filter or endpoint.
     * A WARNING — the page is still rendered with an empty value.
     */
    public static Diagnostic jinjaValueWarning(
            String message, int line, int column, String sourceFile) {
        return new Diagnostic(
                DiagnosticCategory.UNDEFINED_JINJA_VARIABLE,
                DiagnosticSeverity.WARNING,
                CompilerPhase.CODE_GENERATION,
                sourceFile,
                line,
                column,
                message);
    }

    /**
     * Structural template failure during generation (plan section 6.3.4):
     * unbalanced blocks, missing extends target, template syntax errors.
     * An ERROR — the page is not written.
     */
    public static Diagnostic templateStructureError(
            String message, int line, int column, String sourceFile) {
        return new Diagnostic(
                DiagnosticCategory.INVALID_AST_STRUCTURE,
                DiagnosticSeverity.ERROR,
                CompilerPhase.CODE_GENERATION,
                sourceFile,
                line,
                column,
                message);
    }

    public static Diagnostic duplicateSymbol(
            String kind,
            String name,
            int line,
            int column,
            String sourceFile) {
        return new Diagnostic(
                DiagnosticCategory.DUPLICATE_SYMBOL,
                DiagnosticSeverity.ERROR,
                CompilerPhase.SYMBOL_TABLE,
                sourceFile,
                line,
                column,
                kind + " '" + name + "' is already defined in this scope");
    }

    public static Diagnostic indentationError(
            String message, int line, String sourceFile) {
        return new Diagnostic(
                DiagnosticCategory.INDENTATION_ERROR,
                DiagnosticSeverity.ERROR,
                CompilerPhase.LEXER,
                sourceFile,
                line,
                0,
                message);
    }

    public static Diagnostic syntaxError(
            String message, int line, int column, String sourceFile) {
        return syntaxError(
                CompilerPhase.PARSER, message, line, column, sourceFile);
    }

    /**
     * Creates a syntax diagnostic owned by the lexer or parser frontend stage.
     * Keeping this distinction at creation time lets the production pipeline
     * preserve phase order without rewriting diagnostics after parsing.
     */
    public static Diagnostic syntaxError(
            CompilerPhase phase,
            String message,
            int line,
            int column,
            String sourceFile) {
        if (phase != CompilerPhase.LEXER && phase != CompilerPhase.PARSER) {
            throw new IllegalArgumentException(
                    "Syntax errors can only be reported by the lexer or parser");
        }
        return new Diagnostic(
                DiagnosticCategory.SYNTAX_ERROR,
                DiagnosticSeverity.ERROR,
                phase,
                sourceFile,
                line,
                column,
                message);
    }

    public static Diagnostic mismatchedTag(
            String openTag,
            String closeTag,
            int line,
            int column,
            String sourceFile) {
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
        return new Diagnostic(
                category,
                severity,
                CompilerPhase.SEMANTIC,
                sourceFile,
                line,
                column,
                message);
    }

    private static Diagnostic astValidation(
            DiagnosticCategory category,
            DiagnosticSeverity severity,
            String sourceFile,
            int line,
            int column,
            String message) {
        return new Diagnostic(
                category,
                severity,
                CompilerPhase.AST_VALIDATION,
                sourceFile,
                line,
                column,
                message);
    }

    private static Diagnostic bindingResolution(
            DiagnosticCategory category,
            String sourceFile,
            int line,
            int column,
            String message) {
        return new Diagnostic(
                category,
                DiagnosticSeverity.ERROR,
                CompilerPhase.BINDING_RESOLUTION,
                sourceFile,
                line,
                column,
                message);
    }

    private static Diagnostic codeGeneration(
            DiagnosticCategory category,
            String sourceFile,
            int line,
            int column,
            String message) {
        return new Diagnostic(
                category,
                DiagnosticSeverity.ERROR,
                CompilerPhase.CODE_GENERATION,
                sourceFile,
                line,
                column,
                message);
    }

    private static Diagnostic bytecodeVerification(
            DiagnosticCategory category,
            String sourceFile,
            int line,
            int column,
            String message) {
        return new Diagnostic(
                category,
                DiagnosticSeverity.ERROR,
                CompilerPhase.BYTECODE_VERIFICATION,
                sourceFile,
                line,
                column,
                message);
    }
}
