package compilers.report;

import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticReporter;

/**
 * Produces {@code compiler_output/semantic_report.txt} (plan section 7.2):
 * a deterministic, human-readable listing of every diagnostic with a
 * severity summary — "No errors." when the program is clean.
 */
public final class SemanticReportWriter {

    private SemanticReportWriter() {
    }

    public static String write(String sourceFile, DiagnosticReporter reporter) {
        StringBuilder out = new StringBuilder();
        out.append("Semantic Analysis Report\n");
        out.append("Source: ").append(sourceFile).append('\n');
        out.append("========================================\n");

        int errors = reporter.errors().size();
        int warnings = reporter.warnings().size();
        if (!reporter.hasDiagnostics()) {
            out.append("No errors. No warnings.\n");
            out.append("The program is valid for generation.\n");
            return out.toString();
        }

        for (Diagnostic diagnostic : reporter.diagnostics()) {
            out.append(diagnostic).append('\n');
        }
        out.append("========================================\n");
        out.append("Summary: ").append(errors).append(" error(s), ")
                .append(warnings).append(" warning(s)\n");
        out.append(errors == 0
                ? "The program is valid for generation.\n"
                : "Generation is blocked until all errors are fixed.\n");
        return out.toString();
    }
}
