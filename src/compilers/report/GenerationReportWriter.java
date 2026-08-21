package compilers.report;

import compilers.diagnostics.DiagnosticReporter;

import java.nio.file.Path;
import java.util.List;

/** Builds the self-contained HTML dashboard written to compiler_output/report.html. */
public final class GenerationReportWriter {

    private GenerationReportWriter() {
    }

    public static String write(
            int exitCode,
            Path outputDir,
            Path reportsDir,
            List<String> generatedPages,
            DiagnosticReporter reporter,
            String semanticReport,
            String generationLog) {
        boolean success = exitCode == 0;
        int errors = reporter.errors().size();
        int warnings = reporter.warnings().size();

        StringBuilder pages = new StringBuilder();
        if (generatedPages.isEmpty()) {
            pages.append("<p class=\"empty\">No pages were generated.</p>");
        } else {
            pages.append("<ul class=\"page-list\">");
            for (String page : generatedPages) {
                pages.append("<li><a href=\"")
                        .append(attribute(relativeHref(reportsDir, outputDir.resolve(page))))
                        .append("\">")
                        .append(text(page))
                        .append("</a><span>Generated HTML</span></li>");
            }
            pages.append("</ul>");
        }

        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\" />\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n"
                + "<title>Compiler Generation Report</title>\n<style>\n"
                + css()
                + "</style>\n</head>\n<body>\n"
                + "<header><div><p class=\"eyebrow\">Compiler2 Project</p>"
                + "<h1>Generation Report</h1>"
                + "<p class=\"subtitle\">Python/Flask context extraction and Jinja-to-HTML output</p>"
                + "</div><span class=\"status " + (success ? "success" : "failure") + "\">"
                + (success ? "SUCCESS" : "FAILED") + "</span></header>\n"
                + "<main>\n<section class=\"stats\" aria-label=\"Generation summary\">"
                + stat("Exit code", String.valueOf(exitCode))
                + stat("Generated pages", String.valueOf(generatedPages.size()))
                + stat("Errors", String.valueOf(errors))
                + stat("Warnings", String.valueOf(warnings))
                + "</section>\n"
                + "<section><div class=\"section-heading\"><h2>Generated pages</h2>"
                + "<span>Open the compiler output</span></div>" + pages + "</section>\n"
                + "<section><div class=\"section-heading\"><h2>Compiler artifacts</h2>"
                + "<span>Inspect each compilation stage</span></div>"
                + "<nav class=\"artifact-links\">"
                + artifact("Python AST", "ast_python.json")
                + artifact("Jinja AST", "ast_jinja.json")
                + artifact("Semantic report", "semantic_report.txt")
                + artifact("Generation log", "generation_log.txt")
                + "</nav></section>\n"
                + "<section><details open><summary>Generation pipeline</summary><pre>"
                + text(generationLog) + "</pre></details>"
                + "<details><summary>Semantic analysis</summary><pre>"
                + text(semanticReport) + "</pre></details></section>\n"
                + "</main>\n<footer>Deterministic compiler output - no runtime server required</footer>\n"
                + "</body>\n</html>\n";
    }

    private static String stat(String label, String value) {
        return "<article><strong>" + text(value) + "</strong><span>"
                + text(label) + "</span></article>";
    }

    private static String artifact(String label, String href) {
        return "<a href=\"" + attribute(href) + "\">" + text(label) + "</a>";
    }

    private static String relativeHref(Path reportsDir, Path target) {
        Path reports = reportsDir.toAbsolutePath().normalize();
        Path absoluteTarget = target.toAbsolutePath().normalize();
        try {
            return reports.relativize(absoluteTarget).toString().replace('\\', '/');
        } catch (IllegalArgumentException differentRoots) {
            return absoluteTarget.toUri().toString();
        }
    }

    private static String attribute(String value) {
        return text(value).replace("'", "&#39;");
    }

    private static String text(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String css() {
        return "*{box-sizing:border-box}body{margin:0;background:#f4f6f8;color:#18202a;"
                + "font-family:Arial,sans-serif}header{background:#17212b;color:#fff;padding:28px "
                + "max(24px,calc((100% - 1080px)/2));display:flex;align-items:center;"
                + "justify-content:space-between;gap:24px;border-bottom:5px solid #20a36a}"
                + "h1,h2,p{margin-top:0}.eyebrow{color:#8dd9b8;font-size:12px;font-weight:bold;"
                + "text-transform:uppercase;margin-bottom:6px}.subtitle{color:#cbd5df;margin-bottom:0}"
                + ".status{padding:9px 13px;border-radius:4px;font-size:12px;font-weight:bold}"
                + ".success{background:#dff7e9;color:#12613e}.failure{background:#ffe2df;color:#8a2119}"
                + "main{max-width:1080px;margin:0 auto;padding:28px 24px 48px}section{margin-bottom:32px}"
                + ".stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}"
                + ".stats article{background:#fff;border:1px solid #d9e0e7;border-radius:6px;padding:18px}"
                + ".stats strong{display:block;font-size:26px;color:#0e6f91}.stats span{color:#637080}"
                + ".section-heading{display:flex;align-items:end;justify-content:space-between;"
                + "gap:16px;border-bottom:1px solid #ccd5de;margin-bottom:14px}"
                + ".section-heading h2{margin-bottom:10px;font-size:19px}.section-heading span{"
                + "color:#637080;font-size:13px;margin-bottom:10px}.page-list{list-style:none;padding:0;"
                + "display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.page-list li{"
                + "background:#fff;border:1px solid #d9e0e7;border-left:4px solid #20a36a;"
                + "border-radius:5px;padding:14px}.page-list a{display:block;color:#0e6f91;"
                + "font-weight:bold;text-decoration:none;margin-bottom:5px}.page-list span,.empty{"
                + "font-size:13px;color:#637080}.artifact-links{display:flex;flex-wrap:wrap;gap:9px}"
                + ".artifact-links a{background:#fff;border:1px solid #b9c6d2;border-radius:4px;"
                + "color:#244a63;padding:9px 12px;text-decoration:none;font-weight:bold}details{"
                + "background:#fff;border:1px solid #d9e0e7;border-radius:6px;margin-bottom:10px}"
                + "summary{cursor:pointer;font-weight:bold;padding:13px 15px}pre{margin:0;border-top:1px "
                + "solid #e2e8ee;background:#f8fafb;padding:16px;overflow:auto;font:12px/1.6 Consolas,"
                + "monospace;white-space:pre-wrap}footer{text-align:center;color:#687684;padding:20px}"
                + "@media(max-width:720px){header{align-items:flex-start;flex-direction:column}.stats{"
                + "grid-template-columns:repeat(2,minmax(0,1fr))}.page-list{grid-template-columns:1fr}"
                + ".section-heading{align-items:flex-start;flex-direction:column}.section-heading span{"
                + "margin-top:-8px}}\n";
    }
}
