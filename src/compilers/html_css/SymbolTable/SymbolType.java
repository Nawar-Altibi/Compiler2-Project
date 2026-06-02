package compilers.html_css.SymbolTable;

public enum SymbolType {
    HTML_TAG("HTML Tag"),
    HTML_ATTRIBUTE("HTML Attribute"),
    CSS_SELECTOR("CSS Selector"),
    CSS_PROPERTY("CSS Property"),
    JINJA_BLOCK("Jinja Block"),
    JINJA_VARIABLE("Jinja Variable"),
    JINJA_EXPRESSION("Jinja Expression"),
    CSS_STYLESHEET("CSS Stylesheet"),
    CSS_RULE("CSS Rule");

    private final String displayName;

    SymbolType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}


