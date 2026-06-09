# Compiler Error Checking

This document describes how errors are detected in the compiler, where each check is implemented, and how diagnostics are reported.

All Flask and HTML/Jinja semantic and symbol-table errors flow through the unified **`compilers.diagnostics`** package. One `DiagnosticReporter` is created per compilation run and shared across phases that support it.

---

## Pipeline Overview

### Flask (`.py`)

```
Source file
  → Lexer (ANTLR)          → tokens
  → Parser (ANTLR)         → parse tree
  → ASTBuilder             → Flask AST
  → SymbolTableBuilder     → symbol table + DUPLICATE_SYMBOL errors
  → SemanticAnalyzer         → UNDEFINED_VARIABLE, TYPE_ERROR, TYPE_MISMATCH, FUNCTION_CALL_ERROR
  → printDiagnostics()     → stderr
  → TemplateContextCollector → variables passed to render_template()
  → Cross-file HTML analysis (if templates exist)
```

Entry point: `src/Main/UnifiedMain.java` → `runFlaskCompiler()`

### HTML / Jinja (`.html`)

```
Source file
  → Lexer (ANTLR)          → tokens
  → Parser (ANTLR)         → parse tree
  → HtmlAstBuilder         → HTML AST
  → SymbolTableBuilder     → HTML symbol table (no diagnostics yet)
  → HtmlSemanticAnalyzer   → Jinja variable checks
  → printDiagnostics()     → stderr
```

Entry point: `src/Main/UnifiedMain.java` → `runHtmlCssCompiler()`

When a Flask file references templates, `UnifiedMain` re-runs the HTML pipeline for each template and passes the variables collected from `render_template(..., key=value)` so missing Flask variables can be detected across files.

---

## Unified Diagnostics System

| File | Role |
|------|------|
| `src/compilers/diagnostics/Diagnostic.java` | Single diagnostic record: category, severity, phase, file, line, column, message |
| `src/compilers/diagnostics/DiagnosticCategory.java` | Named error categories (e.g. `UNDEFINED_VARIABLE`, `TYPE_MISMATCH`) |
| `src/compilers/diagnostics/DiagnosticSeverity.java` | `ERROR`, `WARNING`, `INFO` |
| `src/compilers/diagnostics/CompilerPhase.java` | Which phase produced the diagnostic (`SYMBOL_TABLE`, `SEMANTIC`, …) |
| `src/compilers/diagnostics/Diagnostics.java` | Factory methods — **edit message text here** |
| `src/compilers/diagnostics/DiagnosticReporter.java` | Collects and prints all diagnostics |

**Output format** (from `Diagnostic.toString()`):

```
[Semantic Error] file.py:10:5
  Undefined Variable: 'a' is not defined
```

---

## Error Types (Requested)

### 1. Symbol Table Error

**Category:** `DUPLICATE_SYMBOL`  
**Phase:** `SYMBOL_TABLE`  
**Factory:** `Diagnostics.duplicateSymbol(...)`

**What is checked:** A function or class is defined more than once in the same scope.

**How it works:**

1. `SymbolTableBuilder` walks the Flask AST and calls `currentScope.insert(name, kind)`.
2. `SymbolTable.insert()` returns `null` if the name already exists in the current scope.
3. When insert fails, the builder reports a duplicate-symbol diagnostic.

**Implemented in:**

| Location | What it does |
|----------|--------------|
| `src/compilers/flask/SymbolTable/SymbolTableBuilder.java` | Reports duplicate **functions** (`visitFunctionDef`) and **classes** (`visitClassDef`) |
| `src/compilers/flask/SymbolTable/SymbolTable.java` | `insert()` detects name collision in the current scope |
| `src/compilers/diagnostics/Diagnostics.java` | `duplicateSymbol()` builds the message |

**Example message:** `Function 'foo' is already defined in this scope`

**Test file:** duplicate definitions in any Flask test (not a dedicated test file).

---

### 2. Semantic Errors (General)

**Phase:** `SEMANTIC`  
**Orchestrator:** `SemanticAnalyzer`

Semantic analysis runs three independent AST visitors on the same symbol table. Each visitor reports errors through the shared `DiagnosticReporter`.

**Implemented in:**

| File | Checker | Categories reported |
|------|---------|---------------------|
| `src/compilers/flask/semantic/SemanticAnalyzer.java` | Runs all three checkers | — |
| `src/compilers/flask/semantic/UndefinedVariableChecker.java` | Undefined names | `UNDEFINED_VARIABLE` |
| `src/compilers/flask/semantic/TypeChecker.java` | Type rules | `TYPE_ERROR`, `TYPE_MISMATCH` |
| `src/compilers/flask/semantic/FunctionCallChecker.java` | Call arity | `FUNCTION_CALL_ERROR` |
| `src/compilers/html_css/semantic/HtmlSemanticAnalyzer.java` | Delegates to Jinja checker | — |
| `src/compilers/html_css/semantic/JinjaSemanticAnalyzer.java` | Jinja variables | `MISSING_TEMPLATE_VARIABLE`, `UNDEFINED_JINJA_VARIABLE` |

**Wiring in main:**

```java
// UnifiedMain.runFlaskCompiler()
SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer(table, path, reporter);
semanticAnalyzer.analyze(ast);
printDiagnostics(reporter);
```

**Test files:**

- `Tests/flask_semantic_errors.py` — undefined, type, mismatch, function call
- `Tests/flask_type_errors.py`
- `Tests/flask_call_errors.py`
- `Tests/jinja_semantic_errors.html`
- `Tests/jinja_complex_errors.html`

---

### 3. Undefined Variable

**Category:** `UNDEFINED_VARIABLE`  
**Phase:** `SEMANTIC`  
**Factory:** `Diagnostics.undefinedVariable(...)`

**What is checked:** An identifier is used but does not exist in the current scope chain (including parent scopes and built-ins).

**How it works:**

1. During symbol-table building, each AST node gets a `SymbolTable` scope attached (`node.setScope(...)`).
2. `UndefinedVariableChecker` walks the AST, tracking `currentScope`.
3. On every `IdentifierNode`, it calls `currentScope.lookup(name)`.
4. If lookup returns `null`, it reports an undefined-variable error.

**Scope navigation:** When entering a function or class body, the checker switches to `node.getScope()` and restores the previous scope on exit.

**Implemented in:**

| Location | Role |
|----------|------|
| `src/compilers/flask/semantic/UndefinedVariableChecker.java` | `visitIdentifier()` — main check |
| `src/compilers/flask/SymbolTable/SymbolTable.java` | `lookup()` walks current scope → parent scopes |
| `src/compilers/flask/SymbolTable/SymbolTableBuilder.java` | Builds scopes and registers variables, parameters, imports, loop variables |
| `src/compilers/diagnostics/Diagnostics.java` | `undefinedVariable()` |

**Example message:** `'a' is not defined`

**Test file:** `Tests/flask_semantic_errors.py` (line 2: `print(a)`)

**Note:** Built-in names (`print`, `len`, `str`, …) are pre-registered in `SymbolTableBuilder.initializeBuiltins()` so they are not flagged as undefined.

---

### 4. Type Error

**Category:** `TYPE_ERROR`  
**Phase:** `SEMANTIC`  
**Factory:** `Diagnostics.typeError(...)`

**What is checked:** An operator is applied to incompatible types.

**How it works — `TypeChecker` infers expression types and validates operations:**

| Visit method | Check |
|--------------|-------|
| `visitBinaryOp()` | Arithmetic operators (`+`, `-`, `*`, `/`, …) require numeric operands, except `+` allows string concatenation or list concatenation |
| `visitUnaryOp()` | Unary `+` / `-` require a numeric operand; `not` returns boolean |

If operands are incompatible, `Diagnostics.typeError(...)` is reported with a message describing the operator and types.

**Implemented in:**

| Location | Role |
|----------|------|
| `src/compilers/flask/semantic/TypeChecker.java` | `visitBinaryOp()`, `visitUnaryOp()` |
| `src/compilers/diagnostics/Diagnostics.java` | `typeError()` |

**Example message:** `Cannot apply operator '+' to INTEGER and STRING`

**Test file:** `Tests/flask_semantic_errors.py` (line 10: `c = 5 + "hello"`)

---

### 5. Scope Error

**Category:** `SCOPE_ERROR`  
**Phase:** `SEMANTIC`  
**Factory:** `Diagnostics.scopeError(...)`

**Intended check:** Using a variable that exists in an outer scope but is not visible in the current scope (e.g. accessing a function-local variable from global scope, or using `val` instead of `self.val` inside a method).

**Current status:** The factory and category exist in `Diagnostics.java`, but **no checker calls `Diagnostics.scopeError()` yet**.

**What happens today:** These cases are reported as **`UNDEFINED_VARIABLE`** by `UndefinedVariableChecker`, because `lookup()` only finds names visible from the current scope chain.

**Planned / related files:**

| Location | Role |
|----------|------|
| `src/compilers/diagnostics/Diagnostics.java` | `scopeError()` — ready to use |
| `src/compilers/flask/semantic/UndefinedVariableChecker.java` | Currently catches scope violations as undefined variable |
| `Tests/flask_scope_errors.py` | Documents expected scope-error scenarios (not yet distinct in output) |
| `Tests/flask_semantic_errors.py` | Comment `# Scope Error` on `print(b)` after `foo()` — currently reported as undefined variable |

**To implement scope errors distinctly:** A dedicated checker (or extended logic in `UndefinedVariableChecker`) would need to distinguish “name never defined” from “name defined in a sibling/inner scope but not accessible here”.

---

### 6. Type Mismatch

**Category:** `TYPE_MISMATCH`  
**Phase:** `SEMANTIC`  
**Factory:** `Diagnostics.typeMismatch(...)`

**What is checked:** Assigning a value whose type does not match the variable’s existing type.

**How it works:**

1. `TypeChecker.visitAssignment()` evaluates the right-hand side type.
2. Looks up the assignment target in `currentScope`.
3. If the variable already has a known type and the new value type is known, calls `isCompatible(expected, received)`.
4. Compatible means: same type, or both numeric (`INTEGER` / `FLOAT`).
5. If incompatible, reports a type-mismatch diagnostic.

**Implemented in:**

| Location | Role |
|----------|------|
| `src/compilers/flask/semantic/TypeChecker.java` | `visitAssignment()`, `isCompatible()` |
| `src/compilers/flask/SymbolTable/SymbolTableBuilder.java` | Sets initial variable types via `inferType()` on first assignment |
| `src/compilers/diagnostics/Diagnostics.java` | `typeMismatch()` |

**Example message:** `Variable 'd' expected INTEGER but received STRING`

**Test file:** `Tests/flask_semantic_errors.py` (lines 13–14: `d = 10` then `d = "world"`)

---

### 7. Missing Flask Variable

This covers Jinja template variables that Flask does not supply. There are two related categories depending on whether cross-file validation is active.

#### 7a. Missing Template Variable (cross-file)

**Category:** `MISSING_TEMPLATE_VARIABLE`  
**Phase:** `SEMANTIC`  
**Factory:** `Diagnostics.missingTemplateVariable(...)`

**What is checked:** A Jinja expression uses a variable that the matching Flask `render_template()` call does not pass as a keyword argument.

**How it works:**

1. **Flask side:** `TemplateContextCollector` scans the Flask AST for `render_template("template.html", key=value, ...)` and records keyword names per template file.
2. **HTML side:** When `UnifiedMain` analyzes a template referenced from Flask, it passes that variable set into `HtmlSemanticAnalyzer`.
3. **Jinja side:** `JinjaSemanticAnalyzer` tokenizes Jinja expressions. For each identifier that looks like a variable (not a keyword, not a literal), it checks:
   - Is it in `providedVariables` (from Flask)?
   - Is it a local Jinja variable (`{% for %}`, `{% set %}`)?
4. If neither, and `crossFileValidation` is true, reports `missingTemplateVariable`.

**Implemented in:**

| Location | Role |
|----------|------|
| `src/compilers/flask/semantic/TemplateContextCollector.java` | Collects kwargs from `render_template()` |
| `src/Main/UnifiedMain.java` | Runs cross-file HTML analysis with template contexts |
| `src/compilers/html_css/semantic/HtmlSemanticAnalyzer.java` | Passes `providedVars` for the current template file |
| `src/compilers/html_css/semantic/JinjaSemanticAnalyzer.java` | `checkExpression()`, `crossFileValidation` branch |
| `src/compilers/diagnostics/Diagnostics.java` | `missingTemplateVariable()` |

**Example message:** `Template 'index.html' requires variable 'age' but Flask route does not provide it`

#### 7b. Undefined Jinja Variable (standalone HTML)

**Category:** `UNDEFINED_JINJA_VARIABLE`  
**Phase:** `SEMANTIC`  
**Factory:** `Diagnostics.undefinedJinjaVariable(...)`

**What is checked:** Same Jinja variable logic, but when no Flask context is provided (HTML file analyzed alone).

**Example message:** `'undefined_var' is not supplied by Flask`

**Test files:**

- `Tests/jinja_semantic_errors.html` — standalone HTML analysis
- `Tests/flask_render_errors.py` + linked templates — cross-file missing variables

---

## Quick Reference Table

| Error | Category | Phase | Checker / Builder | Factory method | Wired? |
|-------|----------|-------|-------------------|----------------|--------|
| Symbol table (duplicate) | `DUPLICATE_SYMBOL` | Symbol Table | `SymbolTableBuilder` | `duplicateSymbol()` | Yes |
| Undefined variable | `UNDEFINED_VARIABLE` | Semantic | `UndefinedVariableChecker` | `undefinedVariable()` | Yes |
| Type error | `TYPE_ERROR` | Semantic | `TypeChecker` | `typeError()` | Yes |
| Scope error | `SCOPE_ERROR` | Semantic | — | `scopeError()` | **No** (factory only) |
| Type mismatch | `TYPE_MISMATCH` | Semantic | `TypeChecker` | `typeMismatch()` | Yes |
| Missing Flask variable | `MISSING_TEMPLATE_VARIABLE` | Semantic | `JinjaSemanticAnalyzer` | `missingTemplateVariable()` | Yes (cross-file) |
| Missing Flask variable (standalone) | `UNDEFINED_JINJA_VARIABLE` | Semantic | `JinjaSemanticAnalyzer` | `undefinedJinjaVariable()` | Yes |

---

## Related Errors (Not in Requested List)

| Category | Where | Status |
|----------|-------|--------|
| `FUNCTION_CALL_ERROR` | `FunctionCallChecker` — wrong argument count | Wired |
| `INDENTATION_ERROR` | `Diagnostics.indentationError()` | Factory only |
| `MISMATCHED_TAG` | `Diagnostics.mismatchedTag()` | Factory only |
| `CSS_PARSE_ERROR` | `Diagnostics.cssParseError()` | Factory only |
| `SYNTAX_ERROR` | ANTLR parser default errors | Not unified yet |

---

## Editing Error Messages

Change text in one place:

**`src/compilers/diagnostics/Diagnostics.java`**

Example — change undefined variable wording:

```java
public static Diagnostic undefinedVariable(String name, int line, int column, String sourceFile) {
    return semantic(DiagnosticCategory.UNDEFINED_VARIABLE, DiagnosticSeverity.ERROR,
            sourceFile, line, column, "'" + name + "' is not defined");
}
```

Change display labels in:

**`src/compilers/diagnostics/DiagnosticCategory.java`**
