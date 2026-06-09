package compilers.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DiagnosticReporter {
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    public void report(Diagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    public List<Diagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(Diagnostic::isError).toList();
    }

    public List<Diagnostic> warnings() {
        return diagnostics.stream().filter(Diagnostic::isWarning).toList();
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(Diagnostic::isError);
    }

    public boolean hasWarnings() {
        return diagnostics.stream().anyMatch(Diagnostic::isWarning);
    }

    public boolean hasDiagnostics() {
        return !diagnostics.isEmpty();
    }

    public void printAll() {
        for (Diagnostic diagnostic : diagnostics) {
            System.err.println(diagnostic);
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
