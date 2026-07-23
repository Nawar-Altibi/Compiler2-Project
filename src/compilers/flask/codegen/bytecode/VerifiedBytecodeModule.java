package compilers.flask.codegen.bytecode;

import compilers.flask.codegen.verify.ControlFlowVerifier;

import java.util.Objects;

/**
 * Execution capability produced only by successful control-flow verification.
 *
 * <p>The public factory is not a forgery path: its stamp type can only be
 * instantiated by {@link ControlFlowVerifier}, and the stamp is bound to the
 * exact immutable module identity and deterministic content fingerprint.</p>
 */
public final class VerifiedBytecodeModule {
    private final BytecodeModule module;
    private final int formatVersion;
    private final ControlFlowVerifier.VerificationStamp verificationStamp;

    private VerifiedBytecodeModule(
            BytecodeModule module,
            ControlFlowVerifier.VerificationStamp verificationStamp) {
        this.module = Objects.requireNonNull(module, "module");
        this.verificationStamp = Objects.requireNonNull(
                verificationStamp, "verificationStamp");
        if (!verificationStamp.matches(module)) {
            throw new IllegalArgumentException(
                    "Verification stamp does not match the exact module");
        }
        if (!BytecodeFormat.isSupported(module.getMagic(), module.getFormatVersion())) {
            throw new IllegalArgumentException("Verified module has unsupported format");
        }
        if (!module.getRootCode().isVerified()) {
            throw new IllegalArgumentException("Verified module has unverified root code");
        }
        this.formatVersion = module.getFormatVersion();
    }

    /** Bridge used by the verifier; callers cannot construct the required stamp. */
    public static VerifiedBytecodeModule fromVerifier(
            BytecodeModule module,
            ControlFlowVerifier.VerificationStamp verificationStamp) {
        return new VerifiedBytecodeModule(module, verificationStamp);
    }

    public BytecodeModule getModule() {
        return module;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    /** Recomputes and checks identity, magic, version, and verified content. */
    public boolean hasValidVerificationStamp() {
        return BytecodeFormat.isSupported(module.getMagic(), module.getFormatVersion())
                && formatVersion == BytecodeFormat.CURRENT_VERSION
                && module.getRootCode().isVerified()
                && verificationStamp.matches(module);
    }
}
