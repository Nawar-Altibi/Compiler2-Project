package compilers.flask.SymbolTable;
import compilers.flask.ast.nodes.expressions.atoms.*;

/**
 * أنواع الرموز (Symbols) في Python
 */
public enum SymbolType {
    // أنواع البيانات الأساسية
    INTEGER("int"),
    FLOAT("float"),
    STRING("str"),
    BOOLEAN("bool"),
    NONE("None"),
    
    // أنواع المجموعات
    LIST("list"),
    DICT("dict"),
    SET("set"),
    TUPLE("tuple"),
    
    // أنواع الوظائف والكلاسات
    FUNCTION("function"),
    CLASS("class"),
    METHOD("method"),
    
    // أنواع أخرى
    MODULE("module"),
    VARIABLE("variable"),
    UNKNOWN("unknown");

    private final String name;

    SymbolType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * تحويل من نوع LiteralNode إلى SymbolType
     */
    public static SymbolType fromLiteralType(LiteralNode.LiteralType literalType) {
        switch (literalType) {
            case INTEGER:
                return INTEGER;
            case FLOAT:
                return FLOAT;
            case STRING:
                return STRING;
            case BOOLEAN:
                return BOOLEAN;
            case NONE:
                return NONE;
            default:
                return UNKNOWN;
        }
    }
}

