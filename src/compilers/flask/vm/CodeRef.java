package compilers.flask.vm;

import compilers.flask.codegen.bytecode.CodeObject;

import java.util.Objects;

/** Internal operand-stack reference to a verified nested code definition. */
public final class CodeRef implements VmStackValue {
    private final CodeObject codeObject;

    public CodeRef(CodeObject codeObject) {
        this.codeObject = Objects.requireNonNull(codeObject, "codeObject");
        if (!codeObject.isVerified()) {
            throw new IllegalArgumentException("CodeRef requires a verified CodeObject");
        }
    }

    public CodeObject getCodeObject() {
        return codeObject;
    }

    @Override
    public String toString() {
        return "<code-ref " + codeObject.getQualifiedName() + ">";
    }
}
