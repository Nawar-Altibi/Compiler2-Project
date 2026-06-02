package compilers.flask.SymbolTable;

import java.util.*;

/**
 * Symbol Table - جدول الرموز مع دعم Scope Nesting
 * يدعم scopes متعددة (global, function, class)
 */
public class SymbolTable {
    
    private final Map<String, SymbolEntry> symbols;  // الرموز في هذا الـ scope
    private final SymbolTable parent;                 // الـ parent scope
    private final String scopeName;                   // اسم الـ scope (للتصحيح)
    private final ScopeType scopeType;                // نوع الـ scope

    /**
     * إنشاء root scope (global scope)
     */
    public SymbolTable() {
        this(null, "global", ScopeType.GLOBAL);
    }

    /**
     * إنشاء scope جديد
     */
    public SymbolTable(SymbolTable parent, String scopeName, ScopeType scopeType) {
        this.symbols = new HashMap<>();
        this.parent = parent;
        this.scopeName = scopeName;
        this.scopeType = scopeType;
    }

    // ========================================
    // Symbol Management
    // ========================================

    /**
     * إدراج رمز جديد في الـ scope الحالي
     * @return SymbolEntry إذا نجح، null إذا كان موجود مسبقاً
     */
    public SymbolEntry insert(String name, SymbolEntry.SymbolKind kind) {
        if (symbols.containsKey(name)) {
            return null; // موجود مسبقاً
        }
        
        SymbolEntry entry = new SymbolEntry(name, kind);
        symbols.put(name, entry);
        entry.setDefined(true);
        return entry;
    }

    /**
     * إدراج رمز مع SymbolEntry جاهز
     */
    public boolean insert(SymbolEntry entry) {
        if (symbols.containsKey(entry.getName())) {
            return false;
        }
        symbols.put(entry.getName(), entry);
        entry.setDefined(true);
        return true;
    }

    /**
     * البحث عن رمز في الـ scope الحالي فقط (بدون البحث في parent)
     */
    public SymbolEntry lookupLocal(String name) {
        return symbols.get(name);
    }

    /**
     * البحث عن رمز في الـ scope الحالي وكل الـ parent scopes
     */
    public SymbolEntry lookup(String name) {
        SymbolEntry entry = symbols.get(name);
        if (entry != null) {
            return entry;
        }
        
        // البحث في parent scope
        if (parent != null) {
            return parent.lookup(name);
        }
        
        return null; // لم يتم العثور عليه
    }

    /**
     * التحقق من وجود رمز في الـ scope الحالي فقط
     */
    public boolean containsLocal(String name) {
        return symbols.containsKey(name);
    }

    /**
     * التحقق من وجود رمز في أي scope
     */
    public boolean contains(String name) {
        return lookup(name) != null;
    }

    // ========================================
    // Scope Management
    // ========================================

    /**
     * إنشاء scope جديد (child scope)
     */
    public SymbolTable enterScope(String scopeName, ScopeType scopeType) {
        return new SymbolTable(this, scopeName, scopeType);
    }

    /**
     * الخروج من الـ scope (إرجاع parent)
     */
    public SymbolTable exitScope() {
        return parent;
    }

    /**
     * الحصول على root scope (global)
     */
    public SymbolTable getRootScope() {
        SymbolTable current = this;
        while (current.parent != null) {
            current = current.parent;
        }
        return current;
    }

    // ========================================
    // Getters
    // ========================================

    public SymbolTable getParent() {
        return parent;
    }

    public String getScopeName() {
        return scopeName;
    }

    public ScopeType getScopeType() {
        return scopeType;
    }

    public Map<String, SymbolEntry> getSymbols() {
        return new HashMap<>(symbols);
    }

    public Collection<SymbolEntry> getAllSymbols() {
        return new ArrayList<>(symbols.values());
    }

    public int getSymbolCount() {
        return symbols.size();
    }

    // ========================================
    // Utility Methods
    // ========================================

    /**
     * طباعة محتويات الـ Symbol Table (للتصحيح)
     */
    public void print() {
        print(0);
    }

    private void print(int indent) {
        StringBuilder indentBuilder = new StringBuilder();
        for (int i = 0; i < indent; i++) {
            indentBuilder.append("  ");
        }
        String indentStr = indentBuilder.toString();
        
        System.out.println(indentStr + "Scope: " + scopeName + " (" + scopeType + ")");
        
        if (symbols.isEmpty()) {
            System.out.println(indentStr + "  (empty)");
        } else {
            for (SymbolEntry entry : symbols.values()) {
                System.out.println(indentStr + "  " + entry);
            }
        }
        
        // طباعة child scopes (إذا كان هناك)
        // Note: في التطبيق الحالي، child scopes هي objects منفصلة
    }

    /**
     * الحصول على عمق الـ scope (0 للـ global)
     */
    public int getDepth() {
        int depth = 0;
        SymbolTable current = this;
        while (current.parent != null) {
            depth++;
            current = current.parent;
        }
        return depth;
    }

    /**
     * مسح جميع الرموز في الـ scope الحالي
     */
    public void clear() {
        symbols.clear();
    }

    /**
     * أنواع الـ Scopes
     */
    public enum ScopeType {
        GLOBAL,      // Global scope
        FUNCTION,    // Function scope
        CLASS,       // Class scope
        MODULE       // Module scope
    }
}

