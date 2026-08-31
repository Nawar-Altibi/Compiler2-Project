package Main;

import compilers.pipeline.GenerationPipeline;
import compilers.pipeline.ProjectWatcher;
import compilers.server.CompilerWebServer;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Starts generation, app.py watching, and the local Java HTTP server together. */
public final class LiveProjectMain {
    private static final String SAMPLE_PROJECT = "Tests/generation/sample_project";
    private static final int DEFAULT_PORT = 8080;

    private LiveProjectMain() {
    }

    public static void main(String[] args) {
        if (args != null && args.length > 2) {
            fail("Usage: Main.LiveProjectMain [project-dir|app.py] [port]");
            return;
        }

        try {
            Path source = Paths.get(args == null || args.length == 0
                    ? SAMPLE_PROJECT
                    : args[0]);
            int port = args != null && args.length == 2
                    ? parsePort(args[1])
                    : DEFAULT_PORT;
            GenerationPipeline.ProjectPaths paths =
                    GenerationPipeline.ProjectPaths.resolve(source, null, null);

            try (CompilerWebServer server =
                         new CompilerWebServer(paths, port, System.out, System.err)) {
                server.start();
                try (ProjectWatcher watcher = new ProjectWatcher(
                        paths, System.out, System.err, server::acceptCompilation)) {
                    watcher.watch();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.out.println("[LIVE] Stopped.");
        } catch (Exception failure) {
            fail(safeMessage(failure));
        }
    }

    private static int parsePort(String raw) {
        try {
            int port = Integer.parseInt(raw);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid server port: " + raw);
        }
    }

    private static void fail(String message) {
        System.err.println("[LIVE] Cannot start: " + message);
        System.exit(3);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName()
                : message;
    }
}
