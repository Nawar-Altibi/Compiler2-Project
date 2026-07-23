package compilers.flask.semantic;

import compilers.diagnostics.DiagnosticReporter;
import compilers.flask.SymbolTable.SymbolTable;
import compilers.flask.Visitor.ASTBaseVisitor;
import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.Statement;
import compilers.flask.ast.nodes.helpers.DecoratorNode;
import compilers.flask.ast.nodes.helpers.Parameter;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.ast.nodes.statements.compound.ClassDefNode;
import compilers.flask.ast.nodes.statements.compound.FunctionDefNode;

import java.util.List;
import java.util.Objects;

/** Shared scope switching for semantic passes. */
abstract class ScopedSemanticVisitor<T> extends ASTBaseVisitor<T> {
    protected final DiagnosticReporter reporter;
    protected final String sourceFile;
    protected SymbolTable currentScope;

    ScopedSemanticVisitor(
            SymbolTable symbolTable,
            DiagnosticReporter reporter,
            String sourceFile) {
        this.currentScope = Objects.requireNonNull(symbolTable, "symbolTable");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.sourceFile = sourceFile == null || sourceFile.trim().isEmpty()
                ? "<unknown>"
                : sourceFile;
    }

    @Override
    public T visitProgram(ProgramNode node) {
        SymbolTable previousScope = currentScope;
        if (node.getScope() != null) {
            currentScope = node.getScope();
        }
        try {
            return super.visitProgram(node);
        } finally {
            currentScope = previousScope;
        }
    }

    @Override
    public T visitFunctionDef(FunctionDefNode node) {
        // Decorators, defaults and annotations execute in the defining scope.
        visitDecorators(node.getDecorators());
        for (Parameter parameter : node.getParameters()) {
            if (parameter.hasDefault()) {
                parameter.getDefaultValue().accept(this);
            }
            if (parameter.hasTypeHint()) {
                parameter.getTypeHint().accept(this);
            }
        }
        if (node.hasReturnType()) {
            node.getReturnType().accept(this);
        }

        SymbolTable previousScope = currentScope;
        if (node.getScope() != null) {
            currentScope = node.getScope();
        }
        try {
            return functionDefinitionResult(visitStatements(node.getBody()));
        } finally {
            currentScope = previousScope;
        }
    }

    @Override
    public T visitClassDef(ClassDefNode node) {
        // Class decorators and bases execute before the class namespace exists.
        visitDecorators(node.getDecorators());
        for (Expression base : node.getBases()) {
            base.accept(this);
        }

        SymbolTable previousScope = currentScope;
        if (node.getScope() != null) {
            currentScope = node.getScope();
        }
        try {
            return classDefinitionResult(visitStatements(node.getBody()));
        } finally {
            currentScope = previousScope;
        }
    }

    protected T functionDefinitionResult(T bodyResult) {
        return bodyResult;
    }

    protected T classDefinitionResult(T bodyResult) {
        return bodyResult;
    }

    protected T visitStatements(List<Statement> statements) {
        T result = defaultResult();
        for (Statement statement : statements) {
            result = aggregateResult(result, statement.accept(this));
        }
        return result;
    }

    private void visitDecorators(List<DecoratorNode> decorators) {
        for (DecoratorNode decorator : decorators) {
            decorator.getExpression().accept(this);
        }
    }
}
