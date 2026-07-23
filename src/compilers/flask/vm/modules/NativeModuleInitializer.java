package compilers.flask.vm.modules;

import compilers.flask.vm.values.PyModule;

/**
 * Initializes one already-created native module.
 *
 * <p>The loader publishes the module as {@code IN_PROGRESS} before invoking
 * this callback.  Recursive imports therefore observe the same module
 * identity instead of creating another object.</p>
 */
@FunctionalInterface
public interface NativeModuleInitializer {

    void initialize(PyModule module, ModuleLoader loader);
}
