package compilers.flask.vm.modules;

import compilers.flask.codegen.bytecode.VerifiedBytecodeModule;
import compilers.flask.vm.values.PyModule;

/**
 * VM-independent bridge used by {@link ModuleLoader} to initialize a
 * registered verified-bytecode module.
 *
 * <p>The bytecode VM supplies an implementation later.  Keeping this as an
 * injected interface lets the module cache, circular-import, and rollback
 * protocol remain independent from frame execution.</p>
 */
@FunctionalInterface
public interface BytecodeModuleInitializer {

    void initialize(
            VerifiedBytecodeModule bytecode,
            PyModule module,
            ModuleLoader loader);
}
