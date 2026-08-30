# Compiler2 — Flask + Jinja Static Site Generator

A university **Compilers II** project that implements a multi-phase compiler for a constrained Python/Flask web application. The compiler reads a Flask project (`app.py`, Jinja templates, and static assets), runs classic front-end analysis, evaluates route handlers to collect template context, and **generates static HTML pages** — the required code-generation stage for this course.

The real compiler output is **generated HTML plus translation reports**. There is no bytecode, virtual machine, or `.pyc` emission in the current architecture.

---

## What It Does

Given a small Flask project like the bundled sample store app, the compiler:

1. **Parses and analyzes** `app.py` (syntax, structure, symbol table, scope rules, semantics).
2. **Extracts runtime context** by tree-walking evaluation of supported Python (data, routes, `render_template` calls).
3. **Parses and renders** each referenced Jinja template into final HTML.
4. **Copies support files** (`style.css`, `script.js`, `app.py`, raw templates) into the output folder.
5. **Writes reports** (AST JSON, semantic report, generation log, HTML dashboard).

```
app.py (Flask)
   │  ANTLR lexer/parser          → Python AST
   │  Structural + semantic gates → Symbol table, scope, types
   │  Context extraction          → Route map + template arguments
   ▼
Context data + route map
   │  render_template("index.jinja", products=…)
   ▼
templates/*.jinja
   │  HTML/Jinja/CSS parser       → Template AST
   │  JinjaRenderer               → for/if/extends/blocks/url_for
   ▼
output/*.html  +  compiler_output/*
```

---

## Project Structure

| Path | Responsibility |
|------|----------------|
| `src/compilers/flask/` | Python/Flask front end: ANTLR grammar, AST, symbol table, semantic analysis, context extraction (`PyEval`, `ContextExtractor`) |
| `src/compilers/html_css/` | Template front end: HTML/Jinja/CSS parsing and `JinjaRenderer` |
| `src/compilers/pipeline/` | End-to-end `GenerationPipeline` orchestration |
| `src/compilers/report/` | AST JSON, semantic report, generation log, HTML dashboard |
| `src/compilers/diagnostics/` | Shared diagnostic categories and reporting |
| `src/Main/UnifiedMain.java` | Command-line entry point |
| `src/Main/SampleProjectMain.java` | IntelliJ-friendly runner for the sample project |
| `Tests/` | Regression harnesses, fixtures, and the rubric sample project |

---

## Input Layout

A compilable project directory contains:

| File / folder | Role |
|---------------|------|
| `app.py` | Flask routes, data, and `render_template` calls |
| `templates/*.jinja` or `*.html` | Jinja templates (`index`, `add_product`, `edit_product`, `base`, …) |
| `style.css` | Stylesheet (copied verbatim) |
| `script.js` | Optional client script (copied verbatim) |

---

## Output Layout

```
output/
├── index.html              ← generated
├── add_product.html        ← generated
├── edit_product.html       ← generated
├── app.py                  ← copied
├── style.css               ← copied
├── script.js               ← copied (if present)
└── templates/              ← copied (for running the original app)

compiler_output/
├── ast_python.json         ← deterministic Python AST dump
├── ast_jinja.json          ← parsed template ASTs
├── semantic_report.txt     ← semantic analysis summary
├── generation_log.txt      ← step-by-step generation trace
└── report.html             ← dashboard linking pages and reports
```

**Rules:** semantic errors stop generation; support files are never transformed; stale HTML in `output/` is removed before a new render so a failed run cannot leave outdated pages behind.

---

## Requirements

- **Java** (JDK 8+): `java` and `javac` on your `PATH`
- **ANTLR 4.13.1**: place `antlr-4.13.1-complete.jar` in the `lib/` directory (not committed to the repo)

---

## Build

### Windows (PowerShell)

```powershell
$buildDir = ".tmp\build-$(Get-Date -Format yyyyMMddHHmmss)"
New-Item -ItemType Directory -Path $buildDir | Out-Null
$javaSources = Get-ChildItem src,Tests -Recurse -Filter *.java |
    Select-Object -ExpandProperty FullName
javac -encoding UTF-8 -cp lib\antlr-4.13.1-complete.jar -d $buildDir $javaSources
```

### Linux / macOS

```bash
buildDir=".tmp/build-$(date +%Y%m%d%H%M%S)"
mkdir -p "$buildDir"
find src Tests -name '*.java' > sources.txt
javac -encoding UTF-8 -cp lib/antlr-4.13.1-complete.jar -d "$buildDir" @sources.txt
```

---

## Run

### Quick start (IntelliJ)

Open `src/Main/SampleProjectMain.java` and run `main()`. Set the working directory to the project root (`$PROJECT_DIR$`). On success, open:

```
Tests/generation/sample_project/compiler_output/report.html
```

### Command line

```text
java Main.UnifiedMain <project-dir|app.py|file.html|file.jinja> [options]

Generation (default for a project directory or app.py):
  --out DIR       Output folder for generated pages (default: <project>/output)
  --reports DIR   Compiler reports folder (default: <project>/compiler_output)
  --quiet         Suppress the stdout summary

Analysis views (single file; disables generation for .py):
  --ast           Print the AST
  --symbols       Print the symbol table
  --diagnostics   Print all diagnostics
  --debug         Print Java stack traces on internal failures
```

### Sample project

```powershell
java -cp "$buildDir;lib\antlr-4.13.1-complete.jar" `
    Main.UnifiedMain Tests\generation\sample_project
```

```bash
java -cp "$buildDir:lib/antlr-4.13.1-complete.jar" \
    Main.UnifiedMain Tests/generation/sample_project
```

### Analysis-only examples

```powershell
# Python: AST + symbol table, no generation
java -cp "$buildDir;lib\antlr-4.13.1-complete.jar" Main.UnifiedMain app.py --ast --symbols

# Single template
java -cp "$buildDir;lib\antlr-4.13.1-complete.jar" Main.UnifiedMain templates\index.jinja --ast
```

---

## Exit Codes

| Code | Meaning |
|------|---------|
| `0` | Success (warnings may be present) |
| `1` | Syntax or semantic error in source |
| `2` | Generation/rendering failure (e.g. unbalanced template) |
| `3` | CLI error or internal failure |

---

## Supported Language Subset

### Python (generation phase)

Primitives (`int`, `float`, `str`, `bool`, `None`), collections (`list`, `dict`, `tuple`, `set`), arithmetic and comparisons, `and` / `or` / `not`, f-strings, assignment and unpacking, `if` / `elif` / `else`, `for` / `while` with `else`, `break`, `continue`, user functions with default parameters, `@app.route` / `get` / `post`, `render_template`, `url_for`, and common builtins (`len`, `range`, `str`, `sorted`, `enumerate`, `sum`, …). Unsupported constructs are rejected with a clear diagnostic — no silent fallback values.

### Jinja

`{{ expression }}` with default HTML escaping, `{% for %}` (including `else` and `loop.index` / `first` / `last`), `{% if %}` / `{% elif %}` / `{% else %}`, `{% extends %}` / `{% block %}`, filters (`upper`, `lower`, `length`, `default`, `title`), and `url_for` (including `static`).

---

## Tests

Run every harness after building:

```powershell
Get-ChildItem Tests -Filter *Harness.java | Sort-Object Name | ForEach-Object {
    java -cp "$buildDir;lib\antlr-4.13.1-complete.jar" $_.BaseName
    if ($LASTEXITCODE -ne 0) { throw "Harness failed: $($_.BaseName)" }
}
```

Coverage includes Flask front-end regression, semantic validation, context extraction, Jinja rendering, end-to-end generation with golden outputs, and CLI behavior.

---

## Compiler Pipeline (detail)

The generation pipeline (`GenerationPipeline`) enforces this gate order:

1. Parse `app.py` → Python AST
2. Structural validation (`AstStructuralValidator`)
3. Symbol table construction (`SymbolTableBuilder`)
4. Scope rules (`ScopeRuleChecker`)
5. Semantic analysis (`SemanticAnalyzer`)
6. Write `ast_python.json` and `semantic_report.txt` — **stop on semantic errors (exit 1)**
7. Context extraction (`ContextExtractor` / `PyEval`) — **stop on extraction errors (exit 2)**
8. Render each `RenderJob` via `JinjaRenderer` → `output/*.html`
9. Copy support files; write `ast_jinja.json`, `generation_log.txt`, and `report.html`

---

## Historical Note

An earlier iteration of this repository implemented a full custom-bytecode backend with a Python-like VM (61 opcodes, CFG verification, closures, C3 MRO, exceptions). That work is archived on the `ghaith-work` branch. The course requirement was clarified to **static HTML generation via template rendering**, which is what `main` implements today. The AST layer remains backend-agnostic so alternative code generators could be added as parallel consumers.

---

## License

Academic project — see repository history and course materials for usage context.
