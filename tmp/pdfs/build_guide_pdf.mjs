import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const modules = "C:/Users/Zakwan/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules";
const { marked } = require(path.join(modules, "marked"));
const { chromium } = require(path.join(modules, "playwright"));

const root = "D:/ideaProjects/Compiler2-Project";
const markdownPath = path.join(root, "CODE_GENERATION_VIVA_GUIDE_AR.md");
const htmlPath = path.join(root, "tmp/pdfs/code-generation-viva-guide-ar.html");
const pdfPath = path.join(root, "output/pdf/code-generation-viva-guide-ar.pdf");

let markdown = fs.readFileSync(markdownPath, "utf8");
markdown = markdown.replace(/^# .+\r?\n/, "");
const body = marked.parse(markdown, { gfm: true });

const html = `<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<title>دليل مناقشة مرحلة Code Generation</title>
<style>
@page {
  size: A4;
  margin: 20mm 16mm 19mm;
}

:root {
  --ink: #17232c;
  --muted: #52636f;
  --line: #cfd9df;
  --soft: #eef4f6;
  --accent: #08779a;
  --green: #138a60;
  --gold: #c18a00;
}

* { box-sizing: border-box; }

html {
  direction: rtl;
  font-family: Tahoma, Arial, sans-serif;
  font-size: 11.2pt;
  color: var(--ink);
  background: white;
}

body {
  margin: 0;
  direction: rtl;
  text-align: right;
  line-height: 1.78;
}

.cover {
  min-height: 245mm;
  display: flex;
  flex-direction: column;
  justify-content: center;
  page-break-after: always;
  position: relative;
  padding: 22mm 13mm;
  border-top: 9px solid var(--green);
  border-bottom: 3px solid var(--accent);
}

.cover::before {
  content: "";
  position: absolute;
  top: 15mm;
  right: 13mm;
  width: 42mm;
  height: 5px;
  background: var(--gold);
}

.cover .eyebrow {
  color: var(--green);
  font-size: 12pt;
  font-weight: 700;
  margin-bottom: 12mm;
}

.cover h1 {
  margin: 0;
  color: #102e3b;
  font-size: 34pt;
  line-height: 1.35;
  font-weight: 800;
}

.cover h2 {
  margin: 8mm 0 0;
  padding: 0;
  border: 0;
  color: var(--muted);
  font-size: 18pt;
  font-weight: 500;
  page-break-before: auto;
}

.cover .stack {
  margin-top: 18mm;
  padding: 6mm 7mm;
  background: var(--soft);
  border-right: 5px solid var(--accent);
  color: #24414f;
  font-size: 12pt;
}

.cover .meta {
  margin-top: auto;
  color: var(--muted);
  font-size: 10pt;
}

.toc {
  page-break-after: always;
}

.toc h1 {
  margin: 0 0 10mm;
  color: #102e3b;
  font-size: 25pt;
  border-bottom: 3px solid var(--green);
  padding-bottom: 4mm;
}

.toc-list {
  columns: 2;
  column-gap: 12mm;
}

.toc-item {
  break-inside: avoid;
  margin: 0 0 3.2mm;
  padding-bottom: 2mm;
  border-bottom: 1px dotted #b9c8d0;
}

.toc-item.level-3 {
  margin-right: 5mm;
  color: var(--muted);
  font-size: 9.5pt;
}

.toc a {
  color: inherit;
  text-decoration: none;
}

article > h2 {
  page-break-before: always;
}

article > h2:first-child {
  page-break-before: auto;
}

h1, h2, h3, h4 {
  break-after: avoid;
  page-break-after: avoid;
  color: #123544;
  line-height: 1.45;
}

h2 {
  margin: 0 0 7mm;
  padding: 0 0 3.5mm;
  border-bottom: 3px solid var(--green);
  font-size: 21pt;
}

h3 {
  margin: 9mm 0 4mm;
  padding-right: 4mm;
  border-right: 4px solid var(--accent);
  font-size: 15.5pt;
}

h4 {
  margin: 6mm 0 2.5mm;
  color: var(--accent);
  font-size: 12.5pt;
}

p {
  margin: 0 0 4mm;
  orphans: 3;
  widows: 3;
}

.qa {
  break-inside: avoid-page;
  page-break-inside: avoid;
}

.qa > p:first-child {
  margin-top: 5mm;
}

strong { color: #102e3b; }

blockquote {
  margin: 5mm 0;
  padding: 4.5mm 6mm;
  background: #edf7f3;
  border-right: 5px solid var(--green);
  color: #173e31;
  break-inside: avoid;
}

ul, ol {
  margin: 2mm 0 5mm;
  padding-right: 7mm;
}

li { margin-bottom: 1.5mm; }

code {
  direction: ltr;
  unicode-bidi: isolate;
  display: inline-block;
  font-family: Consolas, "Courier New", monospace;
  font-size: 9.2pt;
  color: #075d77;
  background: #edf3f5;
  border-radius: 3px;
  padding: 0.2mm 1.2mm;
}

pre {
  direction: ltr;
  text-align: left;
  unicode-bidi: isolate;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin: 4mm 0 6mm;
  padding: 4mm 5mm;
  color: #edf6f7;
  background: #172a34;
  border-right: 5px solid var(--accent);
  border-radius: 4px;
  font: 8.5pt/1.55 Consolas, "Courier New", monospace;
  break-inside: avoid;
}

pre code {
  display: inline;
  padding: 0;
  color: inherit;
  background: transparent;
  font: inherit;
}

table {
  width: 100%;
  border-collapse: collapse;
  margin: 4mm 0 7mm;
  font-size: 8.8pt;
  line-height: 1.55;
  break-inside: auto;
}

thead { display: table-header-group; }
tr { break-inside: avoid; }

th, td {
  padding: 2.5mm 3mm;
  border: 1px solid var(--line);
  vertical-align: top;
  text-align: right;
}

th {
  color: white;
  background: #17495d;
  font-weight: 700;
}

tbody tr:nth-child(even) { background: #f4f7f8; }

a { color: var(--accent); }

.content > p:first-of-type {
  font-size: 11.7pt;
}

@media print {
  a { text-decoration: none; }
}
</style>
</head>
<body>
<section class="cover">
  <div class="eyebrow">مشروع جامعي - Compiler2</div>
  <h1>دليل مناقشة<br>مرحلة Code Generation</h1>
  <h2>Flask Extraction, Jinja Rendering, Watcher, Java HTTP Server</h2>
  <div class="stack">مرجع عملي للأسئلة الشفهية، خريطة الملفات، وسيناريو العرض أمام اللجنة</div>
  <div class="meta">إعداد من محتوى المشروع الحالي - نسخة RTL عربية</div>
</section>
<section class="toc">
  <h1>المحتويات</h1>
  <div class="toc-list" id="toc"></div>
</section>
<article class="content">${body}</article>
<script>
const questionStarts = Array.from(document.querySelectorAll("article p")).filter((paragraph) => {
  const strong = paragraph.firstElementChild;
  return strong?.tagName === "STRONG" && /^س\\d+:/u.test(strong.textContent.trim());
});

questionStarts.forEach((question) => {
  const wrapper = document.createElement("section");
  wrapper.className = "qa";
  question.parentNode.insertBefore(wrapper, question);

  let current = question;
  while (current) {
    const next = current.nextElementSibling;
    wrapper.appendChild(current);
    if (!next || next.matches("h2, h3") || questionStarts.includes(next)) break;
    current = next;
  }
});

const headings = Array.from(document.querySelectorAll("article h2, article h3"));
const toc = document.getElementById("toc");
headings.forEach((heading, index) => {
  heading.id = "section-" + (index + 1);
  const item = document.createElement("div");
  item.className = "toc-item level-" + heading.tagName.substring(1);
  const link = document.createElement("a");
  link.href = "#" + heading.id;
  link.textContent = heading.textContent;
  item.appendChild(link);
  toc.appendChild(item);
});
</script>
</body>
</html>`;

fs.mkdirSync(path.dirname(pdfPath), { recursive: true });
fs.writeFileSync(htmlPath, html, "utf8");

const browser = await chromium.launch({
  headless: true,
  executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe"
});
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
await page.goto("file:///" + htmlPath.replaceAll("\\", "/"), { waitUntil: "load" });
await page.emulateMedia({ media: "print" });
await page.pdf({
  path: pdfPath,
  format: "A4",
  printBackground: true,
  preferCSSPageSize: true,
  displayHeaderFooter: true,
  headerTemplate: `<div style="width:100%;font-family:Tahoma,Arial,sans-serif;font-size:8px;color:#60727d;text-align:right;padding:0 16mm;direction:rtl;">دليل مناقشة Code Generation</div>`,
  footerTemplate: `<div style="width:100%;font-family:Tahoma,Arial,sans-serif;font-size:8px;color:#60727d;text-align:center;"><span class="pageNumber"></span> / <span class="totalPages"></span></div>`,
  margin: { top: "20mm", right: "16mm", bottom: "19mm", left: "16mm" },
  tagged: true,
  outline: true
});
await browser.close();

console.log(pdfPath);
