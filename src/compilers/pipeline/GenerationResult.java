package compilers.pipeline;

import compilers.diagnostics.DiagnosticReporter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of one generation run (plan section 8.1): the frozen exit code,
 * the produced page list, both output folders, the shared diagnostics, and
 * the final generation-log text.
 *
 * <p>Exit codes (frozen, section 8.2): 0 success (warnings allowed),
 * 1 compile/semantic error, 2 generation/render error, 3 CLI/IO error.</p>
 */
public final class GenerationResult {
    private final int exitCode;
    private final Path outputDir;
    private final Path reportsDir;
    private final List<String> generatedPages;
    private final DiagnosticReporter reporter;
    private final String logText;

    public GenerationResult(
            int exitCode,
            Path outputDir,
            Path reportsDir,
            List<String> generatedPages,
            DiagnosticReporter reporter,
            String logText) {
        this.exitCode = exitCode;
        this.outputDir = outputDir;
        this.reportsDir = reportsDir;
        this.generatedPages = Collections.unmodifiableList(
                new ArrayList<>(generatedPages));
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.logText = logText == null ? "" : logText;
    }

    public int getExitCode() {
        return exitCode;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public Path getReportsDir() {
        return reportsDir;
    }

    public List<String> getGeneratedPages() {
        return generatedPages;
    }

    public DiagnosticReporter getReporter() {
        return reporter;
    }

    public String getLogText() {
        return logText;
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }
}
