import compilers.diagnostics.CompilerPhase;
import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticCategory;
import compilers.diagnostics.DiagnosticReporter;
import compilers.diagnostics.Diagnostics;
import compilers.flask.SymbolTable.SymbolTableBuilder;
import compilers.flask.antlr_gen.FlaskLexer;
import compilers.flask.antlr_gen.FlaskParser;
import compilers.flask.ast.builder.ASTBuilder;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.ast.nodes.statements.compound.ClassDefNode;
import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;
import compilers.flask.codegen.analysis.BindingAnalysisResult;
import compilers.flask.codegen.analysis.BindingKind;
import compilers.flask.codegen.analysis.BindingResolver;
import compilers.flask.codegen.analysis.NameOccurrence;
import compilers.flask.codegen.analysis.ResolvedName;
import compilers.flask.codegen.analysis.ScopeLayout;
import compilers.flask.runtime.RuntimeSymbolManifest;
import compilers.flask.runtime.RuntimeSymbolSpec;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Dependency-free executable tests for Flask binding and scope layouts. */
public final class FlaskBindingAnalysisHarness {
    private static int passed;
    private static int failed;

    private FlaskBindingAnalysisHarness() {
    }

    public static void main(String[] args) {
        run("module names and fast locals",
                FlaskBindingAnalysisHarness::testModuleAndFastBindings);
        run("global applies to the whole function",
                FlaskBindingAnalysisHarness::testGlobalBindings);
        run("shared closure cells",
                FlaskBindingAnalysisHarness::testSharedClosureCells);
        run("recursive nested function",
                FlaskBindingAnalysisHarness::testRecursiveNestedFunction);
        run("method lookup skips class namespace",
                FlaskBindingAnalysisHarness::testMethodSkipsClassScope);
        run("class closure capture and carrier",
                FlaskBindingAnalysisHarness::testClassClosureCarrier);
        run("definition-time evaluation scopes",
                FlaskBindingAnalysisHarness::testDefinitionTimeScopes);
        run("all helper-string binding sites",
                FlaskBindingAnalysisHarness::testBindingSiteRolesAndSpans);
        run("runtime symbol dependencies retain manifest status",
                FlaskBindingAnalysisHarness::testRuntimeSymbolDependencies);
        run("stable identities and immutable results",
                FlaskBindingAnalysisHarness::testStableAndImmutableResult);
        run("binding diagnostic phase contract",
                FlaskBindingAnalysisHarness::testBindingDiagnosticContract);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " binding test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " Flask binding tests passed.");
    }

    private static void testModuleAndFastBindings() {
        Analysis analysis = analyze(lines(
                "x = 1",
                "def f(a):",
                "    b = a + x",
                "    del b",
                "    return a"));
        ScopeLayout module = analysis.binding.getRootScope();
        ScopeLayout function = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.FUNCTION, "f");

        equal(List.of("x", "f"), module.getLocalNames(), "module locals");
        equal(List.of(), module.getFastLocalNames(), "module fast locals");
        equal(0, module.getTemporarySlotCount(), "module temporary slots");

        equal(List.of("a"), function.getParameterNames(), "parameters");
        equal(List.of("a", "b"), function.getLocalNames(), "function locals");
        equal(List.of("a", "b"), function.getFastLocalNames(), "fast locals");
        equal(List.of(), function.getCellVars(), "cell vars");
        equal(List.of(), function.getFreeVars(), "free vars");
        equal(0, function.getFastSlot("a"), "a fast slot");
        equal(1, function.getFastSlot("b"), "b fast slot");

        assertOnlyKind(analysis.binding, module, "x", NameOccurrence.Role.STORE,
                BindingKind.NAME, module);
        assertOnlyKind(analysis.binding, module, "f",
                NameOccurrence.Role.FUNCTION_DEFINITION, BindingKind.NAME, module);
        assertOnlyKind(analysis.binding, function, "a",
                NameOccurrence.Role.PARAMETER, BindingKind.FAST, function);
        assertEveryKind(analysis.binding, function, "a", NameOccurrence.Role.LOAD,
                BindingKind.FAST, function, 2);
        assertOnlyKind(analysis.binding, function, "b", NameOccurrence.Role.STORE,
                BindingKind.FAST, function);
        assertOnlyKind(analysis.binding, function, "b", NameOccurrence.Role.DELETE,
                BindingKind.FAST, function);
        assertOnlyKind(analysis.binding, function, "x", NameOccurrence.Role.LOAD,
                BindingKind.GLOBAL, module);
    }

    private static void testGlobalBindings() {
        Analysis analysis = analyze(lines(
                "shared = 0",
                "def update(value):",
                "    global shared",
                "    shared = value",
                "    del shared",
                "    return shared"));
        ScopeLayout module = analysis.binding.getRootScope();
        ScopeLayout function = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.FUNCTION, "update");

        equal(List.of("value"), function.getLocalNames(), "global function locals");
        equal(List.of("shared"), function.getGlobalDeclarations(),
                "global declarations");
        check(!function.isLocal("shared"), "global name leaked into function locals");

        assertOnlyKind(analysis.binding, function, "shared",
                NameOccurrence.Role.GLOBAL_DECLARATION, BindingKind.GLOBAL, module);
        assertOnlyKind(analysis.binding, function, "shared", NameOccurrence.Role.STORE,
                BindingKind.GLOBAL, module);
        assertOnlyKind(analysis.binding, function, "shared", NameOccurrence.Role.DELETE,
                BindingKind.GLOBAL, module);
        assertOnlyKind(analysis.binding, function, "shared", NameOccurrence.Role.LOAD,
                BindingKind.GLOBAL, module);
        assertEveryKind(analysis.binding, function, "value", NameOccurrence.Role.LOAD,
                BindingKind.FAST, function, 1);
    }

    private static void testSharedClosureCells() {
        Analysis analysis = analyze(lines(
                "def outer(a):",
                "    x = a",
                "    def first():",
                "        return x",
                "    def second():",
                "        return x",
                "    x = 2",
                "    return first"));
        ScopeLayout outer = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.FUNCTION, "outer");
        ScopeLayout first = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.FUNCTION, "first");
        ScopeLayout second = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.FUNCTION, "second");

        equal(List.of("x"), outer.getCellVars(), "outer cells");
        equal(List.of("a", "first", "second"), outer.getFastLocalNames(),
                "outer fast locals");
        equal(List.of("x"), first.getFreeVars(), "first free vars");
        equal(List.of("x"), second.getFreeVars(), "second free vars");
        equal(0, outer.getCellSlot("x"), "outer x cell slot");
        equal(0, first.getFreeSlot("x"), "first x free slot");
        equal(0, second.getFreeSlot("x"), "second x free slot");

        assertEveryKind(analysis.binding, outer, "x", NameOccurrence.Role.STORE,
                BindingKind.DEREF, outer, 2);
        assertOnlyKind(analysis.binding, first, "x", NameOccurrence.Role.LOAD,
                BindingKind.DEREF, outer);
        assertOnlyKind(analysis.binding, second, "x", NameOccurrence.Role.LOAD,
                BindingKind.DEREF, outer);
    }

    private static void testRecursiveNestedFunction() {
        Analysis analysis = analyze(lines(
                "def outer():",
                "    def inner():",
                "        return inner",
                "    return inner"));
        ScopeLayout outer = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.FUNCTION, "outer");
        ScopeLayout inner = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.FUNCTION, "inner");

        equal(List.of("inner"), outer.getCellVars(), "recursive owner cell");
        equal(List.of("inner"), inner.getFreeVars(), "recursive function free var");
        assertOnlyKind(analysis.binding, outer, "inner",
                NameOccurrence.Role.FUNCTION_DEFINITION, BindingKind.DEREF, outer);
        assertOnlyKind(analysis.binding, outer, "inner", NameOccurrence.Role.LOAD,
                BindingKind.DEREF, outer);
        assertOnlyKind(analysis.binding, inner, "inner", NameOccurrence.Role.LOAD,
                BindingKind.DEREF, outer);
    }

    private static void testMethodSkipsClassScope() {
        Analysis analysis = analyze(lines(
                "value = 10",
                "class C:",
                "    value = 20",
                "    def get(self):",
                "        return value"));
        ScopeLayout module = analysis.binding.getRootScope();
        ScopeLayout classScope = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.CLASS, "C");
        ScopeLayout method = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.FUNCTION, "get");

        check(method.getStructuralParent() == classScope,
                "method structural parent must be its class");
        check(method.getLexicalParent() == module,
                "method lexical parent must skip its class");
        equal(List.of(), method.getFreeVars(), "method free vars");
        assertOnlyKind(analysis.binding, classScope, "value", NameOccurrence.Role.STORE,
                BindingKind.NAME, classScope);
        assertOnlyKind(analysis.binding, method, "value", NameOccurrence.Role.LOAD,
                BindingKind.GLOBAL, module);
    }

    private static void testClassClosureCarrier() {
        Analysis analysis = analyze(lines(
                "def outer():",
                "    x = 1",
                "    class Direct:",
                "        copied = x",
                "    class Collision:",
                "        x = 2",
                "        def get(self):",
                "            return x",
                "    return Collision"));
        ScopeLayout outer = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.FUNCTION, "outer");
        ScopeLayout direct = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.CLASS, "Direct");
        ScopeLayout collision = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.CLASS, "Collision");
        ScopeLayout method = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.FUNCTION, "get");

        equal(List.of("x"), outer.getCellVars(), "class owner cell");
        equal(List.of("x"), direct.getFreeVars(), "direct class free vars");
        equal(List.of("x"), collision.getFreeVars(), "carrier class free vars");
        check(collision.isLocal("x"),
                "class must retain its dynamic local even while carrying outer x");
        equal(List.of("x"), method.getFreeVars(), "method free vars");
        check(method.getStructuralParent() == collision,
                "method structural parent must remain the class");
        check(method.getLexicalParent() == outer,
                "method lexical parent must be the outer function");

        assertOnlyKind(analysis.binding, direct, "x", NameOccurrence.Role.LOAD,
                BindingKind.DEREF, outer);
        assertOnlyKind(analysis.binding, collision, "x", NameOccurrence.Role.STORE,
                BindingKind.NAME, collision);
        assertOnlyKind(analysis.binding, method, "x", NameOccurrence.Role.LOAD,
                BindingKind.DEREF, outer);
    }

    private static void testDefinitionTimeScopes() {
        Analysis analysis = analyze(lines(
                "class Base:",
                "    pass",
                "seed = 1",
                "deco = Base",
                "@deco",
                "def target(value: seed = seed) -> seed:",
                "    return value",
                "@deco",
                "class Child(Base):",
                "    seed = 2",
                "    @deco",
                "    def method(self, value: seed = seed) -> seed:",
                "        return value"));
        ScopeLayout module = analysis.binding.getRootScope();
        ScopeLayout target = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.FUNCTION, "target");
        ScopeLayout child = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.CLASS, "Child");
        ScopeLayout method = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.FUNCTION, "method");

        // target: decorator, default, parameter annotation and return annotation
        assertEveryKind(analysis.binding, module, "deco", NameOccurrence.Role.LOAD,
                BindingKind.NAME, module, 2);
        assertEveryKind(analysis.binding, module, "seed", NameOccurrence.Role.LOAD,
                BindingKind.NAME, module, 3);
        assertEveryKind(analysis.binding, module, "Base", NameOccurrence.Role.LOAD,
                BindingKind.NAME, module, 2);

        // A method's definition-time expressions execute in the class body.
        assertEveryKind(analysis.binding, child, "deco", NameOccurrence.Role.LOAD,
                BindingKind.NAME, child, 1);
        assertEveryKind(analysis.binding, child, "seed", NameOccurrence.Role.LOAD,
                BindingKind.NAME, child, 3);
        assertOnlyKind(analysis.binding, target, "value", NameOccurrence.Role.PARAMETER,
                BindingKind.FAST, target);
        assertOnlyKind(analysis.binding, method, "value", NameOccurrence.Role.PARAMETER,
                BindingKind.FAST, method);
        assertOnlyKind(analysis.binding, method, "value", NameOccurrence.Role.LOAD,
                BindingKind.FAST, method);
    }

    private static void testBindingSiteRolesAndSpans() {
        Analysis analysis = analyze(lines(
                "import os.path",
                "import pkg.mod as alias",
                "from source import item, other as renamed",
                "try:",
                "    pass",
                "except Problem as err:",
                "    copied = err",
                "def update(param):",
                "    global shared",
                "    shared = param",
                "class K:",
                "    pass"));
        ScopeLayout module = analysis.binding.getRootScope();
        ScopeLayout function = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.FUNCTION, "update");

        equal(Set.of("os", "alias", "item", "renamed"),
                names(occurrences(analysis.binding, module, null,
                        NameOccurrence.Role.IMPORT_BINDING)),
                "import binding names");
        assertOnlyKind(analysis.binding, module, "err",
                NameOccurrence.Role.EXCEPT_ALIAS, BindingKind.NAME, module);
        assertOnlyKind(analysis.binding, function, "param",
                NameOccurrence.Role.PARAMETER, BindingKind.FAST, function);
        assertOnlyKind(analysis.binding, module, "update",
                NameOccurrence.Role.FUNCTION_DEFINITION, BindingKind.NAME, module);
        assertOnlyKind(analysis.binding, module, "K",
                NameOccurrence.Role.CLASS_DEFINITION, BindingKind.NAME, module);
        assertOnlyKind(analysis.binding, function, "shared",
                NameOccurrence.Role.GLOBAL_DECLARATION, BindingKind.GLOBAL, module);

        NameOccurrence functionName = only(occurrences(
                analysis.binding, module, "update",
                NameOccurrence.Role.FUNCTION_DEFINITION), "function name occurrence");
        FunctionDefNode functionNode = (FunctionDefNode) function.getOwner();
        check(functionName.getSpan().isKnown(), "function name span must be located");
        equal(functionNode.getNameSpan(), functionName.getSpan(), "function name span");

        NameOccurrence className = only(occurrences(
                analysis.binding, module, "K",
                NameOccurrence.Role.CLASS_DEFINITION), "class name occurrence");
        ClassDefNode classNode = (ClassDefNode) namedScope(
                analysis.binding, ScopeLayout.ScopeKind.CLASS, "K").getOwner();
        equal(classNode.getNameSpan(), className.getSpan(), "class name span");

        for (NameOccurrence occurrence : occurrences(
                analysis.binding, module, null, NameOccurrence.Role.IMPORT_BINDING)) {
            check(occurrence.getSpan().isKnown(),
                    "import binding span must be located for " + occurrence.getName());
        }
    }

    private static void testStableAndImmutableResult() {
        Analysis analysis = analyze(lines(
                "root = 1",
                "def outer(value):",
                "    local = value",
                "    def inner():",
                "        return local + root",
                "    return inner"));
        BindingAnalysisResult first = analysis.binding;
        BindingAnalysisResult second = new BindingResolver().resolve(analysis.ast);

        equal(first.getScopes().size(), second.getScopes().size(), "scope count stability");
        for (int i = 0; i < first.getScopes().size(); i++) {
            ScopeLayout left = first.getScopes().get(i);
            ScopeLayout right = second.getScopes().get(i);
            check(left.getOwner() == right.getOwner(), "scope owner identity changed at " + i);
            equal(left.getOrdinal(), right.getOrdinal(), "scope ordinal " + i);
            equal(left.getKind(), right.getKind(), "scope kind " + i);
            equal(left.getParameterNames(), right.getParameterNames(), "parameters " + i);
            equal(left.getLocalNames(), right.getLocalNames(), "locals " + i);
            equal(left.getGlobalDeclarations(), right.getGlobalDeclarations(), "globals " + i);
            equal(left.getCellVars(), right.getCellVars(), "cells " + i);
            equal(left.getFreeVars(), right.getFreeVars(), "free vars " + i);
        }

        equal(first.getOccurrences().size(), second.getOccurrences().size(),
                "occurrence count stability");
        for (int i = 0; i < first.getOccurrences().size(); i++) {
            NameOccurrence left = first.getOccurrences().get(i);
            NameOccurrence right = second.getOccurrences().get(i);
            equal(left.getKey(), right.getKey(), "occurrence key " + i);
            equal(left.getName(), right.getName(), "occurrence name " + i);
            equal(left.getRole(), right.getRole(), "occurrence role " + i);
            equal(left.getResolvedName().getKind(), right.getResolvedName().getKind(),
                    "binding kind " + i);
            equal(left.getResolvedName().getSlotIndex(),
                    right.getResolvedName().getSlotIndex(), "binding slot " + i);
            equal(left.getResolvedName().getDefiningScope().getOrdinal(),
                    right.getResolvedName().getDefiningScope().getOrdinal(),
                    "defining scope " + i);
            check(left.getRuntimeSymbol() == right.getRuntimeSymbol(),
                    "runtime symbol identity changed at occurrence " + i);
            check(first.getOccurrence(right.getKey()) == left,
                    "stable key cannot look up first-pass occurrence " + i);
        }

        Set<NameOccurrence.Key> uniqueKeys = new HashSet<>();
        for (NameOccurrence occurrence : first.getOccurrences()) {
            check(uniqueKeys.add(occurrence.getKey()),
                    "duplicate occurrence key: " + occurrence.getKey());
            check(!occurrence.getKey().toString().contains("@"),
                    "public occurrence label leaks a JVM identity hash");
        }

        expectUnsupported(() -> first.getScopes().add(first.getRootScope()),
                "scope result must be immutable");
        expectUnsupported(() -> first.getOccurrences().clear(),
                "occurrence result must be immutable");
        expectUnsupported(() -> first.getRootScope().getLocalNames().add("mutate"),
                "layout name tables must be immutable");
    }

    private static void testRuntimeSymbolDependencies() {
        Analysis analysis = analyze(lines(
                "from flask import jsonify as encode",
                "module_value = print",
                "def uses_builtin():",
                "    return len",
                "def shadows(len):",
                "    return len",
                "class C:",
                "    visible = print"));

        ScopeLayout module = analysis.binding.getRootScope();
        ScopeLayout usesBuiltin = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.FUNCTION, "uses_builtin");
        ScopeLayout shadows = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.FUNCTION, "shadows");
        ScopeLayout classScope = namedScope(
                analysis.binding, ScopeLayout.ScopeKind.CLASS, "C");

        assertRuntimeSymbol(
                only(occurrences(analysis.binding, module, "encode",
                        NameOccurrence.Role.IMPORT_BINDING), "Flask import binding"),
                RuntimeSymbolManifest.FLASK_MODULE,
                "jsonify");
        assertRuntimeSymbol(
                only(occurrences(analysis.binding, module, "print",
                        NameOccurrence.Role.LOAD), "module builtin load"),
                RuntimeSymbolManifest.BUILTINS_MODULE,
                "print");
        assertRuntimeSymbol(
                only(occurrences(analysis.binding, usesBuiltin, "len",
                        NameOccurrence.Role.LOAD), "function builtin load"),
                RuntimeSymbolManifest.BUILTINS_MODULE,
                "len");
        assertRuntimeSymbol(
                only(occurrences(analysis.binding, classScope, "print",
                        NameOccurrence.Role.LOAD), "class builtin load"),
                RuntimeSymbolManifest.BUILTINS_MODULE,
                "print");

        NameOccurrence shadowedLoad = only(occurrences(
                analysis.binding, shadows, "len", NameOccurrence.Role.LOAD),
                "shadowed local load");
        equal(BindingKind.FAST,
                shadowedLoad.getResolvedName().getKind(), "shadowed load kind");
        check(!shadowedLoad.hasRuntimeSymbol(),
                "a FAST local must not inherit a same-spelled builtin dependency");
    }

    private static void testBindingDiagnosticContract() {
        Diagnostic unresolved = Diagnostics.unresolvedBinding(
                "missing", 3, 7, "binding.py");
        equal(CompilerPhase.BINDING_RESOLUTION,
                unresolved.phase(), "unresolved-binding phase");
        equal(DiagnosticCategory.UNRESOLVED_BINDING,
                unresolved.category(), "unresolved-binding category");
        equal("binding.py", unresolved.sourceFile(), "unresolved-binding source");
        equal(3, unresolved.line(), "unresolved-binding line");
        equal(7, unresolved.column(), "unresolved-binding column");

        Diagnostic invalidLayout = Diagnostics.invalidScopeLayout(
                "invalid layout", 5, 2, "layout.py");
        equal(CompilerPhase.BINDING_RESOLUTION,
                invalidLayout.phase(), "invalid-layout phase");
        equal(DiagnosticCategory.INVALID_SCOPE_LAYOUT,
                invalidLayout.category(), "invalid-layout category");
        check(invalidLayout.isError(), "binding diagnostics must be errors");
    }

    private static Analysis analyze(String source) {
        ProgramNode ast = build(source);
        DiagnosticReporter reporter = new DiagnosticReporter();
        SymbolTableBuilder symbols = new SymbolTableBuilder(reporter, "<binding-test>");
        ast.accept(symbols);
        if (!reporter.errors().isEmpty()) {
            throw new AssertionError("symbol-table errors: " + diagnosticText(reporter));
        }
        return new Analysis(ast, new BindingResolver().resolve(ast));
    }

    private static ProgramNode build(String source) {
        List<String> syntaxIssues = new ArrayList<>();
        FlaskLexer lexer = new FlaskLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new CollectingErrorListener("lexer", syntaxIssues));

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        FlaskParser parser = new FlaskParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new CollectingErrorListener("parser", syntaxIssues));
        FlaskParser.ProgramContext tree = parser.program();
        check(syntaxIssues.isEmpty(), "syntax errors: " + syntaxIssues + "\n" + source);

        ASTNode root = new ASTBuilder().visit(tree);
        check(root instanceof ProgramNode, "AST root is not ProgramNode");
        return (ProgramNode) root;
    }

    private static ScopeLayout namedScope(
            BindingAnalysisResult result,
            ScopeLayout.ScopeKind kind,
            String name) {
        ScopeLayout found = null;
        for (ScopeLayout scope : result.getScopes()) {
            if (scope.getKind() != kind || !name.equals(scopeName(scope))) {
                continue;
            }
            if (found != null) {
                throw new AssertionError("ambiguous " + kind + " scope '" + name + "'");
            }
            found = scope;
        }
        if (found == null) {
            throw new AssertionError("missing " + kind + " scope '" + name + "'");
        }
        return found;
    }

    private static String scopeName(ScopeLayout scope) {
        if (scope.getOwner() instanceof FunctionDefNode) {
            return ((FunctionDefNode) scope.getOwner()).getName();
        }
        if (scope.getOwner() instanceof ClassDefNode) {
            return ((ClassDefNode) scope.getOwner()).getName();
        }
        return "<module>";
    }

    private static List<NameOccurrence> occurrences(
            BindingAnalysisResult result,
            ScopeLayout scope,
            String name,
            NameOccurrence.Role role) {
        List<NameOccurrence> found = new ArrayList<>();
        for (NameOccurrence occurrence : result.getOccurrences()) {
            if (occurrence.getScope() == scope
                    && (name == null || name.equals(occurrence.getName()))
                    && (role == null || role == occurrence.getRole())) {
                found.add(occurrence);
            }
        }
        return found;
    }

    private static Set<String> names(List<NameOccurrence> occurrences) {
        Set<String> result = new HashSet<>();
        for (NameOccurrence occurrence : occurrences) {
            result.add(occurrence.getName());
        }
        return result;
    }

    private static void assertOnlyKind(
            BindingAnalysisResult result,
            ScopeLayout useScope,
            String name,
            NameOccurrence.Role role,
            BindingKind expectedKind,
            ScopeLayout expectedDefiningScope) {
        NameOccurrence occurrence = only(
                occurrences(result, useScope, name, role), name + " " + role);
        assertResolution(occurrence, expectedKind, expectedDefiningScope);
    }

    private static void assertEveryKind(
            BindingAnalysisResult result,
            ScopeLayout useScope,
            String name,
            NameOccurrence.Role role,
            BindingKind expectedKind,
            ScopeLayout expectedDefiningScope,
            int expectedCount) {
        List<NameOccurrence> found = occurrences(result, useScope, name, role);
        equal(expectedCount, found.size(), name + " " + role + " count");
        for (NameOccurrence occurrence : found) {
            assertResolution(occurrence, expectedKind, expectedDefiningScope);
        }
    }

    private static void assertResolution(
            NameOccurrence occurrence,
            BindingKind expectedKind,
            ScopeLayout expectedDefiningScope) {
        ResolvedName resolved = occurrence.getResolvedName();
        equal(expectedKind, resolved.getKind(), occurrence + " kind");
        check(resolved.getUseScope() == occurrence.getScope(),
                occurrence + " use-scope mismatch");
        check(resolved.getDefiningScope() == expectedDefiningScope,
                occurrence + " defining scope: expected " + expectedDefiningScope
                        + " but was " + resolved.getDefiningScope());
        if (expectedKind == BindingKind.NAME || expectedKind == BindingKind.GLOBAL) {
            equal(-1, resolved.getSlotIndex(), occurrence + " non-slot index");
        } else {
            check(resolved.getSlotIndex() >= 0, occurrence + " missing layout slot");
        }
    }

    private static void assertRuntimeSymbol(
            NameOccurrence occurrence, String moduleName, String symbolName) {
        check(occurrence.hasRuntimeSymbol(),
                occurrence + " must retain its runtime dependency");
        RuntimeSymbolSpec expected = RuntimeSymbolManifest.lookup(moduleName, symbolName);
        check(expected != null, "missing manifest fixture " + moduleName + "." + symbolName);
        check(occurrence.getRuntimeSymbol() == expected,
                occurrence + " must reference the canonical manifest entry");
        equal(RuntimeSymbolSpec.SupportStatus.EXECUTABLE,
                occurrence.getRuntimeSymbol().supportStatus(),
                occurrence + " Part 3 runtime status");
        check(occurrence.getRuntimeSymbol().providerId() != null,
                occurrence + " executable provider id");
    }

    private static NameOccurrence only(List<NameOccurrence> occurrences, String label) {
        equal(1, occurrences.size(), label + " occurrence count");
        return occurrences.get(0);
    }

    private static String diagnosticText(DiagnosticReporter reporter) {
        StringBuilder text = new StringBuilder();
        for (Diagnostic diagnostic : reporter.diagnostics()) {
            text.append(diagnostic).append('\n');
        }
        return text.toString();
    }

    private static String lines(String... sourceLines) {
        return String.join("\n", sourceLines) + "\n";
    }

    private static void run(String name, CheckedRunnable test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable failure) {
            failed++;
            System.err.println("FAIL  " + name + " -> " + failure.getMessage());
            failure.printStackTrace(System.err);
        }
    }

    private static void expectUnsupported(Runnable mutation, String message) {
        try {
            mutation.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class Analysis {
        private final ProgramNode ast;
        private final BindingAnalysisResult binding;

        private Analysis(ProgramNode ast, BindingAnalysisResult binding) {
            this.ast = ast;
            this.binding = binding;
        }
    }

    private static final class CollectingErrorListener extends BaseErrorListener {
        private final String phase;
        private final List<String> issues;

        private CollectingErrorListener(String phase, List<String> issues) {
            this.phase = phase;
            this.issues = issues;
        }

        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String message,
                RecognitionException exception) {
            issues.add(phase + " " + line + ":" + charPositionInLine + " " + message);
        }
    }
}
