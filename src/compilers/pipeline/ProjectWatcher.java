package compilers.pipeline;

import compilers.diagnostics.Diagnostic;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Watches project sources and reruns the complete compiler after saved changes. */
public final class ProjectWatcher implements AutoCloseable {
    private static final long DEBOUNCE_MILLIS = 300L;
    private static final String PIPELINE_DESCRIPTION =
            "Python Lexer -> Parser -> AST -> Semantic -> Context -> Jinja -> HTML";
    private static final Set<String> WATCHED_EXTENSIONS = Set.of(
            ".py", ".html", ".jinja", ".j2", ".css", ".js",
            ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".ico",
            ".woff", ".woff2", ".ttf", ".otf");
    private static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
            "output", "compiler_output", "build", "out", "gen",
            ".git", ".idea", ".tmp", "__pycache__");

    private final GenerationPipeline.ProjectPaths paths;
    private final PrintStream out;
    private final PrintStream err;
    private final WatchService watchService;
    private final Path projectRoot;
    private final Map<WatchKey, Path> watchedDirectories = new LinkedHashMap<>();
    private final CompilationListener compilationListener;
    private volatile boolean running = true;
    private int runNumber;

    public ProjectWatcher(
            GenerationPipeline.ProjectPaths paths,
            PrintStream out,
            PrintStream err) throws IOException {
        this(paths, out, err, result -> { });
    }

    public ProjectWatcher(
            GenerationPipeline.ProjectPaths paths,
            PrintStream out,
            PrintStream err,
            CompilationListener compilationListener) throws IOException {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.compilationListener = Objects.requireNonNull(
                compilationListener, "compilationListener");
        this.projectRoot = paths.projectDir.toAbsolutePath().normalize();
        this.watchService = FileSystems.getDefault().newWatchService();
        try {
            registerAll(projectRoot);
        } catch (IOException failure) {
            watchService.close();
            throw failure;
        }
    }

    /** Runs an initial build, then blocks while watching project source changes. */
    public void watch() throws InterruptedException {
        printBanner();
        runCompiler(null);

        while (running) {
            SourceChange change = awaitSourceChange();
            if (!running) {
                break;
            }
            if (change == null) {
                continue;
            }

            printChange(change);
            if (!Files.isRegularFile(paths.appPy)) {
                out.println("[WATCHER] app.py is currently missing.");
                out.println("[WATCHER] Generation skipped; waiting for app.py to be restored.");
                out.println();
                out.flush();
                continue;
            }
            runCompiler(change);
        }
    }

    private void printBanner() {
        out.println("============================================================");
        out.println(" FLASK COMPILER - AUTOMATIC WATCH MODE");
        out.println("============================================================");
        out.println("[WATCHER] Project : " + paths.projectDir);
        out.println("[WATCHER] Sources : Python, Jinja/HTML, CSS/JS, static assets");
        out.println("[WATCHER] Ignored : output, compiler_output, build folders");
        out.println("[WATCHER] Pipeline: " + PIPELINE_DESCRIPTION);
        out.println("[WATCHER] Stop    : use the IntelliJ Stop button or Ctrl+C");
        out.println("============================================================");
        out.println();
        out.flush();
    }

    private void printChange(SourceChange change) {
        out.println();
        out.println("============================================================");
        out.println(change.pythonOnly()
                ? "[WATCHER] PYTHON SOURCE CHANGE DETECTED"
                : "[WATCHER] PROJECT SOURCE CHANGE DETECTED");
        for (Map.Entry<Path, ChangeFlags> entry : change.files.entrySet()) {
            out.println("[WATCHER] File : " + displayPath(entry.getKey())
                    + " (" + entry.getValue().label() + ")");
        }
        out.println("[WATCHER] The compiler will restart from the Python Lexer.");
        out.println("============================================================");
        out.flush();
    }

    private String displayPath(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        return absolute.startsWith(projectRoot)
                ? projectRoot.relativize(absolute).toString()
                : absolute.toString();
    }

    private void runCompiler(SourceChange change) {
        runNumber++;
        out.println("[COMPILER] Run #" + runNumber
                + (change == null ? " - INITIAL BUILD" : " - AUTOMATIC REBUILD"));
        out.println("[COMPILER] Restart point: Flask/Python Lexer");
        out.println("[COMPILER] Pipeline     : " + PIPELINE_DESCRIPTION);
        out.println();
        out.flush();

        try {
            GenerationResult result = new GenerationPipeline().run(paths);
            out.print(result.getLogText());
            for (Diagnostic diagnostic : result.getReporter().diagnostics()) {
                if (diagnostic.isError() || diagnostic.isWarning()) {
                    err.println("[DIAGNOSTIC] " + diagnostic);
                }
            }

            if (result.isSuccess()) {
                out.println("[COMPILER] RESULT: SUCCESS");
                out.println("[COMPILER] Generated pages ("
                        + result.getGeneratedPages().size() + "): "
                        + pageList(result));
            } else {
                out.println("[COMPILER] RESULT: FAILED (exit code "
                        + result.getExitCode() + ")");
                out.println("[COMPILER] Fix the source and save it; the watcher will try again.");
            }
            out.println("[COMPILER] Output   : " + result.getOutputDir());
            out.println("[COMPILER] Dashboard: "
                    + result.getReportsDir().resolve("report.html"));
            notifyListener(result);
        } catch (Exception failure) {
            err.println("[COMPILER] RESULT: INTERNAL FAILURE");
            err.println("[COMPILER] " + safeMessage(failure));
        }

        out.println("[WATCHER] READY - waiting for the next project source save...");
        out.println();
        out.flush();
        err.flush();
    }

    private void notifyListener(GenerationResult result) {
        try {
            compilationListener.onCompilation(result);
        } catch (RuntimeException failure) {
            err.println("[WATCHER] Compilation listener failed: "
                    + safeMessage(failure));
        }
    }

    private String pageList(GenerationResult result) {
        if (result.getGeneratedPages().isEmpty()) {
            return "(none)";
        }
        return String.join(", ", result.getGeneratedPages());
    }

    private SourceChange awaitSourceChange() throws InterruptedException {
        final WatchKey first;
        try {
            first = watchService.take();
        } catch (ClosedWatchServiceException closed) {
            return null;
        }

        SourceChange change = new SourceChange();
        collect(first, change);

        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(DEBOUNCE_MILLIS);
        while (running) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            final WatchKey extra;
            try {
                extra = watchService.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (ClosedWatchServiceException closed) {
                return null;
            }
            if (extra == null) {
                break;
            }
            collect(extra, change);
            deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(DEBOUNCE_MILLIS);
        }
        return change.detected() ? change : null;
    }

    private void collect(WatchKey key, SourceChange change) {
        Path directory = watchedDirectories.get(key);
        if (directory == null) {
            key.reset();
            return;
        }

        for (WatchEvent<?> event : key.pollEvents()) {
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == StandardWatchEventKinds.OVERFLOW) {
                continue;
            }
            Object context = event.context();
            if (!(context instanceof Path)) {
                continue;
            }
            Path fullPath = directory.resolve((Path) context)
                    .toAbsolutePath().normalize();
            if (isIgnoredPath(fullPath)) {
                continue;
            }

            if (kind == StandardWatchEventKinds.ENTRY_CREATE
                    && Files.isDirectory(fullPath)) {
                try {
                    registerAll(fullPath);
                } catch (IOException failure) {
                    err.println("[WATCHER] Cannot watch new directory "
                            + fullPath + ": " + safeMessage(failure));
                }
                if (isSourceDirectory(fullPath)) {
                    change.record(fullPath, kind);
                }
                continue;
            }

            if (isWatchedSource(fullPath)) {
                change.record(fullPath, kind);
            }
        }

        if (!key.reset()) {
            watchedDirectories.remove(key);
            if (directory.equals(projectRoot) && running) {
                err.println("[WATCHER] The project directory is no longer available: "
                        + projectRoot);
                running = false;
            }
        }
    }

    private void registerAll(Path start) throws IOException {
        final Path normalizedStart = start.toAbsolutePath().normalize();
        Files.walkFileTree(normalizedStart, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory, BasicFileAttributes attributes) throws IOException {
                Path normalized = directory.toAbsolutePath().normalize();
                if (!normalized.equals(normalizedStart) && isIgnoredPath(normalized)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                WatchKey key = normalized.register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                watchedDirectories.put(key, normalized);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean isWatchedSource(Path path) {
        String name = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : WATCHED_EXTENSIONS) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return isInsideNamedDirectory(path, "assets")
                || isInsideNamedDirectory(path, "static");
    }

    private boolean isSourceDirectory(Path path) {
        return isInsideNamedDirectory(path, "templates")
                || isInsideNamedDirectory(path, "assets")
                || isInsideNamedDirectory(path, "static");
    }

    private boolean isInsideNamedDirectory(Path path, String expectedName) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(projectRoot)) {
            return false;
        }
        for (Path part : projectRoot.relativize(absolute)) {
            if (expectedName.equals(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isIgnoredPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.startsWith(paths.outputDir.toAbsolutePath().normalize())
                || absolute.startsWith(paths.reportsDir.toAbsolutePath().normalize())) {
            return true;
        }
        if (!absolute.startsWith(projectRoot)) {
            return false;
        }
        for (Path part : projectRoot.relativize(absolute)) {
            if (IGNORED_DIRECTORY_NAMES.contains(
                    part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPython(Path file) {
        return file.getFileName() != null
                && file.getFileName().toString().toLowerCase(Locale.ROOT)
                .endsWith(".py");
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName()
                : message;
    }

    @Override
    public void close() throws IOException {
        running = false;
        watchService.close();
    }

    @FunctionalInterface
    public interface CompilationListener {
        void onCompilation(GenerationResult result);
    }

    private static final class SourceChange {
        final Map<Path, ChangeFlags> files = new LinkedHashMap<>();

        void record(Path file, WatchEvent.Kind<?> kind) {
            files.computeIfAbsent(file, ignored -> new ChangeFlags()).record(kind);
        }

        boolean detected() {
            return !files.isEmpty();
        }

        boolean pythonOnly() {
            if (files.isEmpty()) {
                return false;
            }
            for (Path file : files.keySet()) {
                if (!isPython(file)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class ChangeFlags {
        boolean created;
        boolean modified;
        boolean deleted;

        void record(WatchEvent.Kind<?> kind) {
            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                created = true;
            } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                modified = true;
            } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                deleted = true;
            }
        }

        String label() {
            if (created && deleted) {
                return "REPLACED";
            }
            if (created) {
                return "CREATED";
            }
            if (deleted) {
                return "DELETED";
            }
            return modified ? "MODIFIED" : "CHANGED";
        }
    }
}
