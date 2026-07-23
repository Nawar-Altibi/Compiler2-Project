package compilers.flask.vm;

import compilers.flask.vm.flask.FlaskExecutionContext;

import java.util.Objects;

/** Mutable services isolated to one top-level VM execution. */
public final class RuntimeServices {
    private final VmCapabilities capabilities;
    private final FlaskExecutionContext flask = new FlaskExecutionContext();

    public RuntimeServices(VmCapabilities capabilities) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    public static RuntimeServices unavailable() {
        return new RuntimeServices(VmCapabilities.NONE);
    }

    public VmCapabilities getCapabilities() {
        return capabilities;
    }

    public FlaskExecutionContext getFlask() {
        return flask;
    }
}
