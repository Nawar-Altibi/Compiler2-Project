package compilers.flask.semantic.validation;

/** Executable Python code blocks that own independent control-flow state. */
public enum ExecutableBlockKind {
    MODULE("module"),
    FUNCTION("function"),
    CLASS("class");

    private final String displayName;

    ExecutableBlockKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
