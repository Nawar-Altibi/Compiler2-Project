package Main;

import compilers.pipeline.GenerationPipeline;
import compilers.pipeline.ProjectWatcher;

import java.nio.file.Path;
import java.nio.file.Paths;

/** IntelliJ-friendly entry point for automatic app.py regeneration. */
public final class WatchProjectMain {
    private static final String SAMPLE_PROJECT = "Tests/generation/sample_project";

    private WatchProjectMain() {
    }

    public static void main(String[] args) {
        if (args != null && args.length > 1) {
            System.err.println("Usage: Main.WatchProjectMain [project-dir|app.py]");
            System.exit(3);
            return;
        }

        Path source = Paths.get(args == null || args.length == 0
                ? SAMPLE_PROJECT
                : args[0]);
        try {
            GenerationPipeline.ProjectPaths paths =
                    GenerationPipeline.ProjectPaths.resolve(source, null, null);
            try (ProjectWatcher watcher =
                         new ProjectWatcher(paths, System.out, System.err)) {
                watcher.watch();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.out.println("[WATCHER] Stopped.");
        } catch (Exception failure) {
            System.err.println("[WATCHER] Cannot start: " + safeMessage(failure));
            System.exit(3);
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName()
                : message;
    }
}
