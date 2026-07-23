# مشروع Compiler2 — مترجم Python/Flask + Jinja إلى صفحات HTML

مشروع جامعي لمادة المترجمات 2: مترجم يقرأ مشروع Flask تعليمي (ملف `app.py` + قوالب Jinja + ملفات تنسيق)، يحلّله على مراحل الكومبايلر الكلاسيكية، ثم ينفّذ **مرحلة التوليد (Code Generation)** كما عرّفتها الجامعة:

> تنفيذ جزء تجهيز البيانات في Python، ثم تمرير القيم إلى قوالب Jinja لتوليد صفحات HTML النهائية.

**الخرج الحقيقي للمترجم هو ملفات HTML مولَّدة + تقارير مراحل الترجمة** — لا يوجد Bytecode ولا Virtual Machine ولا ملفات `.pyc`.

---

## المخطط العام

```
app.py (Flask)
   │  Python Parser (ANTLR)     → Python AST
   │  Structural + Semantic     → التحقق (Symbol Table + Scope + Types)
   │  Context Extraction        → البيانات + خريطة الـ routes   (بدون Symbol Table)
   ▼
Context Data + Route Map
   │  render_template("index.jinja", products=…)
   ▼
templates/*.jinja  →  Jinja/HTML Parser → Jinja AST
   │  Template Rendering: {% for %}, {{ var }}, {% if %}, {% extends %}, url_for()
   ▼
output/*.html  +  compiler_output/*  ← خرج مرحلة التوليد
```

- **قسم Python/Flask** (`src/compilers/flask`): lexer/parser بـ ANTLR، بناء AST، تحقق بنيوي (`AstStructuralValidator`)، جداول رموز، `ScopeRuleChecker`، تحليل دلالي كامل (undefined/types/calls)، ثم **`ContextExtractor`**: مُقيّم شجري صغير (tree-walking) ينفّذ تجهيز البيانات ويلتقط نداءات `render_template` و`url_for` وخريطة الـ routes — ببيانات Java عادية وبلا جدول رموز (حسب تعريف الجامعة للمرحلة).
- **قسم HTML/CSS/Jinja** (`src/compilers/html_css`): lexer/parser للقوالب، AST يحوي عقد Jinja، و**`JinjaRenderer`**: يبني block-tree من العقد الخام (for/if/block/extends عبر event stream + stack)، يقيّم التعابير `{{ … }}` بمُقيّم recursive-descent مصغّر، يطبّق الوراثة `extends/block`، ويحلّ `url_for` عبر خريطة الـ routes — فيُنتج HTML نهائياً.
- **الأنبوب المشترك** (`src/compilers/pipeline` + `src/compilers/report`): تنظيم المراحل وبواباتها، وكتابة التقارير الأربعة.

## ملفات الدخل

| الدخل | الوصف |
|---|---|
| `app.py` | بيانات + routes + نداءات `render_template` |
| `templates/*.jinja` (أو `.html`) | قوالب Jinja (`index`, `add_product`, `edit_product`, `base`) |
| `style.css`, `script.js` | ملفات واجهة تُنسَخ كما هي (script.js اختياري) |

## ملفات الخرج

```
output/
├── index.html            ← مُولَّد
├── add_product.html      ← مُولَّد
├── edit_product.html     ← مُولَّد
├── app.py                ← منسوخ كما هو
├── style.css             ← منسوخ كما هو
├── script.js             ← منسوخ كما هو
└── templates/            ← منسوخ (لتشغيل التطبيق الأصلي)

compiler_output/
├── ast_python.json       ← شجرة AST لبايثون (JSON حتمي)
├── ast_jinja.json        ← أشجار كل القوالب المُحلَّلة
├── semantic_report.txt   ← تقرير التحليل الدلالي
└── generation_log.txt    ← سجل مرحلة التوليد خطوة بخطوة
```

قواعد ثابتة: خطأ دلالي يوقف التوليد؛ الملفات الداعمة تُنسَخ دون أي معالجة؛ أي تعديل بالبيانات يتطلب إعادة توليد.

---

## البناء (Windows PowerShell)

يلزم Java (`java`/`javac`). مكتبة ANTLR موجودة في `lib`.

```powershell
$buildDir = ".tmp\build-$(Get-Date -Format yyyyMMddHHmmss)"
New-Item -ItemType Directory -Path $buildDir | Out-Null
$javaSources = Get-ChildItem src,Tests -Recurse -Filter *.java |
    Select-Object -ExpandProperty FullName
javac -encoding UTF-8 -cp lib\antlr-4.13.1-complete.jar -d $buildDir $javaSources
```

## الاستخدام

```text
java Main.UnifiedMain <project-dir|app.py|file.html|file.jinja> [options]

التوليد (الوضع الافتراضي لمجلد مشروع أو app.py):
  --out DIR      مجلد الصفحات المولّدة (افتراضي: <project>/output)
  --reports DIR  مجلد تقارير المترجم (افتراضي: <project>/compiler_output)
  --quiet        إخفاء ملخص stdout

عروض التحليل (ملف واحد؛ توقف التوليد لملف .py):
  --ast  --symbols  --diagnostics  --debug
```

### مثال كامل (مشروع العيّنة المطابق لمتطلبات الجامعة)

```powershell
java -cp "$buildDir;lib\antlr-4.13.1-complete.jar" `
    Main.UnifiedMain Tests\generation\sample_project
# ثم افتح Tests\generation\sample_project\output\index.html في المتصفح
```

أمثلة أخرى:

```powershell
# عرض AST وجدول الرموز لملف بايثون (بدون توليد)
java -cp "$buildDir;lib\antlr-4.13.1-complete.jar" Main.UnifiedMain app.py --ast --symbols

# تحليل قالب منفرد
java -cp "$buildDir;lib\antlr-4.13.1-complete.jar" Main.UnifiedMain templates\index.jinja --ast
```

### أكواد الخروج

| Exit | المعنى |
|---:|---|
| `0` | نجاح (قد توجد تحذيرات) |
| `1` | خطأ صياغي/دلالي في المصدر |
| `2` | خطأ في مرحلة التوليد/الرندرة (مثل قالب غير متوازن) |
| `3` | خطأ CLI أو فشل داخلي |

---

## المجموعة المدعومة من Python (لمرحلة التوليد)

قيم أساسية (int بدقة حرة/float/str/bool/None)، list/dict/tuple/set، عمليات حسابية ومقارنات و`and/or/not`، f-strings، إسناد عادي ومركّب وتفكيك tuple، `if/elif/else`، `for/while` مع `else` و`break/continue`، دوال مستخدم بوسائط افتراضية، `@app.route/get/post`، `render_template`، `url_for`، مجموعة builtins (`len/range/str/sorted/enumerate/sum/…`). البناءات خارج المجموعة تُرفض بتشخيص واضح — لا قيم وهمية.

ومن Jinja: `{{ تعبير }}` مع escaping افتراضي، `{% for %}` (+`else` و`loop.index/first/last`)، `{% if/elif/else %}`، `{% extends %}/{% block %}`، فلاتر `upper/lower/length/default/title`، و`url_for` (بما فيها `static`).

## الاختبارات

```powershell
Get-ChildItem Tests -Filter *Harness.java | Sort-Object Name | ForEach-Object {
    java -cp "$buildDir;lib\antlr-4.13.1-complete.jar" $_.BaseName
    if ($LASTEXITCODE -ne 0) { throw "Harness failed: $($_.BaseName)" }
}
```

الحزم: front-end (37+17+14+5) + استخراج السياق (8) + رندرة Jinja (10) + توليد end-to-end بذهبيّات (6) + CLI (8).

## بنية المجلدات

| المسار | المسؤولية |
|---|---|
| `src/compilers/flask/antlr_gen` | غرامر Python + المولَّدات |
| `src/compilers/flask/ast` | عقد AST و`ASTBuilder` |
| `src/compilers/flask/SymbolTable`, `semantic`, `runtime` | التحليل الدلالي وسجل الأسماء |
| `src/compilers/flask/generation` | `PyEval` + `ContextExtractor` + نموذج بيانات المرحلة |
| `src/compilers/html_css/antlr`, `ast`, `Visitor` | غرامر القوالب + AST + البُناة |
| `src/compilers/html_css/render` | `JinjaRenderer` ومُقيّم التعابير والوراثة |
| `src/compilers/pipeline`, `src/compilers/report` | تنظيم المراحل + التقارير الأربعة |
| `src/compilers/diagnostics` | نظام التشخيصات المشترك |
| `src/Main/UnifiedMain.java` | CLI فقط |
| `Tests/` | الحزم الاختبارية + مشروع العيّنة + الذهبيّات |

> **ملاحظة تاريخية:** نسخة سابقة من المشروع نفّذت مرحلة توليد كاملة كـ Bytecode مخصّص + Python-like VM بلغة Java (61 opcode، CFG verifier، closures/C3/exceptions). أُرشفت بالكامل على برانش `ghaith-work` بعد أن وضّحت الجامعة أن المطلوب هو توليد HTML عبر رندرة القوالب. المعمار الحالي يبقي الـ AST عقداً ثابتاً، فأي backend مستقبلي (بما فيه bytecode) يُضاف كمستهلك موازٍ دون المساس بالموجود.
