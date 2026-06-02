package compilers.flask.ast.nodes.statements.imports;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

public class ImportNode extends Statement {

    private final String moduleName;  // Module to import (can be dotted: os.path)
    private final String asName;      // Optional alias (null if not present)

    public ImportNode(String moduleName, String asName) {
        this.moduleName = moduleName;
        this.asName = asName;
    }

    // Constructor without alias (most common)
    public ImportNode(String moduleName) {
        this(moduleName, null);
    }

    // Getters
    public String getModuleName() {
        return moduleName;
    }

    public String getAsName() {
        return asName;
    }

    // Helper methods
    public boolean hasAlias() {
        return asName != null;
    }

    public String getEffectiveName() {
        return hasAlias() ? asName : moduleName;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitImport(this);
    }

    @Override
    public String getNodeType() {
        return "Import";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("import ");
        sb.append(moduleName);
        if (hasAlias()) {
            sb.append(" as ").append(asName);
        }
        return sb.toString();
    }
}

