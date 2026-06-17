package compilers.flask.ast.builder;

import compilers.flask.antlr_gen.FlaskLexer;
import compilers.flask.antlr_gen.FlaskParser;
import compilers.flask.antlr_gen.FlaskParserBaseVisitor;
import compilers.flask.ast.nodes.*;
import compilers.flask.ast.nodes.expressions.access.*;
import compilers.flask.ast.nodes.expressions.atoms.*;
import compilers.flask.ast.nodes.expressions.operations.*;
import compilers.flask.ast.nodes.helpers.*;
import compilers.flask.ast.nodes.statements.ProgramNode;
import compilers.flask.ast.nodes.statements.compound.*;
import compilers.flask.ast.nodes.statements.imports.*;
import compilers.flask.ast.nodes.statements.simple.*;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ASTBuilder extends FlaskParserBaseVisitor<ASTNode> {

    /**
     * Helper method to set line and column information from parse tree context
     */
    private void setLocation(ASTNode node, org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (node != null && ctx != null && ctx.getStart() != null) {
            node.setLine(ctx.getStart().getLine());
            node.setColumn(ctx.getStart().getCharPositionInLine());
        }
    }

    // ========================================
    // PROGRAM
    // ========================================

    @Override
    public ASTNode visitProgram(FlaskParser.ProgramContext ctx) {
        ProgramNode program = new ProgramNode();
        setLocation(program, ctx);

        // Visit all statements
        for (FlaskParser.StatementContext stmtCtx : ctx.statement()) {
            ASTNode stmt = visit(stmtCtx);
            if (stmt instanceof Statement) {
                program.addStatement((Statement) stmt);
            }
        }

        return program;
    }

    // ========================================
    // STATEMENTS
    // ========================================

    @Override
    public ASTNode visitStatement(FlaskParser.StatementContext ctx) {
        // Delegate to simple_statement or compound_statement
        if (ctx.simple_statement() != null) {
            return visit(ctx.simple_statement());
        } else if (ctx.compound_statement() != null) {
            return visit(ctx.compound_statement());
        }
        return null;
    }

    @Override
    public ASTNode visitSimple_statement(FlaskParser.Simple_statementContext ctx) {
        // Visit the small_stmt
        return visit(ctx.small_stmt());
    }

    @Override
    public ASTNode visitSmall_stmt(FlaskParser.Small_stmtContext ctx) {
        // Delegate to the specific statement type
        if (ctx.importStatement() != null) {
            return visit(ctx.importStatement());
        } else if (ctx.exprOrAssignment() != null) {
            return visit(ctx.exprOrAssignment());
        } else if (ctx.returnStatement() != null) {
            return visit(ctx.returnStatement());
        } else if (ctx.passStatement() != null) {
            return visit(ctx.passStatement());
        } else if (ctx.breakStatement() != null) {
            return visit(ctx.breakStatement());
        } else if (ctx.continueStatement() != null) {
            return visit(ctx.continueStatement());
        } else if (ctx.delStatement() != null) {
            return visit(ctx.delStatement());
        } else if (ctx.assertStatement() != null) {
            return visit(ctx.assertStatement());
        } else if (ctx.globalStatement() != null) {
            return visit(ctx.globalStatement());
        } else if (ctx.raiseStatement() != null) {
            return visit(ctx.raiseStatement());
        }
        return null;
    }

    // ========================================
    // SIMPLE STATEMENTS
    // ========================================

    @Override
    public ASTNode visitExprOrAssignment(FlaskParser.ExprOrAssignmentContext ctx) {
        Expression firstExpression = (Expression) visit(ctx.expression(0));

        if (ctx.ASSIGN() != null || ctx.augmentedAssignmentOp() != null) {
            Expression value = (Expression) visit(ctx.expression(1));
            String operator = ctx.ASSIGN() != null ? "=" : ctx.augmentedAssignmentOp().getText();

            if (firstExpression == null) {
                throw new IllegalStateException("Assignment target cannot be null at line " +
                        (ctx.getStart() != null ? ctx.getStart().getLine() : "unknown"));
            }
            if (value == null) {
                throw new IllegalStateException("Assignment value cannot be null at line " +
                        (ctx.getStart() != null ? ctx.getStart().getLine() : "unknown"));
            }

            AssignmentNode node = new AssignmentNode(firstExpression, operator, value);
            setLocation(node, ctx);
            return node;
        }

        ExpressionStatementNode node = new ExpressionStatementNode(firstExpression);
        setLocation(node, ctx);
        return node;
    }

    @Override
    public ASTNode visitReturnStatement(FlaskParser.ReturnStatementContext ctx) {
        Expression returnValue = null;

        if (ctx.expression_list() != null) {
            List<Expression> values = new ArrayList<>();
            
            // Visit expression_list
            for (FlaskParser.ExpressionContext exprCtx : ctx.expression_list().expression()) {
                Expression expr = (Expression) visit(exprCtx);
                values.add(expr);
            }

            // If multiple values, wrap in TupleNode with implicit=true
            if (values.size() > 1) {
                TupleNode tuple = new TupleNode(values, false);  // false = implicit tuple (no parentheses)
                setLocation(tuple, ctx);
                returnValue = tuple;
            } else if (values.size() == 1) {
                returnValue = values.get(0);
            }
        }

        ReturnNode node = new ReturnNode(returnValue);
        setLocation(node, ctx);
        return node;
    }

    @Override
    public ASTNode visitPassStatement(FlaskParser.PassStatementContext ctx) {
        PassNode node = new PassNode();
        setLocation(node, ctx);
        return node;
    }

    @Override
    public ASTNode visitBreakStatement(FlaskParser.BreakStatementContext ctx) {
        BreakNode node = new BreakNode();
        setLocation(node, ctx);
        return node;
    }

    @Override
    public ASTNode visitContinueStatement(FlaskParser.ContinueStatementContext ctx) {
        ContinueNode node = new ContinueNode();
        setLocation(node, ctx);
        return node;
    }

    @Override
    public ASTNode visitDelStatement(FlaskParser.DelStatementContext ctx) {
        List<Expression> targets = new ArrayList<>();
        for (FlaskParser.TargetContext targetCtx : ctx.targetList().target()) {
            Expression target = (Expression) visit(targetCtx);
            if (target != null) {
                targets.add(target);
            }
        }
        DelNode node = new DelNode(targets);
        setLocation(node, ctx);
        return node;
    }

    @Override
    public ASTNode visitAssertStatement(FlaskParser.AssertStatementContext ctx) {
        Expression test = (Expression) visit(ctx.expression(0));
        Expression message = null;
        if (ctx.expression().size() > 1) {
            message = (Expression) visit(ctx.expression(1));
        }
        AssertNode node = new AssertNode(test, message);
        setLocation(node, ctx);
        return node;
    }

    @Override
    public ASTNode visitGlobalStatement(FlaskParser.GlobalStatementContext ctx) {
        List<String> names = new ArrayList<>();
        for (org.antlr.v4.runtime.tree.TerminalNode idNode : ctx.IDENTIFIER()) {
            names.add(idNode.getText());
        }
        GlobalNode node = new GlobalNode(names);
        setLocation(node, ctx);
        return node;
    }

    @Override
    public ASTNode visitRaiseStatement(FlaskParser.RaiseStatementContext ctx) {
        Expression exception = null;
        Expression cause = null;

        if (ctx.expression() != null && ctx.expression().size() > 0) {
            // First expression is the exception
            exception = (Expression) visit(ctx.expression(0));
            
            // Second expression (if present) is the cause (after FROM)
            if (ctx.expression().size() > 1) {
                cause = (Expression) visit(ctx.expression(1));
            }
        }

        RaiseNode node;
        if (exception == null && cause == null) {
            // Bare raise: raise
            node = new RaiseNode();
        } else if (cause == null) {
            // raise Exception
            node = new RaiseNode(exception);
        } else {
            // raise Exception from cause
            node = new RaiseNode(exception, cause);
        }
        
        setLocation(node, ctx);
        return node;
    }

    // ========================================
    // COMPOUND STATEMENTS
    // ========================================

    @Override
    public ASTNode visitCompound_statement(FlaskParser.Compound_statementContext ctx) {
        // Delegate to specific compound statement type
        if (ctx.ifStatement() != null) {
            return visit(ctx.ifStatement());
        } else if (ctx.whileStatement() != null) {
            return visit(ctx.whileStatement());
        } else if (ctx.forStatement() != null) {
            return visit(ctx.forStatement());
        } else if (ctx.functionDef() != null) {
            return visit(ctx.functionDef());
        } else if (ctx.classStatement() != null) {
            return visit(ctx.classStatement());
        } else if (ctx.tryStatement() != null) {
            return visit(ctx.tryStatement());
        } else if (ctx.withStatement() != null) {
            return visit(ctx.withStatement());
        } else if (ctx.decoratedDef() != null) {
            return visit(ctx.decoratedDef());
        }
        // TODO: Add other compound statement types
        return null;
    }

    @Override
    public ASTNode visitIfStatement(FlaskParser.IfStatementContext ctx) {
        // Visit main condition
        Expression condition = (Expression) visit(ctx.expression(0));

        // Visit then body (suite)
        List<Statement> thenBody = convertSuiteToStatements(ctx.suite(0));

        // Visit elif clauses
        List<IfStatementNode.ElifClause> elifClauses = new ArrayList<>();
        int elifCount = ctx.ELIF().size();
        for (int i = 0; i < elifCount; i++) {
            Expression elifCondition = (Expression) visit(ctx.expression(i + 1));
            List<Statement> elifBody = convertSuiteToStatements(ctx.suite(i + 1));
            elifClauses.add(new IfStatementNode.ElifClause(elifCondition, elifBody));
        }

        // Visit else body (if present)
        List<Statement> elseBody = null;
        if (ctx.ELSE() != null) {
            elseBody = convertSuiteToStatements(ctx.suite(ctx.suite().size() - 1));
        }

        IfStatementNode node = new IfStatementNode(condition, thenBody, elifClauses, elseBody);
        setLocation(node, ctx);
        return node;
    }

    /**
     * Visit suite and convert to list of statements
     */
    private List<Statement> convertSuiteToStatements(FlaskParser.SuiteContext ctx) {
        List<Statement> statements = new ArrayList<>();

        if (ctx.simple_statement() != null) {
            // Single-line suite: simple_statement
            ASTNode stmt = visit(ctx.simple_statement());
            if (stmt instanceof Statement) {
                statements.add((Statement) stmt);
            }
        } else if (ctx.statement() != null) {
            // Multi-line suite: statement+
            for (FlaskParser.StatementContext stmtCtx : ctx.statement()) {
                ASTNode stmt = visit(stmtCtx);
                if (stmt instanceof Statement) {
                    statements.add((Statement) stmt);
                }
            }
        }

        return statements;
    }

    // ========================================
    // EXPRESSIONS
    // ========================================

    @Override
    public ASTNode visitExpression(FlaskParser.ExpressionContext ctx) {
        return visit(ctx.or_boolean_expression());
    }

    @Override
    public ASTNode visitOr_boolean_expression(FlaskParser.Or_boolean_expressionContext ctx) {
        if (ctx.and_boolean_expression().size() == 1) {
            // No OR, just pass through
            return visit(ctx.and_boolean_expression(0));
        }

        // Build left-associative OR chain
        Expression left = (Expression) visit(ctx.and_boolean_expression(0));
        for (int i = 1; i < ctx.and_boolean_expression().size(); i++) {
            Expression right = (Expression) visit(ctx.and_boolean_expression(i));
            BinaryOpNode node = new BinaryOpNode(left, "or", right);
            setLocation(node, ctx);
            left = node;
        }
        return left;
    }

    @Override
    public ASTNode visitAnd_boolean_expression(FlaskParser.And_boolean_expressionContext ctx) {
        if (ctx.not_boolean_expression().size() == 1) {
            return visit(ctx.not_boolean_expression(0));
        }

        Expression left = (Expression) visit(ctx.not_boolean_expression(0));
        for (int i = 1; i < ctx.not_boolean_expression().size(); i++) {
            Expression right = (Expression) visit(ctx.not_boolean_expression(i));
            BinaryOpNode node = new BinaryOpNode(left, "and", right);
            setLocation(node, ctx);
            left = node;
        }
        return left;
    }

    @Override
    public ASTNode visitNot_boolean_expression(FlaskParser.Not_boolean_expressionContext ctx) {
        if (ctx.NOT() != null) {
            Expression operand = (Expression) visit(ctx.not_boolean_expression());
            UnaryOpNode node = new UnaryOpNode("not", operand);
            setLocation(node, ctx);
            return node;
        }
        return visit(ctx.comparison_expression());
    }

    @Override
    public ASTNode visitComparison_expression(FlaskParser.Comparison_expressionContext ctx) {
        // If no comparison operators, just return the additive expression
        if (ctx.comp_op().size() == 0) {
            return visit(ctx.additive_expression(0));
        }

        // Build CompareNode for chained comparisons (e.g., a < b <= 20)
        Expression left = (Expression) visit(ctx.additive_expression(0));
        List<CompareNode.CompareOp> operators = new ArrayList<>();
        List<Expression> comparators = new ArrayList<>();

        // Collect all operators and comparators
        for (int i = 0; i < ctx.comp_op().size(); i++) {
            String opText = ctx.comp_op(i).getText();
            CompareNode.CompareOp op = convertToCompareOp(opText);
            Expression comparator = (Expression) visit(ctx.additive_expression(i + 1));
            operators.add(op);
            comparators.add(comparator);
        }

        CompareNode node = new CompareNode(left, operators, comparators);
        setLocation(node, ctx);
        return node;
    }

    /**
     * Convert string operator to CompareOp enum
     */
    private CompareNode.CompareOp convertToCompareOp(String op) {
        switch (op) {
            case "==":
                return CompareNode.CompareOp.EQ;
            case "!=":
                return CompareNode.CompareOp.NEQ;
            case "<":
                return CompareNode.CompareOp.LT;
            case "<=":
                return CompareNode.CompareOp.LTE;
            case ">":
                return CompareNode.CompareOp.GT;
            case ">=":
                return CompareNode.CompareOp.GTE;
            case "in":
                return CompareNode.CompareOp.IN;
            case "is":
                return CompareNode.CompareOp.IS;
            default:
                throw new IllegalArgumentException("Unknown comparison operator: " + op);
        }
    }

    @Override
    public ASTNode visitAdditive_expression(FlaskParser.Additive_expressionContext ctx) {
        if (ctx.multiplicative_expression().size() == 1) {
            return visit(ctx.multiplicative_expression(0));
        }

        Expression left = (Expression) visit(ctx.multiplicative_expression(0));
        for (int i = 1; i < ctx.multiplicative_expression().size(); i++) {
            // Determine operator (+ or -)
            String op = ctx.getChild(2 * i - 1).getText(); // ADD or SUB
            Expression right = (Expression) visit(ctx.multiplicative_expression(i));
            BinaryOpNode node = new BinaryOpNode(left, op, right);
            setLocation(node, ctx);
            left = node;
        }
        return left;
    }

    @Override
    public ASTNode visitMultiplicative_expression(FlaskParser.Multiplicative_expressionContext ctx) {
        if (ctx.unary_expression().size() == 1) {
            return visit(ctx.unary_expression(0));
        }

        Expression left = (Expression) visit(ctx.unary_expression(0));
        for (int i = 1; i < ctx.unary_expression().size(); i++) {
            String op = ctx.getChild(2 * i - 1).getText(); // MUL, DIV, MOD
            Expression right = (Expression) visit(ctx.unary_expression(i));
            BinaryOpNode node = new BinaryOpNode(left, op, right);
            setLocation(node, ctx);
            left = node;
        }
        return left;
    }

    @Override
    public ASTNode visitUnary_expression(FlaskParser.Unary_expressionContext ctx) {
        if (ctx.ADD() != null) {
            Expression operand = (Expression) visit(ctx.unary_expression());
            UnaryOpNode node = new UnaryOpNode("+", operand);
            setLocation(node, ctx);
            return node;
        } else if (ctx.SUB() != null) {
            Expression operand = (Expression) visit(ctx.unary_expression());
            UnaryOpNode node = new UnaryOpNode("-", operand);
            setLocation(node, ctx);
            return node;
        }
        return visit(ctx.power_expression());
    }

    @Override
    public ASTNode visitPower_expression(FlaskParser.Power_expressionContext ctx) {
        Expression base = (Expression) visit(ctx.atom_expression());

        if (ctx.power_expression() != null) {
            Expression exponent = (Expression) visit(ctx.power_expression());
            BinaryOpNode node = new BinaryOpNode(base, "**", exponent);
            setLocation(node, ctx);
            return node;
        }

        return base;
    }

    @Override
    public ASTNode visitAtom_expression(FlaskParser.Atom_expressionContext ctx) {
        Expression expr = (Expression) visit(ctx.atom());

        // Process trailers (., [], ())
        for (FlaskParser.TrailerContext trailer : ctx.trailer()) {
            expr = (Expression) visitTrailerOnExpr(expr, trailer);
        }

        return expr;
    }

    /**
     * Helper to apply trailer to expression
     */
    private ASTNode visitTrailerOnExpr(Expression expr, FlaskParser.TrailerContext trailer) {
        if (trailer.DOT() != null) {
            // Attribute access: obj.attr
            String attr = trailer.IDENTIFIER().getText();
            AttributeAccessNode node = new AttributeAccessNode(expr, attr);
            setLocation(node, trailer);
            return node;
        } else if (trailer.LPAREN() != null) {
            // Function call: func()
            FunctionCallNode call = new FunctionCallNode(expr);
            setLocation(call, trailer);

            if (trailer.arglist() != null) {
                for (FlaskParser.ArgumentContext argCtx : trailer.arglist().argument()) {
                    if (argCtx.ASSIGN() != null) {
                        // Keyword argument
                        String name = argCtx.IDENTIFIER().getText();
                        Expression value = (Expression) visit(argCtx.expression());
                        call.addKwarg(name, value);
                    } else {
                        // Positional argument
                        Expression arg = (Expression) visit(argCtx.expression());
                        call.addArg(arg);
                    }
                }
            }

            return call;
        } else if (trailer.LBRACK() != null) {
            // Subscript: list[0]
            Expression index = (Expression) visit(trailer.expression());
            SubscriptNode node = new SubscriptNode(expr, index);
            setLocation(node, trailer);
            return node;
        }

        return expr;
    }

    @Override
    public ASTNode visitAtom(FlaskParser.AtomContext ctx) {
        ASTNode node = null;
        if (ctx.IDENTIFIER() != null) {
            node = new IdentifierNode(ctx.IDENTIFIER().getText());
        } else if (ctx.NUMBER() != null) {
            String text = ctx.NUMBER().getText();
            if (text.contains(".")) {
                node = LiteralNode.floatVal(Double.parseDouble(text));
            } else {
                node = LiteralNode.integer(Integer.parseInt(text));
            }
        } else if (ctx.STRING() != null) {
            String text = ctx.STRING().getText();
            
            // Check if it's an F-string (starts with 'f' or 'F')
            if (text.length() > 0 && (text.charAt(0) == 'f' || text.charAt(0) == 'F')) {
                // Parse F-string
                node = parseFString(text, ctx);
            } else {
                // Regular string - remove quotes
                String content = text;
                if (content.length() >= 2) {
                    // Remove first and last quote
                    char first = content.charAt(0);
                    if (first == 'r' || first == 'R') {
                        // Raw string: remove 'r' prefix and quotes
                        content = content.substring(2, content.length() - 1);
                    } else {
                        // Regular string: remove quotes
                        content = content.substring(1, content.length() - 1);
                    }
                }
                node = LiteralNode.string(content);
            }
        } else if (ctx.TRUE() != null) {
            node = LiteralNode.bool(true);
        } else if (ctx.FALSE() != null) {
            node = LiteralNode.bool(false);
        } else if (ctx.NONE() != null) {
            node = LiteralNode.none();
        } else if (ctx.LPAREN() != null && ctx.expression() != null) {
            // Parenthesized expression
            return visit(ctx.expression());
        } else if (ctx.LBRACK() != null) {
            // List literal: [] or [1, 2, 3]
            List<Expression> elements = new ArrayList<>();
            if (ctx.expression_list() != null) {
                for (FlaskParser.ExpressionContext exprCtx : ctx.expression_list().expression()) {
                    Expression elem = (Expression) visit(exprCtx);
                    if (elem != null) {
                        elements.add(elem);
                    }
                }
            }
            node = new ListNode(elements);
        } else if (ctx.LBRACE() != null) {
            // Dict or Set literal: {} or {1: 2} or {1, 2, 3}
            if (ctx.dict_or_set() != null) {
                if (ctx.dict_or_set().dict_items() != null) {
                    // Dictionary: {key: value, ...}
                    List<DictNode.DictItem> items = new ArrayList<>();
                    for (FlaskParser.Dict_itemContext itemCtx : ctx.dict_or_set().dict_items().dict_item()) {
                        Expression key = (Expression) visit(itemCtx.expression(0));
                        Expression value = (Expression) visit(itemCtx.expression(1));
                        if (key != null && value != null) {
                            items.add(new DictNode.DictItem(key, value));
                        }
                    }
                    node = new DictNode(items);
                } else if (ctx.dict_or_set().expression_list() != null) {
                    // Set: {1, 2, 3}
                    List<Expression> elements = new ArrayList<>();
                    for (FlaskParser.ExpressionContext exprCtx : ctx.dict_or_set().expression_list().expression()) {
                        Expression elem = (Expression) visit(exprCtx);
                        if (elem != null) {
                            elements.add(elem);
                        }
                    }
                    node = new SetNode(elements);
                }
            } else {
                // Empty dict: {}
                node = new DictNode();
            }
        }
        if (node != null) {
            setLocation(node, ctx);
        }
        return node;
    }

    @Override
    public ASTNode visitTarget(FlaskParser.TargetContext ctx) {
        Expression target = new IdentifierNode(ctx.IDENTIFIER().getText());
        setLocation(target, ctx);

        // Process target_trailers (for app.config, x[0], etc.)
        for (FlaskParser.Target_trailerContext trailer : ctx.target_trailer()) {
            if (trailer.DOT() != null) {
                String attr = trailer.IDENTIFIER().getText();
                AttributeAccessNode node = new AttributeAccessNode(target, attr);
                setLocation(node, trailer);
                target = node;
            } else if (trailer.LBRACK() != null) {
                // Subscript: x[0]
                Expression index = (Expression) visit(trailer.expression());
                SubscriptNode node = new SubscriptNode(target, index);
                setLocation(node, trailer);
                target = node;
            }
        }

        return target;
    }

    // ========================================
    // IMPORT STATEMENTS
    // ========================================

    @Override
    public ASTNode visitImportStatement(FlaskParser.ImportStatementContext ctx) {
        if (ctx.importNameStatement() != null) {
            return visit(ctx.importNameStatement());
        } else if (ctx.importFromStatement() != null) {
            return visit(ctx.importFromStatement());
        }
        return null;
    }

    @Override
    public ASTNode visitImportNameStatement(FlaskParser.ImportNameStatementContext ctx) {
        String moduleName = buildDottedName(ctx.dottedName());
        String asName = ctx.AS() != null ? ctx.IDENTIFIER().getText() : null;

        ImportNode node = new ImportNode(moduleName, asName);
        setLocation(node, ctx);
        return node;
    }

    @Override
    public ASTNode visitImportFromStatement(FlaskParser.ImportFromStatementContext ctx) {
        String moduleName = buildDottedName(ctx.dottedName());

        if (ctx.MUL() != null) {
            // from module import *
            FromImportNode node = FromImportNode.importAll(moduleName);
            setLocation(node, ctx);
            return node;
        } else if (ctx.importList() != null) {
            // from module import item1, item2, ...
            List<FromImportNode.ImportItem> items = new ArrayList<>();
            for (org.antlr.v4.runtime.tree.TerminalNode identifier : ctx.importList().IDENTIFIER()) {
                items.add(new FromImportNode.ImportItem(identifier.getText()));
            }

            FromImportNode node = new FromImportNode(moduleName, items);
            setLocation(node, ctx);
            return node;
        }

        return null;
    }

    /**
     * Helper method to build a dotted name from a DottedNameContext
     * e.g., "flask" or "os.path"
     */
    private String buildDottedName(FlaskParser.DottedNameContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ctx.IDENTIFIER().size(); i++) {
            if (i > 0) {
                sb.append(".");
            }
            sb.append(ctx.IDENTIFIER(i).getText());
        }
        return sb.toString();
    }

    // ========================================
    // COMPOUND STATEMENTS - IMPLEMENTATIONS
    // ========================================

    @Override
    public ASTNode visitFunctionDef(FlaskParser.FunctionDefContext ctx) {
        String name = ctx.IDENTIFIER().getText();
        
        // Visit parameters
        List<Parameter> parameters = new ArrayList<>();
        if (ctx.parameters() != null) {
            for (FlaskParser.ParameterContext paramCtx : ctx.parameters().parameter()) {
                parameters.add(buildParameter(paramCtx));
            }
        }

        // Visit body (suite)
        List<Statement> body = convertSuiteToStatements(ctx.suite());

        FunctionDefNode node = new FunctionDefNode(name, parameters, body);
        setLocation(node, ctx);
        return node;
    }

    @Override
    public ASTNode visitDecoratedDef(FlaskParser.DecoratedDefContext ctx) {
        // Visit decorators
        List<Decorator> decorators = new ArrayList<>();
        for (FlaskParser.DecoratorContext decoratorCtx : ctx.decorator()) {
            decorators.add(buildDecorator(decoratorCtx));
        }

        // Visit function definition
        // Note: decoratedDef currently only supports functionDef in the generated parser
        // If classStatement support is needed, regenerate the parser after updating the grammar
        FunctionDefNode functionDef = (FunctionDefNode) visit(ctx.functionDef());
        
        // Create new function def with decorators
        FunctionDefNode node = new FunctionDefNode(
            functionDef.getName(),
            functionDef.getParameters(),
            functionDef.getBody(),
            decorators,
            functionDef.getReturnType()
        );
        setLocation(node, ctx);
        return node;
    }

    /**
     * Build Decorator object from decorator context
     */
    private Decorator buildDecorator(FlaskParser.DecoratorContext ctx) {
        // Build decorator name (dotted name)
        Expression name = buildDottedNameExpression(ctx.dottedName());

        // Visit arguments if present
        List<Expression> args = new ArrayList<>();
        Map<String, Expression> kwargs = new HashMap<>();
        
        if (ctx.arglist() != null) {
            for (FlaskParser.ArgumentContext argCtx : ctx.arglist().argument()) {
                if (argCtx.ASSIGN() != null) {
                    // Keyword argument
                    String kwargName = argCtx.IDENTIFIER().getText();
                    Expression value = (Expression) visit(argCtx.expression());
                    kwargs.put(kwargName, value);
                } else {
                    // Positional argument
                    Expression arg = (Expression) visit(argCtx.expression());
                    args.add(arg);
                }
            }
        }

        return new Decorator(name, args, kwargs);
    }

    /**
     * Build Expression from dotted name (e.g., "app.route" -> AttributeAccessNode)
     */
    private Expression buildDottedNameExpression(FlaskParser.DottedNameContext ctx) {
        if (ctx.IDENTIFIER().size() == 1) {
            return new IdentifierNode(ctx.IDENTIFIER(0).getText());
        }

        // Build chain of attribute accesses: app.route -> app.route
        Expression expr = new IdentifierNode(ctx.IDENTIFIER(0).getText());
        for (int i = 1; i < ctx.IDENTIFIER().size(); i++) {
            String attr = ctx.IDENTIFIER(i).getText();
            expr = new AttributeAccessNode(expr, attr);
        }
        return expr;
    }

    /**
     * Build Parameter object from parameter context
     */
    private Parameter buildParameter(FlaskParser.ParameterContext ctx) {
        String name = ctx.IDENTIFIER().getText();
        Expression defaultValue = null;
        
        if (ctx.ASSIGN() != null && ctx.expression() != null) {
            defaultValue = (Expression) visit(ctx.expression());
        }

        return new Parameter(name, defaultValue);
    }

    @Override
    public ASTNode visitForStatement(FlaskParser.ForStatementContext ctx) {
        // Visit target (can be targetList, but for now we'll handle single target)
        Expression target = buildTargetListExpression(ctx.targetList());
        
        // Visit iterable
        Expression iterable = (Expression) visit(ctx.expression());

        // Visit body
        List<Statement> body = convertSuiteToStatements(ctx.suite(0));

        // Visit else body if present
        List<Statement> elseBody = null;
        if (ctx.ELSE() != null) {
            elseBody = convertSuiteToStatements(ctx.suite(1));
        }

        ForStatementNode node = new ForStatementNode(target, iterable, body, elseBody);
        setLocation(node, ctx);
        return node;
    }

    @Override
    public ASTNode visitWhileStatement(FlaskParser.WhileStatementContext ctx) {
        // Visit condition
        Expression condition = (Expression) visit(ctx.expression());

        // Visit body
        List<Statement> body = convertSuiteToStatements(ctx.suite(0));

        // Visit else body if present
        List<Statement> elseBody = null;
        if (ctx.ELSE() != null) {
            elseBody = convertSuiteToStatements(ctx.suite(1));
        }

        WhileStatementNode node = new WhileStatementNode(condition, body, elseBody);
        setLocation(node, ctx);
        return node;
    }

    @Override
    public ASTNode visitWithStatement(FlaskParser.WithStatementContext ctx) {
        // Visit with items
        List<WithItem> items = new ArrayList<>();
        for (FlaskParser.WithItemContext itemCtx : ctx.withItem()) {
            items.add(buildWithItem(itemCtx));
        }

        // Visit body
        List<Statement> body = convertSuiteToStatements(ctx.suite());

        WithStatementNode node = new WithStatementNode(items, body);
        setLocation(node, ctx);
        return node;
    }

    /**
     * Build WithItem object from with item context
     */
    private WithItem buildWithItem(FlaskParser.WithItemContext ctx) {
        Expression contextExpr = (Expression) visit(ctx.expression());
        Expression asName = null;

        if (ctx.AS() != null && ctx.targetList() != null) {
            // For now, handle single target in targetList
            asName = buildTargetListExpression(ctx.targetList());
        }

        return new WithItem(contextExpr, asName);
    }

    @Override
    public ASTNode visitTryStatement(FlaskParser.TryStatementContext ctx) {
        // Visit try body
        List<Statement> tryBody = convertSuiteToStatements(ctx.suite(0));

        // Visit except clauses
        List<ExceptClause> exceptClauses = new ArrayList<>();
        
        for (FlaskParser.ExceptClauseContext exceptCtx : ctx.exceptClause()) {
            Expression exceptionType = null;
            String asName = null;

            if (exceptCtx.expression() != null) {
                exceptionType = (Expression) visit(exceptCtx.expression());
                if (exceptCtx.AS() != null && exceptCtx.IDENTIFIER() != null) {
                    asName = exceptCtx.IDENTIFIER().getText();
                }
            }

            // Visit body for this except clause (suite is inside exceptClause)
            List<Statement> body = convertSuiteToStatements(exceptCtx.suite());

            exceptClauses.add(new ExceptClause(exceptionType, asName, body));
        }

        // Visit else body (if present, it's in suite(1) if no except, or after except clauses)
        List<Statement> elseBody = null;
        if (ctx.ELSE() != null) {
            // Find else suite - it's after all except clauses
            int elseSuiteIndex = 1 + ctx.exceptClause().size();
            if (ctx.suite().size() > elseSuiteIndex) {
                elseBody = convertSuiteToStatements(ctx.suite(elseSuiteIndex));
            }
        }

        // Visit finally body
        List<Statement> finallyBody = null;
        if (ctx.FINALLY() != null) {
            // Finally is always the last suite
            finallyBody = convertSuiteToStatements(ctx.suite(ctx.suite().size() - 1));
        }

        TryStatementNode node = new TryStatementNode(tryBody, exceptClauses, elseBody, finallyBody);
        setLocation(node, ctx);
        return node;
    }

    @Override
    public ASTNode visitClassStatement(FlaskParser.ClassStatementContext ctx) {
        String name = ctx.IDENTIFIER().getText();

        // Visit base classes
        List<Expression> bases = new ArrayList<>();
        if (ctx.expression_list() != null) {
            for (FlaskParser.ExpressionContext exprCtx : ctx.expression_list().expression()) {
                Expression base = (Expression) visit(exprCtx);
                bases.add(base);
            }
        }

        // Visit body
        List<Statement> body = convertSuiteToStatements(ctx.suite());

        ClassDefNode node = new ClassDefNode(name, bases, body);
        setLocation(node, ctx);
        return node;
    }

    /**
     * Build Expression from targetList
     * For now, handles single target (can be extended for tuple unpacking)
     */
    private Expression buildTargetListExpression(FlaskParser.TargetListContext ctx) {
        // For single target, just visit it
        if (ctx.target().size() == 1) {
            return (Expression) visit(ctx.target(0));
        }

        // For multiple targets (tuple unpacking), create a tuple expression
        // For now, we'll just use the first target
        // TODO: Handle tuple unpacking properly
        return (Expression) visit(ctx.target(0));
    }

    /**
     * Parse F-string: f"Hello {name}" or f'Error: {io_err}'
     * Extracts string parts and expressions from {}
     */
    private FStringNode parseFString(String fstringText, org.antlr.v4.runtime.ParserRuleContext ctx) {
        List<FStringPart> parts = new ArrayList<>();
        
        // Remove 'f' or 'F' prefix and quotes
        String content = fstringText;
        if (content.length() >= 2) {
            char first = content.charAt(0);
            if (first == 'f' || first == 'F') {
                // Remove 'f' prefix
                content = content.substring(1);
            }
            // Remove quotes (first and last)
            if (content.length() >= 2) {
                content = content.substring(1, content.length() - 1);
            }
        }
        
        // Parse content to extract string parts and expressions
        int i = 0;
        StringBuilder currentString = new StringBuilder();
        
        while (i < content.length()) {
            if (content.charAt(i) == '{') {
                // Check for escaped brace: {{
                if (i + 1 < content.length() && content.charAt(i + 1) == '{') {
                    currentString.append('{');
                    i += 2;
                } else {
                    // Expression start: save current string part
                    if (currentString.length() > 0) {
                        parts.add(new FStringPart.StringPart(currentString.toString()));
                        currentString = new StringBuilder();
                    }
                    
                    // Find matching closing brace
                    int braceCount = 1;
                    int j = i + 1;
                    while (j < content.length() && braceCount > 0) {
                        if (content.charAt(j) == '{') {
                            braceCount++;
                        } else if (content.charAt(j) == '}') {
                            braceCount--;
                        }
                        j++;
                    }
                    
                    if (braceCount == 0) {
                        // Extract expression
                        String exprText = content.substring(i + 1, j - 1);
                        Expression expr = parseExpressionFromString(exprText, ctx);
                        if (expr != null) {
                            parts.add(new FStringPart.ExpressionPart(expr));
                        }
                        i = j;
                    } else {
                        // Unmatched brace - treat as literal
                        currentString.append('{');
                        i++;
                    }
                }
            } else if (content.charAt(i) == '}') {
                // Check for escaped brace: }}
                if (i + 1 < content.length() && content.charAt(i + 1) == '}') {
                    currentString.append('}');
                    i += 2;
                } else {
                    // Single } - treat as literal
                    currentString.append('}');
                    i++;
                }
            } else {
                currentString.append(content.charAt(i));
                i++;
            }
        }
        
        // Add remaining string part
        if (currentString.length() > 0) {
            parts.add(new FStringPart.StringPart(currentString.toString()));
        }
        
        FStringNode node = new FStringNode(parts);
        setLocation(node, ctx);
        return node;
    }

    /**
     * Parse an expression from a string (used for F-string expressions)
     */
    private Expression parseExpressionFromString(String exprText, org.antlr.v4.runtime.ParserRuleContext ctx) {
        try {
            // Create a new parser for this expression
            org.antlr.v4.runtime.CharStream input = CharStreams.fromString(exprText);
            FlaskLexer lexer = new FlaskLexer(input);
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            FlaskParser parser = new FlaskParser(tokens);
            
            // Disable error listeners to avoid noise
            parser.removeErrorListeners();
            
            // Parse as expression
            FlaskParser.ExpressionContext exprCtx = parser.expression();
            
            if (exprCtx != null && parser.getNumberOfSyntaxErrors() == 0) {
                // Create a new ASTBuilder to visit the expression
                ASTBuilder builder = new ASTBuilder();
                ASTNode result = builder.visit(exprCtx);
                if (result instanceof Expression) {
                    return (Expression) result;
                }
            }
        } catch (Exception e) {
            // If parsing fails, return null (will be treated as string literal)
            // This can happen with complex expressions or syntax errors
        }
        return null;
    }
}
