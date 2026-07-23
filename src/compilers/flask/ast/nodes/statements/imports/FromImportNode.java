package compilers.flask.ast.nodes.statements.imports;
import compilers.flask.Visitor.ASTVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FromImportNode extends Statement {

    /**
     * Helper class for individual import items
     */
    public static class ImportItem {
        private final String name;    // Item name
        private final String asName;  // Optional alias (null if not present)
        private final SourceSpan span;
        private final SourceSpan nameSpan;
        private final SourceSpan aliasSpan;

        public ImportItem(String name, String asName) {
            this(name, asName, SourceSpan.UNKNOWN,
                    SourceSpan.UNKNOWN, SourceSpan.UNKNOWN);
        }

        public ImportItem(
                String name,
                String asName,
                SourceSpan span,
                SourceSpan nameSpan,
                SourceSpan aliasSpan) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Imported name cannot be empty");
            }
            this.name = name;
            this.asName = asName;
            this.span = span == null ? SourceSpan.UNKNOWN : span;
            this.nameSpan = nameSpan == null ? SourceSpan.UNKNOWN : nameSpan;
            this.aliasSpan = aliasSpan == null ? SourceSpan.UNKNOWN : aliasSpan;
        }

        public ImportItem(String name) {
            this(name, null);
        }

        public String getName() {
            return name;
        }

        public String getAsName() {
            return asName;
        }

        public SourceSpan getSpan() {
            return span;
        }

        public SourceSpan getNameSpan() {
            return nameSpan;
        }

        public SourceSpan getAliasSpan() {
            return aliasSpan;
        }

        public boolean hasAlias() {
            return asName != null;
        }

        public String getEffectiveName() {
            return hasAlias() ? asName : name;
        }

        @Override
        public String toString() {
            return hasAlias() ? name + " as " + asName : name;
        }
    }

    private final String moduleName;        // Module to import from
    private final List<ImportItem> items;   // What to import
    private final boolean importAll;        // True for "import *"

    public FromImportNode(String moduleName, List<ImportItem> items, boolean importAll) {
        this.moduleName = moduleName;
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
        this.importAll = importAll;
    }

    // Constructor for normal import (not *)
    public FromImportNode(String moduleName, List<ImportItem> items) {
        this(moduleName, items, false);
    }

    // Constructor for wildcard import (from x import *)
    public static FromImportNode importAll(String moduleName) {
        return new FromImportNode(moduleName, new ArrayList<>(), true);
    }

    // Constructor for single item
    public static FromImportNode singleItem(String moduleName, String itemName) {
        List<ImportItem> items = new ArrayList<>();
        items.add(new ImportItem(itemName));
        return new FromImportNode(moduleName, items, false);
    }

    // Constructor for single item with alias
    public static FromImportNode singleItem(String moduleName, String itemName, String asName) {
        List<ImportItem> items = new ArrayList<>();
        items.add(new ImportItem(itemName, asName));
        return new FromImportNode(moduleName, items, false);
    }

    // Getters
    public String getModuleName() {
        return moduleName;
    }

    public List<ImportItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public boolean isImportAll() {
        return importAll;
    }

    // Helper methods
    public int getItemCount() {
        return items.size();
    }

    public boolean isSingleImport() {
        return !importAll && items.size() == 1;
    }

    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visitFromImport(this);
    }

    @Override
    public String getNodeType() {
        return "FromImport";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("from ");
        sb.append(moduleName).append(" import ");

        if (importAll) {
            sb.append("*");
        } else {
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(items.get(i));
            }
        }

        return sb.toString();
    }
}
