package compilers.flask.vm;

/**
 * Closed conceptual root for values that may appear on the VM operand stack.
 * Python-visible values implement this through {@code PyValue}; closure and
 * code references use dedicated internal wrappers.
 */
public interface VmStackValue {
}
