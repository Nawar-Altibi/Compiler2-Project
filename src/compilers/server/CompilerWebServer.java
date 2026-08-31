package compilers.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import compilers.pipeline.GenerationPipeline;
import compilers.pipeline.GenerationResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serves generated pages and persists list-backed CRUD changes into app.py. */
public final class CompilerWebServer implements AutoCloseable {
    private static final int MAX_FORM_BYTES = 64 * 1024;
    private static final long REBUILD_TIMEOUT_MILLIS = 15_000L;

    private final GenerationPipeline.ProjectPaths paths;
    private final int requestedPort;
    private final PrintStream out;
    private final PrintStream err;
    private final Object stateLock = new Object();
    private final Object mutationLock = new Object();

    private HttpServer server;
    private ExecutorService executor;
    private GenerationResult compilation;
    private GenerationResult latestCompilationAttempt;
    private Map<String, Object> liveGlobals = new LinkedHashMap<>();
    private long compilationAttempt;

    public CompilerWebServer(
            GenerationPipeline.ProjectPaths paths,
            int port,
            PrintStream out,
            PrintStream err) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
        this.paths = Objects.requireNonNull(paths, "paths");
        this.requestedPort = port;
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    public void start() throws IOException {
        synchronized (stateLock) {
            if (server != null) {
                return;
            }
            server = HttpServer.create(new InetSocketAddress(
                    InetAddress.getLoopbackAddress(), requestedPort), 0);
            server.createContext("/api/", this::handleCollectionRequest);
            server.createContext("/", this::handleStaticFile);
            executor = Executors.newFixedThreadPool(4);
            server.setExecutor(executor);
            server.start();
        }
        out.println("[SERVER] Java HTTP server started: " + getBaseUrl());
        out.println("[SERVER] Generated files: " + paths.outputDir);
        out.println("[SERVER] Collection API: GET/POST /api/<collection>");
        out.println("[SERVER] Item API      : GET/PUT/DELETE /api/<collection>/<id>");
        out.println("[SERVER] Persistence   : app.py -> watcher full rebuild");
        out.println();
        out.flush();
    }

    /** Replaces live state after a successful full compiler run. */
    public void acceptCompilation(GenerationResult result) {
        Objects.requireNonNull(result, "result");
        boolean success = result.isSuccess() && result.getProjectContext() != null;
        synchronized (stateLock) {
            compilationAttempt++;
            latestCompilationAttempt = result;
            if (success) {
                compilation = result;
                liveGlobals = copyGlobals(result.getProjectContext().getGlobals());
            }
            stateLock.notifyAll();
        }
        if (!success) {
            out.println("[SERVER] Compiler failed; keeping the last successful live snapshot.");
            out.flush();
            return;
        }
        out.println("[SERVER] Live context refreshed from app.py: "
                + liveGlobals.keySet());
        out.println("[SERVER] Open " + getBaseUrl() + "/index.html");
        out.println();
        out.flush();
    }

    public int getPort() {
        synchronized (stateLock) {
            return server == null ? requestedPort : server.getAddress().getPort();
        }
    }

    public String getBaseUrl() {
        return "http://localhost:" + getPort();
    }

    private void handleCollectionRequest(HttpExchange exchange) throws IOException {
        try {
            CollectionTarget target = collectionTarget(exchange);
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (target.itemId == null) {
                if ("GET".equals(method)) {
                    getCollection(exchange, target.collectionName);
                } else if ("POST".equals(method)) {
                    addItem(exchange, target.collectionName);
                } else {
                    methodNotAllowed(exchange, "GET, POST");
                }
            } else if ("GET".equals(method)) {
                getItem(exchange, target.collectionName, target.itemId);
            } else if ("PUT".equals(method)) {
                updateItem(exchange, target.collectionName, target.itemId);
            } else if ("DELETE".equals(method)) {
                deleteItem(exchange, target.collectionName, target.itemId);
            } else {
                methodNotAllowed(exchange, "GET, PUT, DELETE");
            }
        } catch (BadRequest failure) {
            sendText(exchange, failure.status, failure.getMessage());
        } catch (Exception failure) {
            err.println("[SERVER] API request failed: " + safeMessage(failure));
            err.flush();
            sendText(exchange, 500, "API request failed: " + safeMessage(failure));
        }
    }

    private CollectionTarget collectionTarget(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String relative = path.substring("/api/".length());
        String[] parts = relative.split("/", -1);
        if (parts.length == 0 || parts.length > 2 || parts[0].isEmpty()
                || (parts.length == 2 && parts[1].isEmpty())) {
            throw new BadRequest(404, "Collection endpoint not found");
        }
        return new CollectionTarget(parts[0], parts.length == 2 ? parts[1] : null);
    }

    private void getCollection(HttpExchange exchange, String collectionName)
            throws IOException {
        final String json;
        synchronized (stateLock) {
            requireCompilation();
            json = toJson(mutableCollection(collectionName));
        }
        sendJson(exchange, 200, json);
    }

    private void getItem(HttpExchange exchange, String collectionName, String itemId)
            throws IOException {
        final String json;
        synchronized (stateLock) {
            requireCompilation();
            List<Object> collection = mutableCollection(collectionName);
            json = toJson(requireItem(collectionName, collection, itemId));
        }
        sendJson(exchange, 200, json);
    }

    private void addItem(HttpExchange exchange, String collectionName)
            throws IOException {
        Map<String, String> form = readForm(exchange);
        int size;
        synchronized (mutationLock) {
            List<Object> updated;
            long previousAttempt;
            synchronized (stateLock) {
                requireCompilation();
                List<Object> collection = mutableCollection(collectionName);
                Map<String, Object> row = buildRow(collectionName, collection, form);
                updated = new ArrayList<>(collection);
                updated.add(row);
                previousAttempt = compilationAttempt;
            }
            persistAndAwaitRebuild(collectionName, updated, previousAttempt);
            size = updated.size();
        }

        out.println("[SERVER] POST /api/" + collectionName);
        out.println("[SERVER] Added one persistent item to '" + collectionName
                + "' in app.py (" + size + " total)");
        out.println("[SERVER] Redirecting browser to /index.html");
        out.println();
        out.flush();
        redirect(exchange, "/index.html");
    }

    private void updateItem(
            HttpExchange exchange, String collectionName, String itemId)
            throws IOException {
        Map<String, String> form = readForm(exchange);
        synchronized (mutationLock) {
            List<Object> updated;
            long previousAttempt;
            synchronized (stateLock) {
                requireCompilation();
                List<Object> collection = mutableCollection(collectionName);
                int index = requireItemIndex(collectionName, collection, itemId);
                Map<String, Object> current = requireMapItem(
                        collectionName, collection.get(index));
                Map<String, Object> replacement = buildUpdatedRow(
                        collectionName, current, form);
                updated = new ArrayList<>(collection);
                updated.set(index, replacement);
                previousAttempt = compilationAttempt;
            }
            persistAndAwaitRebuild(collectionName, updated, previousAttempt);
        }

        out.println("[SERVER] PUT /api/" + collectionName + "/" + itemId);
        out.println("[SERVER] Updated item " + itemId + " persistently in app.py");
        out.println();
        out.flush();
        sendNoContent(exchange);
    }

    private void deleteItem(
            HttpExchange exchange, String collectionName, String itemId)
            throws IOException {
        synchronized (mutationLock) {
            List<Object> updated;
            long previousAttempt;
            synchronized (stateLock) {
                requireCompilation();
                List<Object> collection = mutableCollection(collectionName);
                int index = requireItemIndex(collectionName, collection, itemId);
                updated = new ArrayList<>(collection);
                updated.remove(index);
                previousAttempt = compilationAttempt;
            }
            persistAndAwaitRebuild(collectionName, updated, previousAttempt);
        }

        out.println("[SERVER] DELETE /api/" + collectionName + "/" + itemId);
        out.println("[SERVER] Deleted item " + itemId + " persistently from app.py");
        out.println();
        out.flush();
        sendNoContent(exchange);
    }

    private void persistAndAwaitRebuild(
            String collectionName,
            List<Object> updated,
            long previousAttempt) throws IOException {
        out.println();
        out.println("[SOURCE] Updating '" + collectionName + "' inside "
                + paths.appPy.getFileName() + "...");
        out.flush();
        PythonSourceCollectionWriter.write(paths.appPy, collectionName, updated);
        out.println("[SOURCE] app.py saved successfully.");
        out.println("[SOURCE] Waiting for watcher: Lexer -> Parser -> AST -> "
                + "Semantic -> Context -> Jinja -> HTML");
        out.flush();

        awaitRebuild(collectionName, updated, previousAttempt);
        out.println("[SOURCE] Watcher rebuild confirmed. The change is now persistent.");
        out.flush();
    }

    private void awaitRebuild(
            String collectionName,
            List<Object> expected,
            long previousAttempt) throws IOException {
        long deadline = System.nanoTime()
                + REBUILD_TIMEOUT_MILLIS * 1_000_000L;
        long observedAttempt = previousAttempt;
        synchronized (stateLock) {
            while (true) {
                if (compilationAttempt > observedAttempt) {
                    observedAttempt = compilationAttempt;
                    GenerationResult attempt = latestCompilationAttempt;
                    if (attempt != null && !attempt.isSuccess()) {
                        throw new IOException("Watcher rebuild failed after updating app.py "
                                + "(exit code " + attempt.getExitCode() + ")");
                    }
                    if (attempt != null && attempt.isSuccess()
                            && Objects.equals(liveGlobals.get(collectionName), expected)) {
                        return;
                    }
                }

                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    throw new IOException("app.py was updated, but the watcher did not "
                            + "confirm the regenerated collection within "
                            + REBUILD_TIMEOUT_MILLIS + " ms");
                }
                try {
                    long millis = Math.max(1L, remaining / 1_000_000L);
                    stateLock.wait(millis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for watcher rebuild",
                            interrupted);
                }
            }
        }
    }

    private Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT)
                .startsWith("application/x-www-form-urlencoded")) {
            throw new BadRequest(400,
                    "Expected application/x-www-form-urlencoded form data");
        }

        byte[] body;
        try (InputStream input = exchange.getRequestBody()) {
            body = input.readNBytes(MAX_FORM_BYTES + 1);
        }
        if (body.length > MAX_FORM_BYTES) {
            throw new BadRequest(400, "Form body is too large");
        }

        Map<String, String> values = new LinkedHashMap<>();
        String encoded = new String(body, StandardCharsets.UTF_8);
        if (encoded.isEmpty()) {
            throw new BadRequest(400, "Form data is empty");
        }
        for (String pair : encoded.split("&")) {
            int equals = pair.indexOf('=');
            String rawKey = equals < 0 ? pair : pair.substring(0, equals);
            String rawValue = equals < 0 ? "" : pair.substring(equals + 1);
            String key = decode(rawKey);
            String value = decode(rawValue);
            if (key.isEmpty()) {
                throw new BadRequest(400, "Form field name is empty");
            }
            values.put(key, value);
        }
        return values;
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException failure) {
            throw new BadRequest(400, "Invalid form encoding");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> mutableCollection(String name) {
        Object value = liveGlobals.get(name);
        if (!(value instanceof List)) {
            throw new BadRequest(404,
                    "No editable list named '" + name + "' exists in app.py");
        }
        return (List<Object>) value;
    }

    private Map<String, Object> buildRow(
            String collectionName,
            List<Object> collection,
            Map<String, String> form) {
        Map<?, ?> schema = firstMap(collection);
        if (schema == null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", BigInteger.ONE);
            for (Map.Entry<String, String> entry : form.entrySet()) {
                requireValue(entry.getKey(), entry.getValue());
                row.put(entry.getKey(), entry.getValue());
            }
            return row;
        }

        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : schema.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new BadRequest(400,
                        "Collection '" + collectionName + "' has a non-string field name");
            }
            String field = (String) entry.getKey();
            String raw = form.get(field);
            if (raw == null && "id".equals(field) && entry.getValue() instanceof Number) {
                row.put(field, nextId(collection, entry.getValue()));
                continue;
            }
            if (raw == null) {
                throw new BadRequest(400, "Missing form field '" + field + "'");
            }
            requireValue(field, raw);
            row.put(field, convertValue(field, raw, entry.getValue()));
        }
        for (String field : form.keySet()) {
            if (!schema.containsKey(field)) {
                throw new BadRequest(400, "Unknown form field '" + field + "'");
            }
        }
        return row;
    }

    private Map<String, Object> buildUpdatedRow(
            String collectionName,
            Map<String, Object> current,
            Map<String, String> form) {
        if (!current.containsKey("id")) {
            throw new BadRequest(400,
                    "Collection '" + collectionName + "' does not expose item ids");
        }
        Map<String, Object> replacement = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : current.entrySet()) {
            String field = entry.getKey();
            if ("id".equals(field)) {
                replacement.put(field, entry.getValue());
                continue;
            }
            String raw = form.get(field);
            if (raw == null) {
                throw new BadRequest(400, "Missing form field '" + field + "'");
            }
            requireValue(field, raw);
            replacement.put(field, convertValue(field, raw, entry.getValue()));
        }
        for (String field : form.keySet()) {
            if ("id".equals(field) || !current.containsKey(field)) {
                throw new BadRequest(400, "Unknown or read-only form field '" + field + "'");
            }
        }
        return replacement;
    }

    private Object requireItem(
            String collectionName, List<Object> collection, String itemId) {
        return collection.get(requireItemIndex(collectionName, collection, itemId));
    }

    private int requireItemIndex(
            String collectionName, List<Object> collection, String itemId) {
        for (int index = 0; index < collection.size(); index++) {
            Object item = collection.get(index);
            if (!(item instanceof Map)) {
                continue;
            }
            Object id = ((Map<?, ?>) item).get("id");
            if (id != null && itemId.equals(String.valueOf(id))) {
                return index;
            }
        }
        throw new BadRequest(404,
                "Item '" + itemId + "' was not found in '" + collectionName + "'");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requireMapItem(String collectionName, Object item) {
        if (!(item instanceof Map)) {
            throw new BadRequest(400,
                    "Collection '" + collectionName + "' contains a non-object item");
        }
        Map<?, ?> map = (Map<?, ?>) item;
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw new BadRequest(400,
                        "Collection '" + collectionName + "' has a non-string field name");
            }
        }
        return (Map<String, Object>) map;
    }

    private Map<?, ?> firstMap(List<Object> collection) {
        for (Object item : collection) {
            if (item instanceof Map) {
                return (Map<?, ?>) item;
            }
        }
        return null;
    }

    private void requireValue(String field, String value) {
        if (value.trim().isEmpty()) {
            throw new BadRequest(400, "Form field '" + field + "' is required");
        }
    }

    private Object convertValue(String field, String raw, Object example) {
        try {
            if (example instanceof BigInteger) {
                return looksDecimal(raw) ? Double.valueOf(raw) : new BigInteger(raw);
            }
            if (example instanceof Double || example instanceof Float) {
                return Double.valueOf(raw);
            }
            if (example instanceof Byte || example instanceof Short
                    || example instanceof Integer || example instanceof Long) {
                return Long.valueOf(raw);
            }
            if (example instanceof Boolean) {
                if (!"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
                    throw new NumberFormatException("not a boolean");
                }
                return Boolean.valueOf(raw);
            }
            return raw;
        } catch (NumberFormatException failure) {
            throw new BadRequest(400,
                    "Invalid value for field '" + field + "': " + raw);
        }
    }

    private boolean looksDecimal(String value) {
        return value.indexOf('.') >= 0 || value.indexOf('e') >= 0
                || value.indexOf('E') >= 0;
    }

    private Object nextId(List<Object> collection, Object example) {
        long max = 0L;
        for (Object item : collection) {
            if (item instanceof Map) {
                Object id = ((Map<?, ?>) item).get("id");
                if (id instanceof Number) {
                    max = Math.max(max, ((Number) id).longValue());
                }
            }
        }
        long next = max + 1L;
        return example instanceof BigInteger
                ? BigInteger.valueOf(next)
                : Long.valueOf(next);
    }

    private void requireCompilation() {
        if (compilation == null || compilation.getProjectContext() == null) {
            throw new BadRequest(503,
                    "No successful compiler snapshot is available yet");
        }
    }

    private void handleStaticFile(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                methodNotAllowed(exchange, "GET, HEAD");
                return;
            }
            synchronized (stateLock) {
                requireCompilation();
            }

            String requestPath = exchange.getRequestURI().getPath();
            String relative = "/".equals(requestPath)
                    ? "index.html"
                    : requestPath.substring(1);
            Path outputRoot = paths.outputDir.toAbsolutePath().normalize();
            Path file = outputRoot.resolve(relative).normalize();
            if (!file.startsWith(outputRoot) || !Files.isRegularFile(file)) {
                sendText(exchange, 404, "File not found");
                return;
            }

            byte[] content = Files.readAllBytes(file);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType(file));
            headers.set("Cache-Control", "no-store");
            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(200, -1L);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(content);
            }
        } catch (BadRequest failure) {
            sendText(exchange, failure.status, failure.getMessage());
        } catch (Exception failure) {
            err.println("[SERVER] GET failed: " + safeMessage(failure));
            err.flush();
            sendText(exchange, 500, "Server error");
        }
    }

    private String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (name.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        }
        if (name.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1L);
        exchange.close();
    }

    private void methodNotAllowed(HttpExchange exchange, String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        sendText(exchange, 405, "Method not allowed");
    }

    private void sendText(HttpExchange exchange, int status, String message)
            throws IOException {
        byte[] content = (message + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, content.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(content);
        }
    }

    private void sendJson(HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] content = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, content.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(content);
        }
    }

    private void sendNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1L);
        exchange.close();
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return jsonString((String) value);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof List) {
            StringBuilder json = new StringBuilder("[");
            List<?> list = (List<?>) value;
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                json.append(toJson(list.get(index)));
            }
            return json.append(']').toString();
        }
        if (value instanceof Map) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new BadRequest(500, "Cannot serialize a non-string JSON field");
                }
                if (!first) {
                    json.append(',');
                }
                json.append(jsonString((String) entry.getKey()))
                        .append(':')
                        .append(toJson(entry.getValue()));
                first = false;
            }
            return json.append('}').toString();
        }
        throw new BadRequest(500,
                "Cannot serialize value of type " + value.getClass().getSimpleName());
    }

    private String jsonString(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.append('"').toString();
    }

    private Map<String, Object> copyGlobals(Map<String, Object> globals) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : globals.entrySet()) {
            copy.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return copy;
    }

    private Object copyValue(Object value) {
        if (value instanceof Map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                copy.put(copyValue(entry.getKey()), copyValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) {
                copy.add(copyValue(item));
            }
            return copy;
        }
        return value;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName()
                : message;
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            if (server != null) {
                server.stop(0);
                server = null;
            }
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }

    private static final class BadRequest extends RuntimeException {
        final int status;

        BadRequest(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final class CollectionTarget {
        final String collectionName;
        final String itemId;

        CollectionTarget(String collectionName, String itemId) {
            this.collectionName = collectionName;
            this.itemId = itemId;
        }
    }
}
