package compilers.flask.codegen.bytecode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable assembled module.  It is deliberately not an execution token. */
public final class BytecodeModule {
    private final int magic;
    private final int formatVersion;
    private final String sourceName;
    private final String moduleName;
    private final CodeObject rootCode;
    private final Map<String, String> metadata;

    public BytecodeModule(
            int magic,
            int formatVersion,
            String sourceName,
            String moduleName,
            CodeObject rootCode,
            Map<String, String> metadata) {
        this.magic = magic;
        this.formatVersion = formatVersion;
        this.sourceName = sourceName == null ? "" : sourceName;
        this.moduleName = requireName(moduleName);
        this.rootCode = Objects.requireNonNull(rootCode, "rootCode");
        LinkedHashMap<String, String> copiedMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                copiedMetadata.put(
                        Objects.requireNonNull(entry.getKey(), "metadata key"),
                        Objects.requireNonNull(entry.getValue(), "metadata value"));
            }
        }
        this.metadata = Collections.unmodifiableMap(copiedMetadata);
    }

    public BytecodeModule(
            String sourceName, String moduleName, CodeObject rootCode) {
        this(BytecodeFormat.MAGIC, BytecodeFormat.CURRENT_VERSION,
                sourceName, moduleName, rootCode, Collections.<String, String>emptyMap());
    }

    public int getMagic() { return magic; }
    public int getFormatVersion() { return formatVersion; }
    public String getSourceName() { return sourceName; }
    public String getModuleName() { return moduleName; }
    public CodeObject getRootCode() { return rootCode; }
    public Map<String, String> getMetadata() { return metadata; }

    public BytecodeModule withRootCode(CodeObject verifiedRoot) {
        return new BytecodeModule(
                magic, formatVersion, sourceName, moduleName,
                verifiedRoot, metadata);
    }

    private static String requireName(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Module name cannot be empty");
        }
        return value;
    }
}
