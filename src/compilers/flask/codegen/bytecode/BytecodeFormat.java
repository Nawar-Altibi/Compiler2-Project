package compilers.flask.codegen.bytecode;

/** Frozen format identity for the custom Flask bytecode. */
public final class BytecodeFormat {
    public static final int MAGIC = 0x46424300; // ASCII "FBC\0"
    public static final int CURRENT_VERSION = 1;

    private BytecodeFormat() {
    }

    public static boolean isSupported(int magic, int version) {
        return magic == MAGIC && version == CURRENT_VERSION;
    }

    public static void requireSupported(int magic, int version) {
        if (!isSupported(magic, version)) {
            throw new IllegalArgumentException(
                    "Unsupported bytecode format: magic=0x"
                            + Integer.toHexString(magic) + ", version=" + version);
        }
    }
}
