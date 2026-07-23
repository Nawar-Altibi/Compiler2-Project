package compilers.flask.codegen.bytecode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable assembled code definition.  A negative max stack means unverified. */
public final class CodeObject {
    private final int formatVersion;
    private final int codeId;
    private final CodeKind kind;
    private final String name;
    private final String qualifiedName;
    private final String sourceFile;
    private final List<Instruction> instructions;
    private final ConstantPool constantPool;
    private final NamePool namePool;
    private final FunctionSignature signature;
    private final List<String> fastLocalNames;
    private final List<String> cellVariableNames;
    private final List<String> freeVariableNames;
    private final int temporarySlotCount;
    private final List<ExceptionRegion> exceptionRegions;
    private final List<CleanupRegion> cleanupRegions;
    private final SourceMap sourceMap;
    private final List<StackAnchor> stackAnchors;
    private final int maxStack;

    public CodeObject(
            int formatVersion,
            int codeId,
            CodeKind kind,
            String name,
            String qualifiedName,
            String sourceFile,
            List<Instruction> instructions,
            ConstantPool constantPool,
            NamePool namePool,
            FunctionSignature signature,
            List<String> fastLocalNames,
            List<String> cellVariableNames,
            List<String> freeVariableNames,
            int temporarySlotCount,
            List<ExceptionRegion> exceptionRegions,
            List<CleanupRegion> cleanupRegions,
            SourceMap sourceMap,
            List<StackAnchor> stackAnchors,
            int maxStack) {
        if (formatVersion <= 0 || codeId < 0 || temporarySlotCount < 0 || maxStack < -1) {
            throw new IllegalArgumentException("Invalid code-object numeric metadata");
        }
        this.formatVersion = formatVersion;
        this.codeId = codeId;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.name = requireText(name, "name");
        this.qualifiedName = requireText(qualifiedName, "qualifiedName");
        this.sourceFile = sourceFile == null ? "" : sourceFile;
        this.instructions = immutable(instructions, "instructions");
        this.constantPool = Objects.requireNonNull(constantPool, "constantPool");
        this.namePool = Objects.requireNonNull(namePool, "namePool");
        this.signature = Objects.requireNonNull(signature, "signature");
        this.fastLocalNames = immutableDistinct(fastLocalNames, "fast local");
        this.cellVariableNames = immutableDistinct(cellVariableNames, "cell variable");
        this.freeVariableNames = immutableDistinct(freeVariableNames, "free variable");
        this.temporarySlotCount = temporarySlotCount;
        this.exceptionRegions = immutable(exceptionRegions, "exceptionRegions");
        this.cleanupRegions = immutable(cleanupRegions, "cleanupRegions");
        this.sourceMap = Objects.requireNonNull(sourceMap, "sourceMap");
        this.stackAnchors = immutable(stackAnchors, "stackAnchors");
        this.maxStack = maxStack;
        if (sourceMap.size() != this.instructions.size()) {
            throw new IllegalArgumentException("Source map must be dense for every instruction");
        }
        if (kind != CodeKind.FUNCTION && signature != FunctionSignature.EMPTY
                && !signature.getParameterNames().isEmpty()) {
            throw new IllegalArgumentException("Only function code may declare parameters");
        }
    }

    public int getFormatVersion() { return formatVersion; }
    public int getCodeId() { return codeId; }
    public CodeKind getKind() { return kind; }
    public String getName() { return name; }
    public String getQualifiedName() { return qualifiedName; }
    public String getSourceFile() { return sourceFile; }
    public List<Instruction> getInstructions() { return instructions; }
    public ConstantPool getConstantPool() { return constantPool; }
    public NamePool getNamePool() { return namePool; }
    public FunctionSignature getSignature() { return signature; }
    public List<String> getFastLocalNames() { return fastLocalNames; }
    public List<String> getCellVariableNames() { return cellVariableNames; }
    public List<String> getFreeVariableNames() { return freeVariableNames; }
    public int getTemporarySlotCount() { return temporarySlotCount; }
    public List<ExceptionRegion> getExceptionRegions() { return exceptionRegions; }
    public List<CleanupRegion> getCleanupRegions() { return cleanupRegions; }
    public SourceMap getSourceMap() { return sourceMap; }
    public List<StackAnchor> getStackAnchors() { return stackAnchors; }
    public int getMaxStack() { return maxStack; }
    public boolean isVerified() { return maxStack >= 0; }
    public List<CodeObject> getNestedCodeObjects() {
        return constantPool.getNestedCodeObjects();
    }

    public CodeObject withVerification(
            ConstantPool verifiedConstants,
            List<ExceptionRegion> verifiedExceptionRegions,
            List<CleanupRegion> verifiedCleanupRegions,
            List<StackAnchor> verifiedAnchors,
            int verifiedMaxStack) {
        if (verifiedMaxStack < 0) {
            throw new IllegalArgumentException("Verified maxStack cannot be negative");
        }
        return new CodeObject(
                formatVersion, codeId, kind, name, qualifiedName, sourceFile,
                instructions, verifiedConstants, namePool, signature,
                fastLocalNames, cellVariableNames, freeVariableNames,
                temporarySlotCount, verifiedExceptionRegions,
                verifiedCleanupRegions, sourceMap, verifiedAnchors,
                verifiedMaxStack);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " cannot be empty");
        }
        return value;
    }

    private static <T> List<T> immutable(List<T> source, String label) {
        Objects.requireNonNull(source, label);
        List<T> copy = new ArrayList<>(source.size());
        for (T value : source) {
            copy.add(Objects.requireNonNull(value, label + " entry"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<String> immutableDistinct(List<String> source, String label) {
        Objects.requireNonNull(source, label);
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String name : source) {
            if (name == null || name.isEmpty() || !distinct.add(name)) {
                throw new IllegalArgumentException("Invalid or duplicate " + label + ": " + name);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(distinct));
    }
}
