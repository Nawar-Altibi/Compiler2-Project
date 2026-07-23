package compilers.report;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered, deterministic build of {@code compiler_output/generation_log.txt}
 * (plan section 7.3) — the human-readable proof of the generation stage and
 * the home of the "Python data-prep executed" narrative.
 */
public final class GenerationLog {

    private final List<String> lines = new ArrayList<>();

    public void parse(String message) {
        add("[parse]", message);
    }

    public void semantic(String message) {
        add("[semantic]", message);
    }

    public void render(String message) {
        add("[render]", message);
    }

    public void copy(String message) {
        add("[copy]", message);
    }

    public void warn(String message) {
        add("[warn]", message);
    }

    public void error(String message) {
        add("[error]", message);
    }

    public void done(String message) {
        add("[done]", message);
    }

    /** Adopts pre-formatted lines (extractor/renderer already prefix them). */
    public void adopt(List<String> preformatted) {
        lines.addAll(preformatted);
    }

    private void add(String prefix, String message) {
        // Fixed-width prefixes keep the log grep-able and diff-stable.
        lines.add(String.format("%-11s %s", prefix, message));
    }

    public List<String> lines() {
        return new ArrayList<>(lines);
    }

    public String text() {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
