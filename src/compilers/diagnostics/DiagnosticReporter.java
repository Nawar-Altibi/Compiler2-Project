package compilers.diagnostics;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Collects diagnostics from all phases while preserving report order. */
public class DiagnosticReporter {
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    public void report(Diagnostic diagnostic) {
        Diagnostic value = Objects.requireNonNull(diagnostic, "diagnostic");
        if (!diagnostics.contains(value)) {
            diagnostics.add(value);
        }
    }

    public void reportAll(Iterable<Diagnostic> values) {
        Objects.requireNonNull(values, "values");
        for (Diagnostic diagnostic : values) {
            report(diagnostic);
        }
    }

    public List<Diagnostic> diagnostics() {
        return Collections.unmodifiableList(new ArrayList<>(diagnostics));
    }

    public List<Diagnostic> errors() {
        return bySeverity(DiagnosticSeverity.ERROR);
    }

    public List<Diagnostic> warnings() {
        return bySeverity(DiagnosticSeverity.WARNING);
    }

    private List<Diagnostic> bySeverity(DiagnosticSeverity severity) {
        List<Diagnostic> result = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.severity() == severity) {
                result.add(diagnostic);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public boolean hasErrors() {
        return hasSeverity(DiagnosticSeverity.ERROR);
    }

    public boolean hasWarnings() {
        return hasSeverity(DiagnosticSeverity.WARNING);
    }

    private boolean hasSeverity(DiagnosticSeverity severity) {
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.severity() == severity) {
                return true;
            }
        }
        return false;
    }

    public boolean hasDiagnostics() {
        return !diagnostics.isEmpty();
    }

    public void clear() {
        diagnostics.clear();
    }

    public void printAll() {
        printAll(System.err);
    }

    public void printAll(PrintStream output) {
        Objects.requireNonNull(output, "output");
        for (Diagnostic diagnostic : diagnostics) {
            output.println(diagnostic);
        }
    }

    public void printErrors() {
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.isError()) {
                System.err.println(diagnostic);
            }
        }
    }
}
