package compilers.flask.SymbolTable;

import compilers.flask.Visitor.ASTBaseVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.helpers.*;
import compilers.flask.ast.nodes.expressions.atoms.*;
import compilers.flask.ast.nodes.expressions.access.*;

import compilers.flask.ast.nodes.expressions.operations.*;

import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.ast.nodes.statements.compound.*;
import compilers.flask.ast.nodes.statements.imports.*;
import compilers.flask.ast.nodes.statements.simple.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Visitor لبناء Symbol Table من AST
 * يزور جميع العقد ويبني جدول الرموز مع معلومات الأنواع
 */
public class SymbolTableBuilder extends ASTBaseVisitor<Void> {
    
    private SymbolTable currentScope;           // الـ scope الحالي
    private final List<String> errors;          // قائمة الأخطاء
    private final List<String> warnings;        // قائمة التحذيرات
    private final List<SymbolTable> allScopes;  // جميع الـ scopes (للطباعة)

    public SymbolTableBuilder() {
        this.currentScope = new SymbolTable(); // بدء بـ global scope
        this.errors = new ArrayList<>();
        this.warnings = new ArrayList<>();
        this.allScopes = new ArrayList<>();
        this.allScopes.add(currentScope); // إضافة global scope
        
        // إضافة Python built-ins إلى global scope
        initializeBuiltins();
    }
    
    /**
     * إضافة Python built-ins إلى global scope
     */
    private void initializeBuiltins() {
        // Built-in variables
        addBuiltin("__name__", SymbolType.STRING, SymbolEntry.SymbolKind.VARIABLE);
        addBuiltin("__main__", SymbolType.STRING, SymbolEntry.SymbolKind.VARIABLE);
        
        // Built-in functions
        addBuiltin("print", SymbolType.FUNCTION, SymbolEntry.SymbolKind.FUNCTION);
        addBuiltin("str", SymbolType.FUNCTION, SymbolEntry.SymbolKind.FUNCTION);
        addBuiltin("int", SymbolType.FUNCTION, SymbolEntry.SymbolKind.FUNCTION);
        addBuiltin("float", SymbolType.FUNCTION, SymbolEntry.SymbolKind.FUNCTION);
        addBuiltin("len", SymbolType.FUNCTION, SymbolEntry.SymbolKind.FUNCTION);
        addBuiltin("open", SymbolType.FUNCTION, SymbolEntry.SymbolKind.FUNCTION);
        addBuiltin("range", SymbolType.FUNCTION, SymbolEntry.SymbolKind.FUNCTION);
        addBuiltin("super", SymbolType.FUNCTION, SymbolEntry.SymbolKind.FUNCTION);
        addBuiltin("isinstance", SymbolType.FUNCTION, SymbolEntry.SymbolKind.FUNCTION);
        addBuiltin("type", SymbolType.FUNCTION, SymbolEntry.SymbolKind.FUNCTION);
        addBuiltin("dict", SymbolType.CLASS, SymbolEntry.SymbolKind.CLASS);
        addBuiltin("list", SymbolType.CLASS, SymbolEntry.SymbolKind.CLASS);
        addBuiltin("tuple", SymbolType.CLASS, SymbolEntry.SymbolKind.CLASS);
        addBuiltin("set", SymbolType.CLASS, SymbolEntry.SymbolKind.CLASS);
        addBuiltin("bool", SymbolType.CLASS, SymbolEntry.SymbolKind.CLASS);
        
        // Built-in exceptions
        addBuiltin("Exception", SymbolType.CLASS, SymbolEntry.SymbolKind.CLASS);
        addBuiltin("IOError", SymbolType.CLASS, SymbolEntry.SymbolKind.CLASS);
        addBuiltin("ValueError", SymbolType.CLASS, SymbolEntry.SymbolKind.CLASS);
        addBuiltin("TypeError", SymbolType.CLASS, SymbolEntry.SymbolKind.CLASS);
        addBuiltin("ConnectionError", SymbolType.CLASS, SymbolEntry.SymbolKind.CLASS);
    }
    
    private void addBuiltin(String name, SymbolType type, SymbolEntry.SymbolKind kind) {
        SymbolEntry entry = currentScope.insert(name, kind);
        if (entry != null) {
            entry.setType(type);
            entry.setAttribute("builtin", true);
        }
    }

    // ========================================
    // Getters
    // ========================================

    public SymbolTable getSymbolTable() {
        return currentScope.getRootScope();
    }
    
    /**
     * الحصول على جميع الـ scopes (للطباعة)
     */
    public List<SymbolTable> getAllScopes() {
        return new ArrayList<>(allScopes);
    }

    public List<String> getErrors() {
        return new ArrayList<>(errors);
    }

    public List<String> getWarnings() {
        return new ArrayList<>(warnings);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    // ========================================
    // Program
    // ========================================

    @Override
    public Void visitProgram(ProgramNode node) {
        // الـ program يبدأ في global scope
        currentScope = currentScope.getRootScope();
        node.setScope(currentScope);
        
        // زيارة جميع الـ statements
        super.visitProgram(node);
        
        return null;
    }

    // ========================================
    // Import Statements
    // ========================================

    @Override
    public Void visitImport(ImportNode node) {
        // إضافة module إلى symbol table
        String moduleName = node.getModuleName();
        SymbolEntry entry = currentScope.insert(moduleName, SymbolEntry.SymbolKind.MODULE);
        if (entry != null) {
            entry.setType(SymbolType.MODULE);
            entry.setNode(node);
            if (node.hasAlias()) {
                // إضافة alias أيضاً
                SymbolEntry aliasEntry = currentScope.insert(node.getAsName(), SymbolEntry.SymbolKind.MODULE);
                if (aliasEntry != null) {
                    aliasEntry.setType(SymbolType.MODULE);
                    aliasEntry.setAttribute("original_module", moduleName);
                }
            }
        }
        return null;
    }

    @Override
    public Void visitFromImport(FromImportNode node) {
        // إضافة العناصر المستوردة إلى symbol table
        if (!node.isImportAll()) {
            for (FromImportNode.ImportItem item : node.getItems()) {
                String name = item.getEffectiveName();
                String originalName = item.getName();
                
                // محاولة استنتاج النوع من الاسم (Flask = class, render_template = function)
                SymbolEntry.SymbolKind kind = SymbolEntry.SymbolKind.VARIABLE;
                SymbolType type = SymbolType.UNKNOWN;
                
                // أسماء معروفة من flask
                if (originalName.equals("Flask")) {
                    kind = SymbolEntry.SymbolKind.CLASS;
                    type = SymbolType.CLASS;
                } else if (originalName.equals("render_template") || 
                          originalName.equals("redirect") || 
                          originalName.equals("url_for")) {
                    kind = SymbolEntry.SymbolKind.FUNCTION;
                    type = SymbolType.FUNCTION;
                } else if (originalName.equals("request")) {
                    // request هو object، ليس function
                    kind = SymbolEntry.SymbolKind.VARIABLE;
                    type = SymbolType.UNKNOWN;
                }
                
                SymbolEntry entry = currentScope.insert(name, kind);
                if (entry != null) {
                    entry.setType(type);
                    entry.setAttribute("imported_from", node.getModuleName());
                }
            }
        }
        return null;
    }

    // ========================================
    // Assignment Statements
    // ========================================

    @Override
    public Void visitAssignment(AssignmentNode node) {
        // زيارة value أولاً (لحساب النوع)
        node.getValue().accept(this);
        
        // زيارة target وإضافة إلى symbol table
        Expression target = node.getTarget();
        if (target instanceof IdentifierNode) {
            String name = ((IdentifierNode) target).getName();
            SymbolEntry entry = currentScope.lookupLocal(name);
            
            if (entry == null) {
                // متغير جديد
                entry = currentScope.insert(name, SymbolEntry.SymbolKind.VARIABLE);
                if (entry != null) {
                    entry.setNode(node);
                    // محاولة استنتاج النوع من القيمة
                    SymbolType type = inferType(node.getValue());
                    entry.setType(type);
                }
            } else {
                // متغير موجود - تحديث النوع إذا لزم الأمر
                SymbolType newType = inferType(node.getValue());
                if (entry.getType() == SymbolType.UNKNOWN) {
                    entry.setType(newType);
                }
                entry.setUsed(true);
            }
        } else if (target instanceof AttributeAccessNode) {
            // attribute assignment (مثل self.name = value)
            target.accept(this);
        }
        
        return null;
    }

    // ========================================
    // Function Definitions
    // ========================================

    @Override
    public Void visitFunctionDef(FunctionDefNode node) {
        String functionName = node.getName();
        
        // إضافة الدالة إلى الـ scope الحالي
        SymbolEntry functionEntry = currentScope.insert(functionName, SymbolEntry.SymbolKind.FUNCTION);
        if (functionEntry != null) {
            functionEntry.setType(SymbolType.FUNCTION);
            functionEntry.setFunctionNode(node);
            functionEntry.setNode(node);
        } else {
            // دالة موجودة مسبقاً
            errors.add("Function '" + functionName + "' already defined at line " + node.getLine());
        }
        
        // إنشاء scope جديد للدالة
        SymbolTable functionScope = currentScope.enterScope(functionName, SymbolTable.ScopeType.FUNCTION);
        node.setScope(functionScope);
        allScopes.add(functionScope); // حفظ scope للطباعة
        SymbolTable previousScope = currentScope;
        currentScope = functionScope;
        
        // إضافة parameters إلى function scope
        for (Parameter param : node.getParameters()) {
            String paramName = param.getName();
            SymbolEntry paramEntry = currentScope.insert(paramName, SymbolEntry.SymbolKind.PARAMETER);
            if (paramEntry != null) {
                paramEntry.setType(SymbolType.UNKNOWN);
                if (param.hasDefault()) {
                    paramEntry.setAttribute("has_default", true);
                }
            }
        }
        
        // زيارة body
        for (Statement stmt : node.getBody()) {
            stmt.accept(this);
        }
        
        // العودة للـ scope السابق
        currentScope = previousScope;
        
        return null;
    }

    // ========================================
    // Class Definitions
    // ========================================

    @Override
    public Void visitClassDef(ClassDefNode node) {
        String className = node.getName();
        
        // إضافة الكلاس إلى الـ scope الحالي
        SymbolEntry classEntry = currentScope.insert(className, SymbolEntry.SymbolKind.CLASS);
        if (classEntry != null) {
            classEntry.setType(SymbolType.CLASS);
            classEntry.setClassNode(node);
            classEntry.setNode(node);
        } else {
            errors.add("Class '" + className + "' already defined at line " + node.getLine());
        }
        
        // إنشاء scope جديد للكلاس
        SymbolTable classScope = currentScope.enterScope(className, SymbolTable.ScopeType.CLASS);
        node.setScope(classScope);
        allScopes.add(classScope); // حفظ scope للطباعة
        SymbolTable previousScope = currentScope;
        currentScope = classScope;
        
        // زيارة body (methods, attributes, etc.)
        for (Statement stmt : node.getBody()) {
            stmt.accept(this);
        }
        
        // العودة للـ scope السابق
        currentScope = previousScope;
        
        return null;
    }

    // ========================================
    // Expressions
    // ========================================

    @Override
    public Void visitIdentifier(IdentifierNode node) {
        String name = node.getName();
        SymbolEntry entry = currentScope.lookup(name);
        
        if (entry == null) {
            // متغير غير معرّف - لكن قد يكون built-in
            if (!isBuiltin(name)) {
                warnings.add("Variable '" + name + "' used but not defined at line " + node.getLine());
            }
        } else {
            entry.setUsed(true);
        }
        
        return null;
    }

    @Override
    public Void visitFunctionCall(FunctionCallNode node) {
        // زيارة function expression
        Expression function = node.getFunction();
        if (function instanceof IdentifierNode) {
            String funcName = ((IdentifierNode) function).getName();
            SymbolEntry entry = currentScope.lookup(funcName);
            
            if (entry == null) {
                // قد يكون built-in أو مستورد - لا نضيف warning
                if (!isBuiltin(funcName)) {
                    warnings.add("Function '" + funcName + "' called but not defined at line " + node.getLine());
                }
            } else {
                // التحقق من النوع
                if (entry.getKind() == SymbolEntry.SymbolKind.CLASS) {
                    // Class constructor call (مثل Flask(), User()) - هذا صحيح
                    entry.setUsed(true);
                } else if (entry.getKind() == SymbolEntry.SymbolKind.FUNCTION) {
                    entry.setUsed(true);
                } else {
                    // متغير عادي - قد يكون خطأ أو قد يكون callable
                    // في Python، كل شيء callable تقريباً، لذا نعتبره warning فقط
                    warnings.add("'" + funcName + "' is called but may not be callable at line " + node.getLine());
                }
            }
        } else if (function instanceof AttributeAccessNode) {
            // method call (مثل app.run()) - زيارة object
            function.accept(this);
        }
        
        // زيارة arguments
        super.visitFunctionCall(node);
        return null;
    }
    
    /**
     * التحقق إذا كان اسم built-in
     */
    private boolean isBuiltin(String name) {
        return currentScope.getRootScope().lookupLocal(name) != null &&
               currentScope.getRootScope().lookupLocal(name).hasAttribute("builtin");
    }

    @Override
    public Void visitAttributeAccess(AttributeAccessNode node) {
        // زيارة object
        node.getObject().accept(this);
        return null;
    }

    // ========================================
    // For Statements
    // ========================================

    @Override
    public Void visitForStatement(ForStatementNode node) {
        // إضافة loop variable إلى scope الحالي
        Expression target = node.getTarget();
        if (target instanceof IdentifierNode) {
            String varName = ((IdentifierNode) target).getName();
            SymbolEntry entry = currentScope.insert(varName, SymbolEntry.SymbolKind.VARIABLE);
            if (entry != null) {
                // محاولة استنتاج النوع من iterable
                SymbolType iterableType = inferType(node.getIterable());
                if (iterableType == SymbolType.LIST) {
                    entry.setType(SymbolType.UNKNOWN); // نوع عناصر list غير معروف
                } else {
                    entry.setType(SymbolType.UNKNOWN);
                }
                entry.setNode(node);
                entry.setAttribute("loop_variable", true);
            }
        }
        
        // زيارة iterable
        node.getIterable().accept(this);
        
        // زيارة body
        for (Statement stmt : node.getBody()) {
            stmt.accept(this);
        }
        
        // زيارة else body إذا كان موجود
        if (node.hasElse()) {
            for (Statement stmt : node.getElseBody()) {
                stmt.accept(this);
            }
        }
        
        return null;
    }

    // ========================================
    // With Statements
    // ========================================

    @Override
    public Void visitWithStatement(WithStatementNode node) {
        // زيارة with items وإضافة variables
        for (WithItem item : node.getItems()) {
            // زيارة context expression
            item.getContextExpr().accept(this);
            
            // إضافة as name إذا كان موجود
            if (item.hasAsName() && item.getAsName() instanceof IdentifierNode) {
                String varName = ((IdentifierNode) item.getAsName()).getName();
                SymbolEntry entry = currentScope.insert(varName, SymbolEntry.SymbolKind.VARIABLE);
                if (entry != null) {
                    entry.setType(SymbolType.UNKNOWN);
                    entry.setNode(node);
                }
            }
        }
        
        // زيارة body
        for (Statement stmt : node.getBody()) {
            stmt.accept(this);
        }
        
        return null;
    }

    // ========================================
    // Try Statements
    // ========================================

    @Override
    public Void visitTryStatement(TryStatementNode node) {
        // زيارة try body
        for (Statement stmt : node.getTryBody()) {
            stmt.accept(this);
        }
        
        // زيارة except clauses
        for (ExceptClause exceptClause : node.getExceptClauses()) {
            // إضافة exception variable إذا كان موجود
            if (exceptClause.hasAsName()) {
                String varName = exceptClause.getAsName();
                SymbolEntry entry = currentScope.insert(varName, SymbolEntry.SymbolKind.VARIABLE);
                if (entry != null) {
                    entry.setType(SymbolType.UNKNOWN);
                    // محاولة الحصول على line number من node
                    if (node.getLine() > 0) {
                        entry.setLine(node.getLine());
                    }
                    entry.setAttribute("exception_variable", true);
                }
            }
            
            // زيارة exception body
            for (Statement stmt : exceptClause.getBody()) {
                stmt.accept(this);
            }
        }
        
        // زيارة else body
        if (node.hasElse()) {
            for (Statement stmt : node.getElseBody()) {
                stmt.accept(this);
            }
        }
        
        // زيارة finally body
        if (node.hasFinally()) {
            for (Statement stmt : node.getFinallyBody()) {
                stmt.accept(this);
            }
        }
        
        return null;
    }

    // ========================================
    // Helper Methods
    // ========================================

    /**
     * استنتاج نوع Expression
     */
    private SymbolType inferType(Expression expr) {
        if (expr instanceof LiteralNode) {
            LiteralNode literal = (LiteralNode) expr;
            return SymbolType.fromLiteralType(literal.getLiteralType());
        } else if (expr instanceof IdentifierNode) {
            IdentifierNode id = (IdentifierNode) expr;
            SymbolEntry entry = currentScope.lookup(id.getName());
            if (entry != null) {
                return entry.getType();
            }
        } else if (expr instanceof ListNode) {
            return SymbolType.LIST;
        } else if (expr instanceof DictNode) {
            return SymbolType.DICT;
        } else if (expr instanceof SetNode) {
            return SymbolType.SET;
        } else if (expr instanceof TupleNode) {
            return SymbolType.TUPLE;
        } else if (expr instanceof FunctionCallNode) {
            // محاولة استنتاج نوع الدالة
            FunctionCallNode call = (FunctionCallNode) expr;
            Expression func = call.getFunction();
            if (func instanceof IdentifierNode) {
                String funcName = ((IdentifierNode) func).getName();
                SymbolEntry entry = currentScope.lookup(funcName);
                if (entry != null && entry.getFunctionNode() != null) {
                    // يمكن إضافة منطق أكثر تعقيداً هنا
                    return SymbolType.UNKNOWN;
                }
            }
        }
        
        return SymbolType.UNKNOWN;
    }
}



