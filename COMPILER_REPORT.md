# Compiler2_Project: Unified Compiler Report

## 1. Overview
The **Compiler2_Project** is a multi-language compilation framework designed to parse and analyze two distinct but related web technologies:
1.  **Flask (Python)**: A subset of Python specifically tailored for Flask applications, handling routes, imports, function definitions, and classes.
2.  **HTML/CSS/Jinja**: A template-oriented compiler that processes HTML structures, embedded CSS, and Jinja2 template expressions/statements.

The project uses **ANTLR4** for lexical and syntactic analysis, followed by a custom **AST (Abstract Syntax Tree)** construction and **Symbol Table** generation for semantic awareness.

---

## 2. Project Structure

```text
C:\Users\Lenovo\Desktop\Compiler2_Project\
├── src\
│   ├── Main\
│   │   └── UnifiedMain.java          # Entry point and dispatcher
│   ├── compilers\
│   │   ├── flask\                    # Flask/Python Compiler Module
│   │   │   ├── antlr_gen\            # ANTLR generated Lexer/Parser/Grammars
│   │   │   ├── ast\
│   │   │   │   ├── builder\          # AST construction from ParseTree
│   │   │   │   └── nodes\            # AST node hierarchy (Statements, Expressions)
│   │   │   ├── SymbolTable\          # Scope management and Symbol entries
│   │   │   └── Visitor\              # AST visitors for printing and analysis
│   │   └── html_css\                 # HTML/CSS/Jinja Compiler Module
│   │       ├── antlr\                # Grammars and ANTLR generated files
│   │       ├── ast\                  # AST nodes for HTML, CSS, and Jinja
│   │       ├── SymbolTable\          # Symbol table for template elements
│   │       └── Visitor\              # Builders and Print visitors
└── Tests\                            # Sample test files (.py, .html)
```

---

## 3. How It Works (Compilation Flow)

The compiler follows a traditional pipeline, managed by `UnifiedMain.java`:

### Step 1: Dispatching
`UnifiedMain` detects the file extension:
- `.py` files are routed to the **Flask Compiler**.
- `.html` files are routed to the **HTML/CSS Compiler**.

### Step 2: Lexical & Syntactic Analysis (ANTLR)
The source code is converted into a **Token Stream** (Lexer) and then into a **Parse Tree** (Parser) based on the defined `.g4` grammars.
- **Flask**: Uses `FlaskLexer.g4` and `FlaskParser.g4`.
- **HTML/CSS**: Uses `HtmlCssLexer.g4` and `HtmlCssParser.g4`.

### Step 3: AST Construction
A specialized `ASTBuilder` (utilizing the Visitor pattern) traverses the ANTLR Parse Tree and constructs a more refined and easier-to-manage **Abstract Syntax Tree**.
- Flask AST nodes include `FunctionDefNode`, `ClassDefNode`, `AssignmentNode`, etc.
- HTML/CSS AST nodes include `ElementNode`, `JinjaStatementNode`, `RulesetNode`, etc.

### Step 4: Semantic Analysis & Symbol Table Generation
The `SymbolTableBuilder` visits the AST to identify identifiers, scopes, and types.
- **Flask**: Manages nested scopes for functions and classes. It pre-loads Python built-ins (like `print`, `len`, `Exception`) and performs basic **Type Inference** on assignments.
- **HTML/CSS**: Maps HTML tags, attributes, CSS selectors, and Jinja blocks. It identifies potential variables inside Jinja expressions.

### Step 5: Output/Reporting
The final stage prints a textual representation of the **AST** and the **Symbol Table**, showing the structure and the visibility/kind of every symbol found.

---

## 4. Key Components Detail

### A. Flask Compiler Module
-   **Type Inference**: The `SymbolTableBuilder` attempts to guess variable types (e.g., `STRING`, `INT`, `LIST`, `DICT`) based on the assigned literal or expression.
-   **Built-in Support**: Automatically recognizes standard Python functions and types to avoid "undefined" warnings.
-   **Scope Management**: Correcty handles global vs. local variables in functions and classes.

### B. HTML/CSS/Jinja Module
-   **Unified Grammar**: Combines HTML tags with Jinja template logic (`{% ... %}`, `{{ ... }}`) and `<style>` blocks.
-   **CSS Integration**: Parses CSS rules inside HTML style tags, creating symbols for selectors and properties.
-   **Jinja Awareness**: Identifies block names (e.g., `block content`) and extracts variables from template expressions.

---

## 5. Usage

To run the compiler, use the `UnifiedMain` class:

```bash
java -cp "lib/*;out" Main.UnifiedMain Tests/test_flask.py
# OR
java -cp "lib/*;out" Main.UnifiedMain Tests/index.html
```

---

## 6. Summary of Important Files
-   `UnifiedMain.java`: The "brain" that chooses which compiler to run.
-   `FlaskParser.g4` / `HtmlCssParser.g4`: Define the language rules.
-   `ASTBuilder.java`: Converts raw parsing results into a structured tree.
-   `SymbolTable.java`: Stores all variables, functions, and tags discovered during compilation.
-   `SymbolTableBuilder.java`: The logic that fills the symbol table and checks for errors/warnings.
