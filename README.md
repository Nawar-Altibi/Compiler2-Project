# Tests/

Manual test files for the **Flask** and **HTML/CSS/Jinja** compilers. Each file targets one feature or a small combination. Run a single file with:

```powershell
java -cp "lib/*;out" Main.UnifiedMain Tests/flask/errors/undefined_variable.py
```

Or run all tests:

```powershell
.\Tests\run_all.ps1
```

---

## Layout

```
Tests/
├── flask/                          Flask (.py) compiler
│   ├── valid/                      Should compile with no semantic errors
│   ├── errors/                     One diagnostic category per file (mostly)
│   └── cross_file/                 Flask + HTML template together
├── html_css/                       HTML/CSS/Jinja (.html) compiler
│   ├── valid/                      Valid templates and pages
│   └── errors/                     Jinja semantic errors (standalone)
└── other/                          Not supported by UnifiedMain (.txt samples)
```

---

## Flask compiler

### Valid (`flask/valid/`)

| File | Features exercised |
|------|-------------------|
| `basic_syntax.py` | Classes, routes, decorators, for/if, try/except/finally, while, with |
| `comprehensive_app.py` | Full Flask app: imports, config, POST, file upload patterns |

### Errors (`flask/errors/`)

| File | Diagnostic category | What it tests |
|------|---------------------|---------------|
| `undefined_variable.py` | `UNDEFINED_VARIABLE` | Names used before definition |
| `scope.py` | `UNDEFINED_VARIABLE`* | Inner/outer scope visibility (scope checker not wired yet) |
| `type_error.py` | `TYPE_ERROR` | Invalid operators on mixed types |
| `type_mismatch.py` | `TYPE_MISMATCH` | Re-assignment with incompatible type |
| `function_call.py` | `FUNCTION_CALL_ERROR` | Wrong number of arguments |
| `duplicate_symbol.py` | `DUPLICATE_SYMBOL` | Duplicate function/class in same scope |
| `combined_semantic.py` | Multiple | All semantic errors in one file |

\* `SCOPE_ERROR` factory exists but is not used yet; scope issues appear as undefined variable.

### Cross-file (`flask/cross_file/`)

| File | Template | Diagnostic category |
|------|----------|---------------------|
| `render_missing_vars.py` | `jinja_complex_errors.html` | `MISSING_TEMPLATE_VARIABLE` |
| `render_context.py` | `profile.html` | `MISSING_TEMPLATE_VARIABLE` (missing `username`) |

Templates must sit in the **same directory** as the `.py` file (see `UnifiedMain`).

---

## HTML/CSS compiler

### Valid (`html_css/valid/`)

| File | Features exercised |
|------|-------------------|
| `simple_page.html` | HTML structure + embedded CSS |
| `base.html` | Layout, `<style>`, Jinja blocks, `url_for` |
| `products_list.html` | `extends`, `{% for %}`, nested Jinja expressions |
| `product.html` | Child template, object attribute access |
| `add_product.html` | Form elements, no dynamic vars |

### Errors (`html_css/errors/`)

| File | Diagnostic category | What it tests |
|------|---------------------|---------------|
| `undefined_jinja.html` | `UNDEFINED_JINJA_VARIABLE` | Simple `{{ var }}` and `{% if %}` |
| `jinja_loops_and_conditions.html` | `UNDEFINED_JINJA_VARIABLE` | Loop locals vs missing vars, `{% set %}` |

Run HTML tests alone (no Flask context):

```powershell
java -cp "lib/*;out" Main.UnifiedMain Tests/html_css/errors/undefined_jinja.html
```

---

## Other (`other/`)

JavaScript and Angular samples — **not** parsed by this project. Kept for reference only.

---

## Quick reference

| Error type | Test file |
|------------|-----------|
| Duplicate symbol | `flask/errors/duplicate_symbol.py` |
| Undefined variable | `flask/errors/undefined_variable.py` |
| Scope (as undefined) | `flask/errors/scope.py` |
| Type error | `flask/errors/type_error.py` |
| Type mismatch | `flask/errors/type_mismatch.py` |
| Function call | `flask/errors/function_call.py` |
| Missing Flask variable (cross-file) | `flask/cross_file/render_missing_vars.py` |
| Undefined Jinja variable | `html_css/errors/undefined_jinja.html` |

See `COMPILER_ERROR_CHECKING.md` in the project root for implementation details.
