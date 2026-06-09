# Run all compiler test files from the project root.
# Usage: .\Tests\run_all.ps1

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$classpath = "lib/*;out"
$runner = "Main.UnifiedMain"

$tests = @(
    # Flask — valid
    "Tests/flask/valid/basic_syntax.py",
    "Tests/flask/valid/comprehensive_app.py",

    # Flask — errors
    "Tests/flask/errors/undefined_variable.py",
    "Tests/flask/errors/scope.py",
    "Tests/flask/errors/type_error.py",
    "Tests/flask/errors/type_mismatch.py",
    "Tests/flask/errors/function_call.py",
    "Tests/flask/errors/duplicate_symbol.py",
    "Tests/flask/errors/combined_semantic.py",

    # Flask — cross-file
    "Tests/flask/cross_file/render_missing_vars.py",
    "Tests/flask/cross_file/render_context.py",

    # HTML/CSS — valid
    "Tests/html_css/valid/simple_page.html",
    "Tests/html_css/valid/base.html",
    "Tests/html_css/valid/products_list.html",
    "Tests/html_css/valid/product.html",
    "Tests/html_css/valid/add_product.html",

    # HTML/CSS — errors
    "Tests/html_css/errors/undefined_jinja.html",
    "Tests/html_css/errors/jinja_loops_and_conditions.html"
)

Write-Host "Running $($tests.Count) test files..." -ForegroundColor Cyan
Write-Host ""

$passed = 0
$failed = 0

foreach ($test in $tests) {
    Write-Host ("=" * 60) -ForegroundColor DarkGray
    Write-Host "TEST: $test" -ForegroundColor Yellow
    Write-Host ("=" * 60) -ForegroundColor DarkGray

    java -cp $classpath $runner $test
    if ($LASTEXITCODE -eq 0) {
        $passed++
    } else {
        $failed++
        Write-Host "Exit code: $LASTEXITCODE" -ForegroundColor Red
    }
    Write-Host ""
}

Write-Host ("=" * 60) -ForegroundColor DarkGray
Write-Host "Done. Ran $($tests.Count) files ($passed ok, $failed non-zero exit)." -ForegroundColor Cyan
