import compilers.diagnostics.Diagnostic;
import compilers.diagnostics.DiagnosticCategory;
import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.SymbolTable.SymbolEntry;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.SymbolTable.SymbolTableBuilder;
import compilers.flask.SymbolTable.SymbolType;
import compilers.flask.antlr_gen.FlaskLexer;
import compilers.flask.antlr_gen.FlaskParser;
import compilers.flask.ast.builder.ASTBuilder;
import compilers.flask.ast.nodes.ASTNode;
import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.SourceSpan;
import compilers.flask.ast.nodes.Statement;
import compilers.flask.ast.nodes.expressions.access.AttributeAccessNode;
import compilers.flask.ast.nodes.expressions.access.FunctionCallNode;
import compilers.flask.ast.nodes.expressions.access.SubscriptNode;
import compilers.flask.ast.nodes.expressions.atoms.FStringNode;
import compilers.flask.ast.nodes.expressions.atoms.FStringPart;
import compilers.flask.ast.nodes.expressions.atoms.IdentifierNode;
import compilers.flask.ast.nodes.expressions.atoms.ListNode;
import compilers.flask.ast.nodes.expressions.atoms.LiteralNode;
import compilers.flask.ast.nodes.expressions.atoms.TupleNode;
import compilers.flask.ast.nodes.expressions.operations.BinaryOpNode;
import compilers.flask.ast.nodes.helpers.CallArgument;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.ast.nodes.statements.compound.ClassDefNode;
import compilers.flask.ast.nodes.statements.compound.ForStatementNode;
import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;
import compilers.flask.ast.nodes.statements.compound.TryStatementNode;
import compilers.flask.ast.nodes.statements.imports.FromImportNode;
import compilers.flask.ast.nodes.statements.imports.ImportNode;
import compilers.flask.ast.nodes.statements.simple.AssignmentNode;
import compilers.flask.ast.nodes.statements.simple.ExpressionStatementNode;
import compilers.flask.ast.nodes.statements.simple.ReturnNode;
import compilers.flask.semantic.SemanticAnalyzer;
import compilers.flask.semantic.validation.AstStructuralValidator;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Dependency-free regression harness for the Flask/Python compiler front-end.
 *
 * <p>This class deliberately bypasses {@code Main.UnifiedMain}: it parses source
 * strings directly, builds the custom AST, and then runs the symbol-table
 * visitor. Run it with a fresh output directory so stale generated classes do
 * not hide regressions.</p>
 */
public final class FlaskRegressionHarness {

    private static int passed;
    private static int failed;

    private FlaskRegressionHarness() {
    }

    public static void main(String[] args) {
        run("baseline: test_flask.py", FlaskRegressionHarness::testFlaskFixture);
        run("baseline: test_comprehensive.py", FlaskRegressionHarness::testComprehensiveFixture);
        run("baseline: minimal Flask symbols", FlaskRegressionHarness::testMinimalFlaskSymbols);
        run("baseline: expression and assignment targets", FlaskRegressionHarness::testValidAssignmentTargets);

        run("AST: try/except/else", FlaskRegressionHarness::testTryExceptElse);
        run("AST: try/except/else/finally", FlaskRegressionHarness::testTryExceptElseFinally);
        run("AST: try/finally only", FlaskRegressionHarness::testTryFinallyOnly);
        run("AST: multiple except clauses", FlaskRegressionHarness::testMultipleExceptClauses);
        run("AST: invalid assignment targets are preserved",
                FlaskRegressionHarness::testInvalidAssignmentTargets);
        run("AST: numeric literals", FlaskRegressionHarness::testNumericLiterals);
        run("AST: string literals", FlaskRegressionHarness::testStringLiterals);
        run("AST: tuple loop target", FlaskRegressionHarness::testTupleLoopTarget);
        run("AST: ordered call arguments", FlaskRegressionHarness::testOrderedCallArguments);
        run("AST: decorator shapes and decorated class",
                FlaskRegressionHarness::testDecoratorShapesAndDecoratedClass);
        run("AST: grammar alignment", FlaskRegressionHarness::testGrammarAlignment);
        run("AST: source spans and parent ownership",
                FlaskRegressionHarness::testSourceSpansAndParentOwnership);
        run("AST: f-string structure and locations", FlaskRegressionHarness::testFStringStructure);
        run("AST: malformed f-string is explicit", FlaskRegressionHarness::testMalformedFStringFails);

        run("symbols: import binding semantics", FlaskRegressionHarness::testImportBindings);
        run("symbols: from-import module awareness", FlaskRegressionHarness::testFromImportKinds);
        run("symbols: global assignment", FlaskRegressionHarness::testGlobalAssignment);
        run("symbols: forward function reference", FlaskRegressionHarness::testForwardFunctionReference);
        run("symbols: duplicate definitions reported once",
                FlaskRegressionHarness::testDuplicateDefinitions);
        run("symbols: augmented assignment reads target", FlaskRegressionHarness::testAugmentedAssignment);
        run("symbols: subscript assignment visits target", FlaskRegressionHarness::testSubscriptAssignment);
        run("symbols: iterable is evaluated before target", FlaskRegressionHarness::testForEvaluationOrder);
        run("symbols: method lookup skips class namespace", FlaskRegressionHarness::testMethodLexicalLookup);
        run("symbols: structural child scopes", FlaskRegressionHarness::testChildScopes);
        run("symbols: tuple target binds every name", FlaskRegressionHarness::testTupleTargetBindings);
        run("symbols: builtin shadowing", FlaskRegressionHarness::testBuiltinShadowing);
        run("symbols: imported unknown may be callable", FlaskRegressionHarness::testImportedUnknownCallable);
        run("symbols: rebinding clears import metadata", FlaskRegressionHarness::testImportRebinding);
        run("symbols: undefined exception type", FlaskRegressionHarness::testUndefinedExceptionType);
        run("symbols: decorator/default/base outer traversal",
                FlaskRegressionHarness::testDefinitionTimeExpressions);
        run("symbols: reassignment refreshes inferred type",
                FlaskRegressionHarness::testReassignmentTypeUpdate);
        run("symbols: undefined call has one diagnostic",
                FlaskRegressionHarness::testNoDuplicateFunctionCallDiagnostic);
        run("symbols: wildcard import suppresses false undefined names",
                FlaskRegressionHarness::testWildcardImport);

        System.out.println();
        if (failed != 0) {
            throw new AssertionError(
                    failed + " regression test(s) failed; " + passed + " passed");
        }
        System.out.println("All " + passed + " Flask regression tests passed.");
    }

    private static void testFlaskFixture() throws IOException {
        Analysis analysis = analyze(readFixture("test_flask.py"));
        equal(10, analysis.ast.getStatements().size(),
                "test_flask.py top-level statement count");
        symbol(analysis.root, "app");
        symbol(analysis.root, "User");
        symbol(analysis.root, "index");
        symbol(analysis.root, "login");
        check(analysis.errors.isEmpty(), "test_flask.py semantic errors: " + analysis.errors);
        check(analysis.warnings.isEmpty(), "test_flask.py warnings: " + analysis.warnings);
    }

    private static void testComprehensiveFixture() throws IOException {
        Analysis analysis = analyze(readFixture("test_comprehensive.py"));
        equal(12, analysis.ast.getStatements().size(),
                "test_comprehensive.py top-level statement count");
        symbol(analysis.root, "app");
        symbol(analysis.root, "UPLOAD_FOLDER");
        symbol(analysis.root, "products_page");
        symbol(analysis.root, "delete_product_route");
        check(analysis.errors.isEmpty(),
                "test_comprehensive.py semantic errors: " + analysis.errors);
        check(analysis.warnings.isEmpty(),
                "test_comprehensive.py warnings: " + analysis.warnings);
    }

    private static void testMinimalFlaskSymbols() {
        Analysis analysis = analyze(lines(
                "from flask import Flask, render_template",
                "app = Flask(__name__)",
                "@app.route(\"/\")",
                "def index():",
                "    return render_template(\"index.html\")"));

        assertSymbol(analysis.root, "Flask", SymbolEntry.SymbolKind.CLASS, SymbolType.CLASS);
        assertSymbol(analysis.root, "render_template", SymbolEntry.SymbolKind.FUNCTION,
                SymbolType.FUNCTION);
        assertSymbol(analysis.root, "app", SymbolEntry.SymbolKind.VARIABLE, SymbolType.UNKNOWN);
        assertSymbol(analysis.root, "index", SymbolEntry.SymbolKind.FUNCTION, SymbolType.FUNCTION);
        SymbolEntry moduleName = assertSymbol(
                analysis.root, "__name__", SymbolEntry.SymbolKind.VARIABLE, SymbolType.STRING);
        SymbolEntry packageName = assertSymbol(
                analysis.root, "__package__", SymbolEntry.SymbolKind.VARIABLE, SymbolType.STRING);
        check(Boolean.TRUE.equals(moduleName.getAttribute("implicit")),
                "__name__ must be an implicit module-local binding");
        check(Boolean.TRUE.equals(packageName.getAttribute("implicit")),
                "__package__ must be an implicit module-local binding");
        check(analysis.errors.isEmpty(), "minimal Flask program has semantic errors: " + analysis.errors);
        check(analysis.warnings.isEmpty(), "minimal Flask program has warnings: " + analysis.warnings);
    }

    private static void testValidAssignmentTargets() {
        ProgramNode ast = build(lines(
                "value = 1",
                "obj.attr = value",
                "items[value] = obj.attr",
                "factory().attr = value",
                "value += 1",
                "foo()"));

        equal(6, ast.getStatements().size(), "valid-target statement count");
        isType(IdentifierNode.class, assignment(ast, 0).getTarget(), "identifier target");
        isType(AttributeAccessNode.class, assignment(ast, 1).getTarget(), "attribute target");
        isType(SubscriptNode.class, assignment(ast, 2).getTarget(), "subscript target");
        AttributeAccessNode callAttribute = isType(
                AttributeAccessNode.class, assignment(ast, 3).getTarget(),
                "attribute on call target");
        isType(FunctionCallNode.class, callAttribute.getObject(),
                "factory().attr target receiver");
        check(assignment(ast, 4).isAugmented(), "value += 1 must remain augmented");

        ExpressionStatementNode expressionStatement = isType(
                ExpressionStatementNode.class, ast.getStatements().get(5),
                "foo() expression statement");
        isType(FunctionCallNode.class, expressionStatement.getExpression(), "foo() call");
    }

    private static void testTryExceptElse() {
        ProgramNode ast = build(lines(
                "try:",
                "    a = 1",
                "except ValueError as error:",
                "    b = 2",
                "else:",
                "    c = 3"));

        TryStatementNode tryNode = isType(
                TryStatementNode.class, ast.getStatements().get(0), "try node");
        check(tryNode.hasExcept(), "except clause was lost");
        check(tryNode.hasElse(), "else suite was lost");
        check(!tryNode.hasFinally(), "finally suite was invented");
        equal("a", onlyAssignmentTarget(tryNode.getTryBody(), "try body"), "try marker");
        equal("b", onlyAssignmentTarget(
                tryNode.getExceptClauses().get(0).getBody(), "except body"), "except marker");
        equal("c", onlyAssignmentTarget(tryNode.getElseBody(), "else body"), "else marker");
        equal("error", tryNode.getExceptClauses().get(0).getAsName(), "except alias");
    }

    private static void testTryExceptElseFinally() {
        ProgramNode ast = build(lines(
                "try:",
                "    a = 1",
                "except ValueError as error:",
                "    b = 2",
                "else:",
                "    c = 3",
                "finally:",
                "    d = 4"));

        TryStatementNode tryNode = isType(
                TryStatementNode.class, ast.getStatements().get(0), "try node");
        equal(1, tryNode.getExceptClauseCount(), "except-clause count");
        check(tryNode.hasElse(), "else suite was lost");
        check(tryNode.hasFinally(), "finally suite was lost");
        equal("a", onlyAssignmentTarget(tryNode.getTryBody(), "try body"), "try marker");
        equal("b", onlyAssignmentTarget(
                tryNode.getExceptClauses().get(0).getBody(), "except body"), "except marker");
        equal("c", onlyAssignmentTarget(tryNode.getElseBody(), "else body"), "else marker");
        equal("d", onlyAssignmentTarget(tryNode.getFinallyBody(), "finally body"),
                "finally marker");
        check(tryNode.getElseBody() != tryNode.getFinallyBody(),
                "else and finally must not reference the same suite");
    }

    private static void testTryFinallyOnly() {
        ProgramNode ast = build(lines(
                "try:",
                "    a = 1",
                "finally:",
                "    d = 4"));

        TryStatementNode tryNode = isType(
                TryStatementNode.class, ast.getStatements().get(0), "try/finally node");
        check(!tryNode.hasExcept(), "try/finally-only must not invent an except clause");
        check(!tryNode.hasElse(), "try/finally-only must not invent an else suite");
        check(tryNode.hasFinally(), "try/finally-only lost its finally suite");
        equal("a", onlyAssignmentTarget(tryNode.getTryBody(), "try body"), "try marker");
        equal("d", onlyAssignmentTarget(tryNode.getFinallyBody(), "finally body"),
                "finally marker");
    }

    private static void testMultipleExceptClauses() {
        ProgramNode ast = build(lines(
                "try:",
                "    a = 1",
                "except ValueError:",
                "    b = 2",
                "except TypeError:",
                "    c = 3",
                "else:",
                "    d = 4",
                "finally:",
                "    e = 5"));

        TryStatementNode tryNode = isType(
                TryStatementNode.class, ast.getStatements().get(0), "multi-except node");
        equal(2, tryNode.getExceptClauseCount(), "multi-except clause count");
        equal("ValueError", identifier(
                tryNode.getExceptClauses().get(0).getExceptionType(),
                "first exception type").getName(), "first exception name");
        equal("TypeError", identifier(
                tryNode.getExceptClauses().get(1).getExceptionType(),
                "second exception type").getName(), "second exception name");
        equal("b", onlyAssignmentTarget(
                tryNode.getExceptClauses().get(0).getBody(), "first except body"),
                "first except marker");
        equal("c", onlyAssignmentTarget(
                tryNode.getExceptClauses().get(1).getBody(), "second except body"),
                "second except marker");
        equal("d", onlyAssignmentTarget(tryNode.getElseBody(), "else body"), "else marker");
        equal("e", onlyAssignmentTarget(tryNode.getFinallyBody(), "finally body"),
                "finally marker");
    }

    private static void testInvalidAssignmentTargets() {
        ProgramNode invalid = build(lines(
                "1 = 2",
                "foo() = 1",
                "a + b = 1",
                "[a, b] += values"));

        isType(LiteralNode.class, assignment(invalid, 0).getTarget(),
                "literal assignment target must survive AST construction");
        isType(FunctionCallNode.class, assignment(invalid, 1).getTarget(),
                "call assignment target must survive AST construction");
        isType(BinaryOpNode.class, assignment(invalid, 2).getTarget(),
                "binary assignment target must survive AST construction");
        isType(ListNode.class, assignment(invalid, 3).getTarget(),
                "augmented unpacking target must survive AST construction");

        DiagnosticReporter reporter = new DiagnosticReporter();
        boolean structurallyValid = new AstStructuralValidator(
                reporter, "invalid-targets.py").validate(invalid);
        check(!structurallyValid,
                "invalid targets must be rejected by structural validation");
        equal(4, reporter.errors().size(), "invalid-target diagnostic count");
        for (Diagnostic diagnostic : reporter.errors()) {
            equal(DiagnosticCategory.INVALID_ASSIGNMENT_TARGET,
                    diagnostic.category(),
                    "invalid-target diagnostic category");
        }

        ProgramNode validUnpacking = build("[a, b] = values\n");
        ListNode target = isType(
                ListNode.class, assignment(validUnpacking, 0).getTarget(), "list unpacking target");
        equal(2, target.getElements().size(), "list-unpacking arity");
    }

    private static void testNumericLiterals() {
        ProgramNode ast = build(lines(
                "small = 42",
                "exponent = 1e3",
                "huge = 9223372036854775808123",
                "fraction = 2.5e-2"));

        LiteralNode small = literalValue(ast, 0);
        equal(LiteralNode.LiteralType.INTEGER, small.getLiteralType(), "small integer type");
        check(small.getValue() instanceof Integer,
                "small integers must remain Integer for backwards compatibility, got "
                        + className(small.getValue()));
        equal(42, small.getValue(), "small integer value");

        LiteralNode exponent = literalValue(ast, 1);
        equal(LiteralNode.LiteralType.FLOAT, exponent.getLiteralType(), "exponent type");
        closeTo(1000.0, exponent.getValue(), 0.0, "exponent value");

        LiteralNode huge = literalValue(ast, 2);
        equal(LiteralNode.LiteralType.INTEGER, huge.getLiteralType(), "huge integer type");
        check(huge.getValue() instanceof BigInteger,
                "overflowing Python integer must use BigInteger, got " + className(huge.getValue()));
        equal(new BigInteger("9223372036854775808123"), huge.getValue(), "huge integer value");

        LiteralNode fraction = literalValue(ast, 3);
        equal(LiteralNode.LiteralType.FLOAT, fraction.getLiteralType(), "fraction type");
        closeTo(0.025, fraction.getValue(), 1.0e-12, "fraction value");
    }

    private static void testStringLiterals() {
        ProgramNode ast = build(lines(
                "triple = \"\"\"hello",
                "world\"\"\"",
                "normal = \"line\\nquote\\\"\"",
                "raw = r\"line\\n\""));

        equal("hello\nworld", literalValue(ast, 0).getValue(), "triple-quoted content");
        equal("line\nquote\"", literalValue(ast, 1).getValue(), "decoded string escapes");
        equal("line\\n", literalValue(ast, 2).getValue(), "raw string content");
    }

    private static void testTupleLoopTarget() {
        ProgramNode ast = build(lines(
                "rows = [[1, 2]]",
                "for a, b in rows:",
                "    pass"));

        ForStatementNode forNode = isType(
                ForStatementNode.class, ast.getStatements().get(1), "for statement");
        TupleNode target = isType(TupleNode.class, forNode.getTarget(), "tuple loop target");
        equal(2, target.size(), "tuple-loop arity");
        check(!target.hasParentheses(), "target-list tuple must be implicit");
        equal("a", identifier(target.getElements().get(0), "first tuple target").getName(),
                "first tuple target name");
        equal("b", identifier(target.getElements().get(1), "second tuple target").getName(),
                "second tuple target name");
    }

    private static void testOrderedCallArguments() {
        ProgramNode ast = build(
                "result = invoke(first(), named=second(), third(), named=fourth())\n");
        FunctionCallNode call = isType(
                FunctionCallNode.class,
                assignment(ast, 0).getValue(),
                "mixed-argument call");
        List<CallArgument> arguments = call.getArguments();

        equal(4, arguments.size(), "canonical call argument count");
        check(arguments.get(0).isPositional(), "first argument must be positional");
        check(arguments.get(1).isKeyword(), "second argument must be keyword");
        equal("named", arguments.get(1).getKeywordName(), "first keyword name");
        check(arguments.get(2).isPositional(),
                "positional-after-keyword must remain available for structural diagnostics");
        check(arguments.get(3).isKeyword(), "fourth argument must be keyword");
        equal("named", arguments.get(3).getKeywordName(), "duplicate keyword name");
        equal(2, call.getArgs().size(), "derived positional compatibility view");
        equal(1, call.getKwargs().size(),
                "derived map view cannot represent duplicate keywords");

        for (CallArgument argument : arguments) {
            check(argument.getValue().getParent() == call,
                    "every argument value must be parented by its call");
            check(argument.getSpan().isKnown(), "every parsed argument needs a source span");
        }
        expectUnsupported(() -> arguments.clear(),
                "canonical argument list must be immutable");
    }

    private static void testDecoratorShapesAndDecoratedClass() {
        ProgramNode ast = build(lines(
                "@plain",
                "def first():",
                "    pass",
                "@factory()",
                "def second():",
                "    pass",
                "@outer.flag(key=build())",
                "class Child(Base):",
                "    pass",
                "@providers[select()]",
                "def indexed():",
                "    pass"));

        FunctionDefNode first = isType(
                FunctionDefNode.class, ast.getStatements().get(0), "plain decorated function");
        IdentifierNode plain = isType(
                IdentifierNode.class,
                first.getDecorators().get(0).getExpression(),
                "@plain expression");
        equal("plain", plain.getName(), "plain decorator name");

        FunctionDefNode second = isType(
                FunctionDefNode.class, ast.getStatements().get(1), "called decorator function");
        FunctionCallNode emptyCall = isType(
                FunctionCallNode.class,
                second.getDecorators().get(0).getExpression(),
                "@factory() expression");
        equal(0, emptyCall.getArguments().size(), "zero-argument decorator call");

        ClassDefNode child = isType(
                ClassDefNode.class, ast.getStatements().get(2), "decorated class");
        FunctionCallNode classDecorator = isType(
                FunctionCallNode.class,
                child.getDecorators().get(0).getExpression(),
                "decorated-class expression");
        equal(1, classDecorator.getArguments().size(), "class decorator argument count");
        check(classDecorator.getArguments().get(0).isKeyword(),
                "class decorator argument kind");
        equal("key", classDecorator.getArguments().get(0).getKeywordName(),
                "class decorator keyword");
        check(classDecorator.getParent() == child,
                "decorator expression must belong to its decorated class");
        check(child.getDecorators().get(0).getSpan().isKnown(),
                "decorator wrapper needs a source span");

        FunctionDefNode indexed = isType(
                FunctionDefNode.class, ast.getStatements().get(3),
                "full-expression decorated function");
        SubscriptNode indexedDecorator = isType(
                SubscriptNode.class,
                indexed.getDecorators().get(0).getExpression(),
                "subscript decorator expression");
        isType(FunctionCallNode.class, indexedDecorator.getIndex(),
                "decorator subscript index must use normal call lowering");
        check(indexedDecorator.getParent() == indexed,
                "full decorator expression must belong to its function");
    }

    private static void testGrammarAlignment() {
        ProgramNode ast = build(lines(
                "from package import item as alias, other,",
                "def convert(value: Source, fallback: int = 1,) -> Result:",
                "    empty = ()",
                "    single = (value,)",
                "    many = (value, fallback,)",
                "    grouped = (value)",
                "    quotient = 9 // 2",
                "    return value,"));

        FromImportNode fromImport = isType(
                FromImportNode.class, ast.getStatements().get(0), "aliased from-import");
        equal(2, fromImport.getItems().size(), "from-import item count");
        equal("alias", fromImport.getItems().get(0).getAsName(), "from-import alias");
        check(fromImport.getItems().get(0).getAliasSpan().isKnown(),
                "from-import alias span");

        FunctionDefNode function = isType(
                FunctionDefNode.class, ast.getStatements().get(1), "annotated function");
        equal(2, function.getParameters().size(), "annotated parameter count");
        check(function.getParameters().get(0).hasTypeHint(), "first parameter type hint");
        check(function.getParameters().get(1).hasTypeHint(), "second parameter type hint");
        check(function.getParameters().get(1).hasDefault(), "annotated default value");
        check(function.hasReturnType(), "function return annotation");

        TupleNode empty = isType(
                TupleNode.class,
                isType(AssignmentNode.class, function.getBody().get(0), "empty tuple assignment")
                        .getValue(),
                "empty tuple");
        TupleNode single = isType(
                TupleNode.class,
                isType(AssignmentNode.class, function.getBody().get(1), "single tuple assignment")
                        .getValue(),
                "singleton tuple");
        TupleNode many = isType(
                TupleNode.class,
                isType(AssignmentNode.class, function.getBody().get(2), "many tuple assignment")
                        .getValue(),
                "multi tuple");
        equal(0, empty.size(), "empty tuple arity");
        equal(1, single.size(), "singleton tuple arity");
        equal(2, many.size(), "multi tuple arity");
        check(empty.hasParentheses() && single.hasParentheses() && many.hasParentheses(),
                "explicit tuple displays must retain parentheses");

        Expression grouped = isType(
                AssignmentNode.class, function.getBody().get(3), "grouping assignment")
                .getValue();
        isType(IdentifierNode.class, grouped, "(value) remains grouping");
        BinaryOpNode floorDivision = isType(
                BinaryOpNode.class,
                isType(AssignmentNode.class, function.getBody().get(4),
                        "floor division assignment").getValue(),
                "floor division expression");
        equal("//", floorDivision.getOperator(), "floor division operator");

        ReturnNode returnNode = isType(
                ReturnNode.class, function.getBody().get(5), "trailing-comma return");
        TupleNode returnedTuple = isType(
                TupleNode.class, returnNode.getValue(), "implicit singleton return tuple");
        equal(1, returnedTuple.size(), "implicit return tuple arity");
        check(!returnedTuple.hasParentheses(), "return x, is an implicit tuple");
    }

    private static void testSourceSpansAndParentOwnership() {
        check(!SourceSpan.UNKNOWN.isKnown(), "UNKNOWN must be the no-source sentinel");
        try {
            new SourceSpan("<unknown>", 0, 0, 0, 0);
            throw new AssertionError("public all-zero SourceSpan must be rejected");
        } catch (IllegalArgumentException expected) {
            // Only SourceSpan.UNKNOWN may carry all-zero coordinates.
        }

        String source = lines(
                "@decorator()",
                "def typed(value: int = seed) -> str:",
                "    return value");
        ProgramNode ast = build(source, "sample.py");
        FunctionDefNode function = isType(
                FunctionDefNode.class, ast.getStatements().get(0), "located function");

        SourceSpan span = function.getSourceSpan();
        equal("sample.py", span.getSourceFile(), "source file");
        equal(1, span.getStartLine(), "decorated function start line");
        equal(0, span.getStartColumn(), "decorated function start column");
        check(span.getEndLine() >= 3, "function end line must cover its suite");
        equal(span.getStartLine(), function.getLine(), "legacy line compatibility");
        equal(span.getStartColumn(), function.getColumn(), "legacy column compatibility");

        SourceSpan nameSpan = function.getNameSpan();
        equal(2, nameSpan.getStartLine(), "function-name line");
        equal(4, nameSpan.getStartColumn(), "function-name start column");
        equal(9, nameSpan.getEndColumn(), "function-name end-exclusive column");

        check(function.getDecorators().get(0).getExpression().getParent() == function,
                "decorator expression parent");
        check(function.getParameters().get(0).getTypeHint().getParent() == function,
                "parameter type-hint parent");
        check(function.getParameters().get(0).getDefaultValue().getParent() == function,
                "parameter default parent");
        check(function.getReturnType().getParent() == function,
                "return annotation parent");
        expectUnsupported(() -> function.getBody().clear(),
                "function body view must be immutable");

        ProgramNode oneLine = build("value = call(1)\n", "sample.py");
        SourceSpan assignmentSpan = assignment(oneLine, 0).getSourceSpan();
        equal(1, assignmentSpan.getStartLine(), "assignment span line");
        equal(0, assignmentSpan.getStartColumn(), "assignment span start");
        equal("value = call(1)".length(), assignmentSpan.getEndColumn(),
                "assignment span end must be exclusive");
    }

    private static void testFStringStructure() {
        ProgramNode ast = build(lines(
                "name = \"Ali\"",
                "msg = f\"Hi {name}\"",
                "escaped = f\"{{{name}}}\"",
                "data = {\"}\": \"ok\"}",
                "quoted_brace = f\"{data['}']}\"",
                "triple = f\"\"\"Hello {name}\"\"\""));

        FStringNode message = isType(
                FStringNode.class, assignment(ast, 1).getValue(), "message f-string");
        equal(2, message.size(), "message f-string part count");
        FStringPart.StringPart prefix = isType(
                FStringPart.StringPart.class, message.getParts().get(0), "f-string prefix");
        equal("Hi ", prefix.getValue(), "f-string prefix text");
        FStringPart.ExpressionPart expressionPart = isType(
                FStringPart.ExpressionPart.class, message.getParts().get(1),
                "f-string expression part");
        IdentifierNode embedded = identifier(expressionPart.getExpression(), "embedded name");
        equal("name", embedded.getName(), "embedded identifier");
        check(embedded.getParent() == message, "embedded expression parent must be the f-string");
        equal(2, embedded.getLine(), "embedded expression source line");
        equal(12, embedded.getColumn(), "embedded expression source column");

        FStringNode escaped = isType(
                FStringNode.class, assignment(ast, 2).getValue(), "escaped-brace f-string");
        equal(3, escaped.size(), "escaped-brace f-string part count");
        equal("{", isType(FStringPart.StringPart.class, escaped.getParts().get(0),
                "escaped opening brace").getValue(), "escaped opening brace text");
        equal("}", isType(FStringPart.StringPart.class, escaped.getParts().get(2),
                "escaped closing brace").getValue(), "escaped closing brace text");

        FStringNode quotedBrace = isType(
                FStringNode.class, assignment(ast, 4).getValue(), "quoted-brace f-string");
        equal(1, quotedBrace.size(), "quoted-brace f-string part count");
        isType(FStringPart.ExpressionPart.class, quotedBrace.getParts().get(0),
                "brace inside embedded string literal");

        FStringNode triple = isType(
                FStringNode.class, assignment(ast, 5).getValue(), "triple f-string");
        equal("Hello ", isType(FStringPart.StringPart.class, triple.getParts().get(0),
                "triple f-string prefix").getValue(), "triple f-string prefix text");

        ProgramNode multilineAst = build("multi = f\"\"\"first\n{name}\"\"\"\n");
        FStringNode multiline = isType(
                FStringNode.class, assignment(multilineAst, 0).getValue(),
                "multiline triple f-string");
        IdentifierNode multilineName = identifier(
                isType(FStringPart.ExpressionPart.class, multiline.getParts().get(1),
                        "multiline f-string expression").getExpression(),
                "multiline embedded name");
        equal(2, multilineName.getLine(), "multiline embedded expression line");
        equal(1, multilineName.getColumn(), "multiline embedded expression column");
    }

    private static void testMalformedFStringFails() {
        expectBuildFailure("msg = f\"{name extra}\"\n", "f-string");
        expectBuildFailure("msg = f\"{name\"\n", "f-string");
        expectBuildFailure("msg = f\"name}\"\n", "f-string");
        expectBuildFailure("msg = f\"{}\"\n", "f-string");
        expectBuildFailure("msg = f\"{name!r}\"\n", "f-string");
    }

    private static void testImportBindings() {
        Analysis analysis = analyze(lines(
                "import flask as f",
                "import os.path",
                "import os.path as osp"));

        equal("f", isType(ImportNode.class, analysis.ast.getStatements().get(0),
                "aliased import AST").getEffectiveName(), "aliased import effective name");
        equal("os", isType(ImportNode.class, analysis.ast.getStatements().get(1),
                "dotted import AST").getEffectiveName(), "dotted import effective name");

        SymbolEntry f = assertSymbol(
                analysis.root, "f", SymbolEntry.SymbolKind.MODULE, SymbolType.MODULE);
        equal("flask", f.getAttribute("original_module"), "flask alias metadata");
        check(analysis.root.lookupLocal("flask") == null,
                "import flask as f must not also bind flask");

        SymbolEntry os = assertSymbol(
                analysis.root, "os", SymbolEntry.SymbolKind.MODULE, SymbolType.MODULE);
        equal("os.path", os.getAttribute("original_module"), "dotted import metadata");
        check(analysis.root.lookupLocal("os.path") == null,
                "import os.path must bind the top-level name os");

        SymbolEntry osp = assertSymbol(
                analysis.root, "osp", SymbolEntry.SymbolKind.MODULE, SymbolType.MODULE);
        equal("os.path", osp.getAttribute("original_module"), "dotted alias metadata");
    }

    private static void testFromImportKinds() {
        Analysis custom = analyze("from custom import Flask\n");
        assertSymbol(custom.root, "Flask", SymbolEntry.SymbolKind.VARIABLE, SymbolType.UNKNOWN);

        Analysis flask = analyze(lines(
                "from flask import Flask, render_template, request"));
        assertSymbol(flask.root, "Flask", SymbolEntry.SymbolKind.CLASS, SymbolType.CLASS);
        assertSymbol(flask.root, "render_template", SymbolEntry.SymbolKind.FUNCTION,
                SymbolType.FUNCTION);
        assertSymbol(flask.root, "request", SymbolEntry.SymbolKind.VARIABLE, SymbolType.UNKNOWN);
    }

    private static void testGlobalAssignment() {
        Analysis analysis = analyze(lines(
                "x = 0",
                "def set_x():",
                "    global x",
                "    x = 1",
                "    y = 2"));

        SymbolTable function = findScope(
                analysis, "set_x", SymbolTable.ScopeType.FUNCTION);
        symbol(analysis.root, "x");
        check(function.lookupLocal("x") == null,
                "global x must not create a function-local x");
        symbol(function, "y");
        check(analysis.errors.isEmpty(), "global assignment produced errors: " + analysis.errors);
    }

    private static void testForwardFunctionReference() {
        Analysis analysis = analyze(lines(
                "def first():",
                "    return later()",
                "def later():",
                "    return 1"));

        assertNoDiagnosticContaining(analysis, "later");
        assertSymbol(analysis.root, "first", SymbolEntry.SymbolKind.FUNCTION, SymbolType.FUNCTION);
        assertSymbol(analysis.root, "later", SymbolEntry.SymbolKind.FUNCTION, SymbolType.FUNCTION);
    }

    private static void testDuplicateDefinitions() {
        Analysis analysis = analyze(lines(
                "def duplicate_function():",
                "    pass",
                "def duplicate_function():",
                "    pass",
                "class DuplicateClass:",
                "    pass",
                "class DuplicateClass:",
                "    pass"));

        equal(1, diagnosticCountContaining(analysis, "Function 'duplicate_function'"),
                "duplicate function diagnostic count");
        equal(1, diagnosticCountContaining(analysis, "Class 'DuplicateClass'"),
                "duplicate class diagnostic count");
    }

    private static void testAugmentedAssignment() {
        Analysis defined = analyze(lines(
                "x = 0",
                "x += 1"));
        SymbolEntry definedX = symbol(defined.root, "x");
        check(definedX.isUsed(), "x += 1 must mark the previous value of x as used");
        assertNoDiagnosticContaining(defined, "x");

        Analysis missing = analyze(lines(
                "def update():",
                "    x += 1"));
        SymbolTable function = findScope(missing, "update", SymbolTable.ScopeType.FUNCTION);
        SymbolEntry localX = symbol(function, "x");
        check(localX.isUsed(), "read-before-write x must be recorded as read");
        assertDiagnosticContaining(missing, "x");
    }

    private static void testSubscriptAssignment() {
        Analysis analysis = analyze(lines(
                "data = {}",
                "key = \"answer\"",
                "data[key] = 42"));

        check(symbol(analysis.root, "data").isUsed(),
                "subscript assignment must visit its object");
        check(symbol(analysis.root, "key").isUsed(),
                "subscript assignment must visit its index");
        assertNoDiagnosticContaining(analysis, "data");
        assertNoDiagnosticContaining(analysis, "key");
    }

    private static void testForEvaluationOrder() {
        Analysis analysis = analyze(lines(
                "for item in item:",
                "    pass"));

        symbol(analysis.root, "item");
        assertDiagnosticContaining(analysis, "item");
    }

    private static void testMethodLexicalLookup() {
        Analysis analysis = analyze(lines(
                "value = 1",
                "class Container:",
                "    value = 2",
                "    def read(self):",
                "        return value"));

        SymbolTable classScope = findScope(
                analysis, "Container", SymbolTable.ScopeType.CLASS);
        SymbolTable methodScope = findScope(
                analysis, "read", SymbolTable.ScopeType.FUNCTION);
        SymbolEntry globalValue = symbol(analysis.root, "value");
        SymbolEntry classValue = symbol(classScope, "value");

        check(globalValue.isUsed(),
                "unqualified name inside a method must resolve in the enclosing global scope");
        check(!classValue.isUsed(),
                "method lexical lookup must skip the class namespace");
        check(methodScope.lookup("value") == globalValue,
                "method lookup(value) must return the global binding");
        assertNoDiagnosticContaining(analysis, "value");
    }

    private static void testChildScopes() throws ReflectiveOperationException {
        Analysis analysis = analyze(lines(
                "class Container:",
                "    def read(self):",
                "        pass"));

        SymbolTable classScope = findScope(
                analysis, "Container", SymbolTable.ScopeType.CLASS);
        SymbolTable methodScope = findScope(
                analysis, "read", SymbolTable.ScopeType.FUNCTION);
        List<SymbolTable> rootChildren = childrenOf(analysis.root);
        List<SymbolTable> classChildren = childrenOf(classScope);

        check(containsIdentity(rootChildren, classScope),
                "global scope must structurally own the class scope");
        check(containsIdentity(classChildren, methodScope),
                "class scope must structurally own its method scope");

        int originalCount = rootChildren.size();
        try {
            rootChildren.clear();
        } catch (UnsupportedOperationException ignored) {
            // An immutable defensive result is also valid.
        }
        equal(originalCount, childrenOf(analysis.root).size(),
                "getChildren must not expose mutable internal state");
    }

    private static void testTupleTargetBindings() {
        Analysis analysis = analyze(lines(
                "rows = [[1, 2]]",
                "for a, b in rows:",
                "    pass"));

        symbol(analysis.root, "a");
        symbol(analysis.root, "b");
        check(symbol(analysis.root, "rows").isUsed(), "rows iterable must be read");
        check(analysis.warnings.isEmpty(),
                "tuple target names are bindings, not undefined reads: " + analysis.warnings);
    }

    private static void testBuiltinShadowing() {
        Analysis analysis = analyze(lines(
                "len = 7",
                "copied = len"));

        SymbolEntry userLen = assertSymbol(
                analysis.root, "len", SymbolEntry.SymbolKind.VARIABLE, SymbolType.INTEGER);
        SymbolEntry builtinLen = analysis.root.lookupBuiltin("len");
        check(builtinLen != null, "shadowing len must not destroy the builtin fallback entry");
        check(userLen != builtinLen, "user binding and builtin binding must be separate entries");
        check(analysis.root.lookup("len") == userLen,
                "normal lookup must prefer the user binding over the builtin");
        check(userLen.isUsed(), "copied = len must read the shadowing user binding");
        check(analysis.warnings.isEmpty(), "legal builtin shadowing produced warnings: "
                + analysis.warnings);
    }

    private static void testImportedUnknownCallable() {
        Analysis analysis = analyze(lines(
                "from products import load_products",
                "result = load_products()"));

        SymbolEntry imported = assertSymbol(
                analysis.root,
                "load_products",
                SymbolEntry.SymbolKind.VARIABLE,
                SymbolType.UNKNOWN);
        check(imported.hasAttribute("imported_from"),
                "from-import binding must retain source-module metadata");
        check(imported.isUsed(), "calling an imported name must mark it used");
        assertNoDiagnosticContaining(analysis, "load_products");
        assertNoDiagnosticContaining(analysis, "callable");
    }

    private static void testImportRebinding() {
        Analysis analysis = analyze(lines(
                "from products import load_products",
                "load_products = 7",
                "result = load_products()"));

        SymbolEntry rebound = assertSymbol(
                analysis.root,
                "load_products",
                SymbolEntry.SymbolKind.VARIABLE,
                SymbolType.INTEGER);
        check(!rebound.hasAttribute("imported_from"),
                "a normal assignment must clear stale from-import metadata");
        assertNoDiagnosticContaining(analysis, "callable");
    }

    private static void testUndefinedExceptionType() {
        Analysis analysis = analyze(lines(
                "try:",
                "    pass",
                "except MissingError:",
                "    pass"));

        equal(1, diagnosticCountContaining(analysis, "MissingError"),
                "undefined exception diagnostic count");
    }

    private static void testDefinitionTimeExpressions() {
        Analysis analysis = analyze(lines(
                "class Base:",
                "    pass",
                "seed = 1",
                "def decorate(function):",
                "    return function",
                "@decorate(seed)",
                "def target(value=seed):",
                "    return value",
                "class Child(Base):",
                "    pass"));

        check(symbol(analysis.root, "decorate").isUsed(),
                "decorator expression must be traversed in the defining scope");
        check(symbol(analysis.root, "seed").isUsed(),
                "decorator arguments and defaults must be traversed in the defining scope");
        check(symbol(analysis.root, "Base").isUsed(),
                "base-class expressions must be traversed in the defining scope");
        check(analysis.errors.isEmpty(), "definition-time expressions produced errors: "
                + analysis.errors);
        check(analysis.warnings.isEmpty(), "definition-time expressions produced warnings: "
                + analysis.warnings);
    }

    private static void testReassignmentTypeUpdate() {
        Analysis analysis = analyze(lines(
                "value = 1",
                "value = \"now a string\""));

        SymbolEntry value = assertSymbol(
                analysis.root, "value", SymbolEntry.SymbolKind.VARIABLE, SymbolType.STRING);
        equal(2, value.getLine(), "reassignment source line");
    }

    private static void testNoDuplicateFunctionCallDiagnostic() {
        Analysis analysis = analyze("missing_function()\n");
        equal(1, diagnosticCountContaining(analysis, "missing_function"),
                "undefined function diagnostic count");
    }

    private static void testWildcardImport() {
        Analysis analysis = analyze(lines(
                "from plugins import *",
                "def use_plugin():",
                "    return dynamically_exported()"));

        check(analysis.root.hasVisibleWildcardImport(),
                "wildcard-import state must be recorded in its scope");
        check(analysis.root.lookupLocal("dynamically_exported") == null,
                "wildcard import must not invent concrete symbol entries");
        assertNoDiagnosticContaining(analysis, "dynamically_exported");
    }

    private static Parsed parse(String source) {
        List<String> syntaxIssues = new ArrayList<>();

        FlaskLexer lexer = new FlaskLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(new CollectingErrorListener("lexer", syntaxIssues));

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        FlaskParser parser = new FlaskParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new CollectingErrorListener("parser", syntaxIssues));

        FlaskParser.ProgramContext tree = parser.program();
        return new Parsed(tree, syntaxIssues);
    }

    private static ProgramNode build(String source) {
        return build(source, "<unknown>");
    }

    private static ProgramNode build(String source, String sourceFile) {
        Parsed parsed = parse(source);
        check(parsed.syntaxIssues.isEmpty(),
                "source has lexer/parser errors: " + parsed.syntaxIssues + "\n" + source);

        ASTNode node = new ASTBuilder(sourceFile).visit(parsed.tree);
        return isType(ProgramNode.class, node, "AST root");
    }

    private static Analysis analyze(String source) {
        ProgramNode ast = build(source);
        DiagnosticReporter reporter = new DiagnosticReporter();
        SymbolTableBuilder builder = new SymbolTableBuilder(reporter, "<regression>");
        ast.accept(builder);
        new SemanticAnalyzer(builder.getSymbolTable(), "<regression>", reporter).analyze(ast);
        return new Analysis(
                ast,
                builder.getSymbolTable(),
                builder.getAllScopes(),
                diagnosticStrings(reporter.errors()),
                diagnosticStrings(reporter.warnings()));
    }

    private static List<String> diagnosticStrings(List<Diagnostic> diagnostics) {
        List<String> result = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            result.add(diagnostic.toString());
        }
        return result;
    }

    private static void expectBuildFailure(String source, String messageFragment) {
        Parsed parsed = parse(source);
        check(parsed.syntaxIssues.isEmpty(),
                "semantic rejection must not depend on parser recovery: " + parsed.syntaxIssues);

        try {
            new ASTBuilder().visit(parsed.tree);
        } catch (IllegalArgumentException expected) {
            if (messageFragment != null) {
                String message = String.valueOf(expected.getMessage()).toLowerCase(Locale.ROOT);
                check(message.contains(messageFragment.toLowerCase(Locale.ROOT)),
                        "failure message must mention '" + messageFragment + "', got: "
                                + expected.getMessage());
            }
            return;
        }
        throw new AssertionError("expected AST build to reject source:\n" + source);
    }

    private static void expectUnsupported(Runnable action, String message) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static AssignmentNode assignment(ProgramNode ast, int statementIndex) {
        return isType(
                AssignmentNode.class, ast.getStatements().get(statementIndex),
                "statement " + statementIndex + " assignment");
    }

    private static LiteralNode literalValue(ProgramNode ast, int statementIndex) {
        return isType(
                LiteralNode.class, assignment(ast, statementIndex).getValue(),
                "statement " + statementIndex + " literal value");
    }

    private static String onlyAssignmentTarget(List<Statement> body, String label) {
        check(body != null, label + " is null");
        equal(1, body.size(), label + " statement count");
        AssignmentNode assignment = isType(
                AssignmentNode.class, body.get(0), label + " assignment");
        return identifier(assignment.getTarget(), label + " target").getName();
    }

    private static IdentifierNode identifier(Expression expression, String label) {
        return isType(IdentifierNode.class, expression, label);
    }

    private static SymbolEntry symbol(SymbolTable scope, String name) {
        SymbolEntry entry = scope.lookupLocal(name);
        check(entry != null,
                "missing local symbol '" + name + "' in scope " + scope.getScopeName());
        return entry;
    }

    private static SymbolEntry assertSymbol(
            SymbolTable scope,
            String name,
            SymbolEntry.SymbolKind expectedKind,
            SymbolType expectedType) {
        SymbolEntry entry = symbol(scope, name);
        equal(expectedKind, entry.getKind(), name + " symbol kind");
        equal(expectedType, entry.getType(), name + " symbol type");
        return entry;
    }

    private static SymbolTable findScope(
            Analysis analysis, String name, SymbolTable.ScopeType scopeType) {
        for (SymbolTable scope : analysis.scopes) {
            if (scope.getScopeName().equals(name) && scope.getScopeType() == scopeType) {
                return scope;
            }
        }
        throw new AssertionError("missing " + scopeType + " scope '" + name + "'");
    }

    @SuppressWarnings("unchecked")
    private static List<SymbolTable> childrenOf(SymbolTable scope)
            throws ReflectiveOperationException {
        final Method method;
        try {
            method = SymbolTable.class.getMethod("getChildren");
        } catch (NoSuchMethodException missing) {
            throw new AssertionError(
                    "SymbolTable must expose getChildren() so structural scopes can be verified",
                    missing);
        }

        try {
            Object value = method.invoke(scope);
            check(value instanceof List<?>, "getChildren() must return a List");
            return (List<SymbolTable>) value;
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw failure;
        }
    }

    private static boolean containsIdentity(List<SymbolTable> scopes, SymbolTable expected) {
        for (SymbolTable scope : scopes) {
            if (scope == expected) {
                return true;
            }
        }
        return false;
    }

    private static void assertDiagnosticContaining(Analysis analysis, String fragment) {
        String diagnostics = diagnostics(analysis).toLowerCase(Locale.ROOT);
        check(diagnostics.contains(fragment.toLowerCase(Locale.ROOT)),
                "expected diagnostic containing '" + fragment + "', got: " + diagnostics(analysis));
    }

    private static void assertNoDiagnosticContaining(Analysis analysis, String fragment) {
        String diagnostics = diagnostics(analysis).toLowerCase(Locale.ROOT);
        check(!diagnostics.contains(fragment.toLowerCase(Locale.ROOT)),
                "unexpected diagnostic containing '" + fragment + "': " + diagnostics(analysis));
    }

    private static int diagnosticCountContaining(Analysis analysis, String fragment) {
        String needle = fragment.toLowerCase(Locale.ROOT);
        int count = 0;
        for (String error : analysis.errors) {
            if (error.toLowerCase(Locale.ROOT).contains(needle)) {
                count++;
            }
        }
        for (String warning : analysis.warnings) {
            if (warning.toLowerCase(Locale.ROOT).contains(needle)) {
                count++;
            }
        }
        return count;
    }

    private static String diagnostics(Analysis analysis) {
        return "errors=" + analysis.errors + ", warnings=" + analysis.warnings;
    }

    private static String readFixture(String fileName) throws IOException {
        return Files.readString(
                Path.of("Tests", fileName),
                StandardCharsets.UTF_8);
    }

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
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

    private static void closeTo(double expected, Object actual, double tolerance, String label) {
        check(actual instanceof Number,
                label + ": expected a Number but got " + className(actual));
        double value = ((Number) actual).doubleValue();
        if (Math.abs(expected - value) > tolerance) {
            throw new AssertionError(
                    label + ": expected <" + expected + "> but was <" + value + ">");
        }
    }

    private static <T> T isType(Class<T> type, Object value, String label) {
        check(type.isInstance(value),
                label + ": expected " + type.getSimpleName() + " but got " + className(value));
        return type.cast(value);
    }

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class Parsed {
        private final FlaskParser.ProgramContext tree;
        private final List<String> syntaxIssues;

        private Parsed(FlaskParser.ProgramContext tree, List<String> syntaxIssues) {
            this.tree = tree;
            this.syntaxIssues = syntaxIssues;
        }
    }

    private static final class Analysis {
        private final ProgramNode ast;
        private final SymbolTable root;
        private final List<SymbolTable> scopes;
        private final List<String> errors;
        private final List<String> warnings;

        private Analysis(
                ProgramNode ast,
                SymbolTable root,
                List<SymbolTable> scopes,
                List<String> errors,
                List<String> warnings) {
            this.ast = ast;
            this.root = root;
            this.scopes = scopes;
            this.errors = errors;
            this.warnings = warnings;
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
