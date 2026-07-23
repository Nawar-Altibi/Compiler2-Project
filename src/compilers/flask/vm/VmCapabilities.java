package compilers.flask.vm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;

/** Explicit host capabilities; no VM operation receives ambient host access. */
public final class VmCapabilities {
    @FunctionalInterface
    public interface FileSystemRead {
        /** Returns deterministic file content or throws a normalized VM error. */
        String readText(String path);
    }

    @FunctionalInterface
    public interface FileSystemExists {
        /** Queries a sandboxed path or throws a normalized VM error. */
        boolean exists(String path);
    }

    public static final VmCapabilities NONE = new VmCapabilities(null, null);

    private final FileSystemRead fileSystemRead;
    private final FileSystemExists fileSystemExists;

    private VmCapabilities(
            FileSystemRead fileSystemRead,
            FileSystemExists fileSystemExists) {
        this.fileSystemRead = fileSystemRead;
        this.fileSystemExists = fileSystemExists;
    }

    public static VmCapabilities withFileSystemRead(FileSystemRead reader) {
        return new VmCapabilities(Objects.requireNonNull(reader, "reader"), null);
    }

    /** Creates an in-memory/host-sandboxed read and path-query capability. */
    public static VmCapabilities withFileSystemRead(
            FileSystemRead reader,
            FileSystemExists exists) {
        return new VmCapabilities(
                Objects.requireNonNull(reader, "reader"),
                Objects.requireNonNull(exists, "exists"));
    }

    /**
     * Creates a real read-only host capability confined to one canonical root.
     * Absolute paths outside the root, traversal, and symlinks resolving
     * outside that root are rejected before content is exposed to bytecode.
     */
    public static VmCapabilities rootedFileSystemRead(Path allowedRoot) {
        final Path root;
        try {
            root = Objects.requireNonNull(allowedRoot, "allowedRoot")
                    .toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Filesystem capability root is not accessible", failure);
        }
        FileSystemRead reader = requested -> {
            Path candidate = resolveInsideRoot(root, requested, true);
            try {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            } catch (java.nio.file.NoSuchFileException missing) {
                throw RuntimeOps.error("FileNotFoundError", "file not found: " + requested);
            } catch (java.nio.file.AccessDeniedException denied) {
                throw RuntimeOps.error("PermissionError", "cannot read file: " + requested);
            } catch (IOException failure) {
                throw RuntimeOps.error("OSError", "cannot read file: " + requested);
            }
        };
        FileSystemExists exists = requested -> {
            Path lexical = resolveLexicallyInsideRoot(root, requested);
            if (!Files.exists(lexical, LinkOption.NOFOLLOW_LINKS)) return false;
            try {
                resolveInsideRoot(root, requested, true);
                return true;
            } catch (VmRuntimeException failure) {
                if ("FileNotFoundError".equals(
                        failure.getExceptionValue().getExceptionTypeName())) {
                    return false;
                }
                throw failure;
            }
        };
        return new VmCapabilities(reader, exists);
    }

    public Optional<FileSystemRead> getFileSystemRead() {
        return Optional.ofNullable(fileSystemRead);
    }

    public Optional<FileSystemExists> getFileSystemExists() {
        return Optional.ofNullable(fileSystemExists);
    }

    public String readText(String path) {
        if (fileSystemRead == null) {
            throw RuntimeOps.error(
                    "PermissionError", "filesystem read capability is not available");
        }
        if (path == null || path.isEmpty()) {
            throw RuntimeOps.error("ValueError", "path cannot be empty");
        }
        try {
            String result = fileSystemRead.readText(path);
            if (result == null) {
                throw RuntimeOps.error(
                        "OSError", "filesystem capability returned no content");
            }
            return result;
        } catch (VmRuntimeException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw RuntimeOps.error("OSError", "filesystem read capability failed");
        }
    }

    public boolean exists(String path) {
        if (fileSystemExists == null) {
            throw RuntimeOps.error(
                    "PermissionError", "filesystem query capability is not available");
        }
        requirePath(path);
        try {
            return fileSystemExists.exists(path);
        } catch (VmRuntimeException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw RuntimeOps.error("OSError", "filesystem query capability failed");
        }
    }

    private static Path resolveInsideRoot(
            Path root,
            String requested,
            boolean requireExisting) {
        Path lexical = resolveLexicallyInsideRoot(root, requested);
        try {
            Path resolved = requireExisting ? lexical.toRealPath() : lexical;
            if (!resolved.startsWith(root)) {
                throw RuntimeOps.error(
                        "PermissionError", "path escapes filesystem capability root");
            }
            return resolved;
        } catch (java.nio.file.NoSuchFileException missing) {
            throw RuntimeOps.error("FileNotFoundError", "file not found: " + requested);
        } catch (IOException failure) {
            throw RuntimeOps.error("OSError", "cannot resolve path: " + requested);
        }
    }

    private static Path resolveLexicallyInsideRoot(Path root, String requested) {
        requirePath(requested);
        final Path raw;
        try {
            raw = Paths.get(requested);
        } catch (RuntimeException invalid) {
            throw RuntimeOps.error("ValueError", "invalid filesystem path");
        }
        Path candidate = raw.isAbsolute() ? raw.normalize() : root.resolve(raw).normalize();
        if (!candidate.startsWith(root)) {
            throw RuntimeOps.error(
                    "PermissionError", "path escapes filesystem capability root");
        }
        return candidate;
    }

    private static void requirePath(String path) {
        if (path == null || path.isEmpty()) {
            throw RuntimeOps.error("ValueError", "path cannot be empty");
        }
    }
}
