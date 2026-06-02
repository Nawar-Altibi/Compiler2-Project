package compilers.flask.SymbolTable;

import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;
import compilers.flask.ast.nodes.statements.compound.ClassDefNode;

import java.util.HashMap;
import java.util.Map;

/**
 * إدخال في Symbol Table يمثل رمز واحد (متغير، دالة، كلاس، إلخ)
 */
public class SymbolEntry {
    
    private final String name;                    // اسم الرمز
    private SymbolType type;                       // نوع الرمز
    private SymbolKind kind;                       // نوع الرمز (variable, function, class, etc.)
    private final Map<String, Object> attributes; // خصائص إضافية
    
    // مراجع للعقد في AST (للاستخدام في التحليل اللاحق)
    private ASTNode node;                          // العقدة المرتبطة
    private FunctionDefNode functionNode;          // إذا كان function
    private ClassDefNode classNode;                // إذا كان class
    
    // معلومات إضافية
    private int line;                              // رقم السطر
    private int column;                            // رقم العمود
    private boolean isDefined;                     // هل تم تعريفه أم لا
    private boolean isUsed;                        // هل تم استخدامه أم لا

    public SymbolEntry(String name, SymbolKind kind) {
        this.name = name;
        this.kind = kind;
        this.attributes = new HashMap<>();
        this.type = SymbolType.UNKNOWN;
        this.isDefined = false;
        this.isUsed = false;
    }

    // ========================================
    // Getters and Setters
    // ========================================

    public String getName() {
        return name;
    }

    public SymbolType getType() {
        return type;
    }

    public void setType(SymbolType type) {
        this.type = type;
    }

    public SymbolKind getKind() {
        return kind;
    }

    public void setKind(SymbolKind kind) {
        this.kind = kind;
    }

    public ASTNode getNode() {
        return node;
    }

    public void setNode(ASTNode node) {
        this.node = node;
        if (node != null) {
            this.line = node.getLine();
            this.column = node.getColumn();
        }
    }

    public FunctionDefNode getFunctionNode() {
        return functionNode;
    }

    public void setFunctionNode(FunctionDefNode functionNode) {
        this.functionNode = functionNode;
        this.node = functionNode;
        if (functionNode != null) {
            this.line = functionNode.getLine();
            this.column = functionNode.getColumn();
        }
    }

    public ClassDefNode getClassNode() {
        return classNode;
    }

    public void setClassNode(ClassDefNode classNode) {
        this.classNode = classNode;
        this.node = classNode;
        if (classNode != null) {
            this.line = classNode.getLine();
            this.column = classNode.getColumn();
        }
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public int getColumn() {
        return column;
    }

    public void setColumn(int column) {
        this.column = column;
    }

    public boolean isDefined() {
        return isDefined;
    }

    public void setDefined(boolean defined) {
        isDefined = defined;
    }

    public boolean isUsed() {
        return isUsed;
    }

    public void setUsed(boolean used) {
        isUsed = used;
    }

    // ========================================
    // Attributes Management
    // ========================================

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public <T> T getAttribute(String key, Class<T> clazz) {
        Object value = attributes.get(key);
        if (value != null && clazz.isInstance(value)) {
            return clazz.cast(value);
        }
        return null;
    }

    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    public Map<String, Object> getAttributes() {
        return new HashMap<>(attributes);
    }

    // ========================================
    // Helper Methods
    // ========================================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        if (type != SymbolType.UNKNOWN) {
            sb.append(": ").append(type);
        }
        if (kind != null) {
            sb.append(" (").append(kind).append(")");
        }
        if (line > 0) {
            sb.append(" [line ").append(line).append("]");
        }
        return sb.toString();
    }

    /**
     * أنواع الرموز المختلفة
     */
    public enum SymbolKind {
        VARIABLE,      // متغير عادي
        FUNCTION,      // دالة
        METHOD,        // method في class
        CLASS,         // class
        PARAMETER,     // parameter في function
        MODULE,        // module
        ATTRIBUTE      // attribute في class
    }
}

