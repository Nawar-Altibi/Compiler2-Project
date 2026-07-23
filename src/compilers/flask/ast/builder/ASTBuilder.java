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
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class ASTBuilder extends FlaskParserBaseVisitor<ASTNode> {

    private final String sourceFile;
    private final int sourceLineOffset;
    private final int sourceFirstLineColumnOffset;

    public ASTBuilder() {
        this(SourceSpan.UNKNOWN_SOURCE);
    }

    public ASTBuilder(String sourceFile) {
        this(sourceFile, 0, 0);
    }

    private ASTBuilder(
            String sourceFile,
            int sourceLineOffset,
            int sourceFirstLineColumnOffset) {
        this.sourceFile = sourceFile == null || sourceFile.trim().isEmpty()
                ? SourceSpan.UNKNOWN_SOURCE
                : sourceFile;
        this.sourceLineOffset = sourceLineOffset;
        this.sourceFirstLineColumnOffset = sourceFirstLineColumnOffset;
    }

    /**
     * Helper method to set line and column information from parse tree context
     */
    private void setLocation(ASTNode node, org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (node != null) {
            node.setSourceSpan(sourceSpan(ctx));
        }
    }

    private void setLocation(ASTNode node, SourceSpan span) {
        if (node != null) {
            node.setSourceSpan(span);
        }
    }

    private SourceSpan sourceSpan(org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (ctx == null || ctx.getStart() == null) {
            return SourceSpan.UNKNOWN;
        }
        Token stop = ctx.getStop() == null ? ctx.getStart() : ctx.getStop();
        return sourceSpan(ctx.getStart(), stop);
    }

    private SourceSpan sourceSpan(TerminalNode terminalNode) {
        return terminalNode == null
                ? SourceSpan.UNKNOWN
                : sourceSpan(terminalNode.getSymbol(), terminalNode.getSymbol());
    }

    private SourceSpan sourceSpan(Token start, Token stop) {
        if (start == null) {
            return SourceSpan.UNKNOWN;
        }

        int startLocalLine = Math.max(1, start.getLine());
        int startLine = toAbsoluteLine(startLocalLine);
        int startColumn = toAbsoluteColumn(
                startLocalLine,
                Math.max(0, start.getCharPositionInLine()));

        Token effectiveStop = stop == null ? start : stop;
        int stopLocalLine = Math.max(1, effectiveStop.getLine());
        int endLine = toAbsoluteLine(stopLocalLine);
        int endColumn = toAbsoluteColumn(
                stopLocalLine,
                Math.max(0, effectiveStop.getCharPositionInLine()));

        String text = effectiveStop.getType() == Token.EOF
                ? ""
                : effectiveStop.getText();
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                char current = text.charAt(i);
                if (current == '\r') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    endLine++;
                    endColumn = 0;
                } else if (current == '\n' || current == '\f') {
                    endLine++;
                    endColumn = 0;
                } else {
                    endColumn++;
                }
            }
        }

        return new SourceSpan(
                sourceFile,
                startLine,
                startColumn,
                endLine,
                endColumn);
    }

    private SourceSpan covering(SourceSpan start, SourceSpan end) {
        if (start == null || !start.isKnown()) {
            return end == null ? SourceSpan.UNKNOWN : end;
        }
        if (end == null || !end.isKnown()) {
            return start;
        }
        return new SourceSpan(
                sourceFile,
                start.getStartLine(),
                start.getStartColumn(),
                end.getEndLine(),
                end.getEndColumn());
    }

    private int toAbsoluteLine(int localLine) {
        return localLine + sourceLineOffset;
    }

    private int toAbsoluteColumn(int localLine, int localColumn) {
        return localColumn + (localLine == 1 ? sourceFirstLineColumnOffset : 0);
    }

    // ========================================
    // PROGRAM
    // ========================================

    @Override
    public ASTNode visitProgram(FlaskParser.ProgramContext ctx) {
        List<Statement> statements = new ArrayList<>();

        for (FlaskParser.StatementContext stmtCtx : ctx.statement()) {
            ASTNode stmt = visit(stmtCtx);
            if (stmt instanceof Statement) {
                statements.add((Statement) stmt);
            }
        }

        ProgramNode program = new ProgramNode(statements);
        setLocation(program, ctx);
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
            String operator = ctx.ASSIGN() != null ? "=" : ctx.augmentedAssignmentOp().getText();

            if (firstExpression == null) {
                throw new IllegalStateException("Assignment target cannot be null at line " +
                        (ctx.getStart() != null ? ctx.getStart().getLine() : "unknown"));
            }

            Expression value = (Expression) visit(ctx.expression(1));
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

            // A comma creates a tuple even when there is only one value.
            if (!ctx.expression_list().COMMA().isEmpty()) {
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
            elifClauses.add(new IfStatementNode.ElifClause(
                    elifCondition,
                    elifBody,
                    sourceSpan(
                            ctx.ELIF(i).getSymbol(),
                            ctx.suite(i + 1).getStop())));
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
            ASTNode stmt = visit(ctx.simple_statement());
            if (!(stmt instanceof Statement)) {
                throw new IllegalStateException(
                    "Expected Statement at line " +
                    ctx.getStart().getLine() +
                    " but got " +
                    (stmt == null ? "null" : stmt.getClass().getSimpleName())
                );
            }
            statements.add((Statement) stmt);
        } else if (ctx.statement() != null) {
            for (FlaskParser.StatementContext stmtCtx : ctx.statement()) {
                ASTNode stmt = visit(stmtCtx);
                if (!(stmt instanceof Statement)) {
                    throw new IllegalStateException(
                        "Expected Statement at line " +
                        stmtCtx.getStart().getLine() +
                        " but got " +
                        (stmt == null ? "null" : stmt.getClass().getSimpleName())
                    );
                }
                statements.add((Statement) stmt);
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
            setLocation(node, covering(expr.getSourceSpan(), sourceSpan(trailer)));
            return node;
        } else if (trailer.LPAREN() != null) {
            // Function call: func()
            List<CallArgument> arguments = new ArrayList<>();

            if (trailer.arglist() != null) {
                for (FlaskParser.ArgumentContext argCtx : trailer.arglist().argument()) {
                    if (argCtx.ASSIGN() != null) {
                        String name = argCtx.IDENTIFIER().getText();
                        Expression value = (Expression) visit(argCtx.expression());
                        arguments.add(CallArgument.keyword(
                                name,
                                value,
                                sourceSpan(argCtx)));
                    } else {
                        Expression arg = (Expression) visit(argCtx.expression());
                        arguments.add(CallArgument.positional(
                                arg,
                                sourceSpan(argCtx)));
                    }
                }
            }

            FunctionCallNode call = new FunctionCallNode(expr, arguments);
            setLocation(call, covering(expr.getSourceSpan(), sourceSpan(trailer)));
            return call;
        } else if (trailer.LBRACK() != null) {
            // Subscript: list[0]
            Expression index = (Expression) visit(trailer.expression());
            SubscriptNode node = new SubscriptNode(expr, index);
            setLocation(node, covering(expr.getSourceSpan(), sourceSpan(trailer)));
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
            node = parseNumberLiteral(ctx.NUMBER().getText(), ctx);
        } else if (ctx.STRING() != null) {
            String text = ctx.STRING().getText();
            StringLiteralInfo stringInfo = parseStringLiteralToken(text, ctx);

            if (stringInfo.formatted) {
                node = parseFString(stringInfo, ctx);
            } else {
                String content = stringInfo.raw
                        ? stringInfo.content
                        : decodePythonEscapes(stringInfo.content, ctx);
                node = LiteralNode.string(content);
            }
        } else if (ctx.TRUE() != null) {
            node = LiteralNode.bool(true);
        } else if (ctx.FALSE() != null) {
            node = LiteralNode.bool(false);
        } else if (ctx.NONE() != null) {
            node = LiteralNode.none();
        } else if (ctx.parenthesized() != null) {
            return visit(ctx.parenthesized());
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
                            items.add(new DictNode.DictItem(
                                    key,
                                    value,
                                    sourceSpan(itemCtx)));
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
    public ASTNode visitParenthesized(FlaskParser.ParenthesizedContext ctx) {
        List<Expression> elements = new ArrayList<>();
        for (FlaskParser.ExpressionContext expressionContext : ctx.expression()) {
            Expression expression = (Expression) visit(expressionContext);
            if (expression != null) {
                elements.add(expression);
            }
        }

        if (elements.size() == 1 && ctx.COMMA().isEmpty()) {
            // `(x)` is grouping and deliberately does not introduce a node.
            return elements.get(0);
        }

        TupleNode tuple = new TupleNode(elements, true);
        setLocation(tuple, ctx);
        return tuple;
    }

    private LiteralNode parseNumberLiteral(
            String text,
            org.antlr.v4.runtime.ParserRuleContext ctx) {
        try {
            if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
                return LiteralNode.floatVal(Double.parseDouble(text));
            }

            try {
                return LiteralNode.integer(Integer.parseInt(text));
            } catch (NumberFormatException outOfIntRange) {
                return LiteralNode.integer(new BigInteger(text));
            }
        } catch (NumberFormatException invalidNumber) {
            int line = ctx != null && ctx.getStart() != null
                    ? toAbsoluteLine(ctx.getStart().getLine())
                    : -1;
            throw new IllegalArgumentException(
                    "Invalid numeric literal '" + text + "' at line " + line,
                    invalidNumber);
        }
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
            for (FlaskParser.ImportItemContext itemContext
                    : ctx.importList().importItem()) {
                TerminalNode nameToken = itemContext.IDENTIFIER(0);
                TerminalNode aliasToken = itemContext.AS() == null
                        ? null
                        : itemContext.IDENTIFIER(1);
                items.add(new FromImportNode.ImportItem(
                        nameToken.getText(),
                        aliasToken == null ? null : aliasToken.getText(),
                        sourceSpan(itemContext),
                        sourceSpan(nameToken),
                        sourceSpan(aliasToken)));
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
        Expression returnType = ctx.expression() == null
                ? null
                : (Expression) visit(ctx.expression());

        FunctionDefNode node = new FunctionDefNode(
                name,
                parameters,
                body,
                new ArrayList<DecoratorNode>(),
                returnType,
                sourceSpan(ctx.IDENTIFIER()));
        setLocation(node, ctx);
        return node;
    }

    @Override
    public ASTNode visitDecoratedDef(FlaskParser.DecoratedDefContext ctx) {
        List<DecoratorNode> decorators = new ArrayList<>();
        for (FlaskParser.DecoratorContext decoratorCtx : ctx.decorator()) {
            decorators.add(buildDecorator(decoratorCtx));
        }

        if (ctx.functionDef() != null) {
            FunctionDefNode functionDef =
                    (FunctionDefNode) visit(ctx.functionDef());
            FunctionDefNode decoratedFunction = new FunctionDefNode(
                    functionDef.getName(),
                    functionDef.getParameters(),
                    functionDef.getBody(),
                    decorators,
                    functionDef.getReturnType(),
                    functionDef.getNameSpan());
            setLocation(decoratedFunction, ctx);
            return decoratedFunction;
        }

        ClassDefNode classDef = (ClassDefNode) visit(ctx.classStatement());
        ClassDefNode decoratedClass = new ClassDefNode(
                classDef.getName(),
                classDef.getBases(),
                classDef.getBody(),
                decorators,
                classDef.getNameSpan());
        setLocation(decoratedClass, ctx);
        return decoratedClass;
    }

    /**
     * Build a decorator through the canonical expression lowering path.
     */
    private DecoratorNode buildDecorator(FlaskParser.DecoratorContext ctx) {
        Expression expression = (Expression) visit(ctx.expression());
        return new DecoratorNode(expression, sourceSpan(ctx));
    }

    /**
     * Build Expression from dotted name (e.g., "app.route" -> AttributeAccessNode)
     */
    private Expression buildDottedNameExpression(FlaskParser.DottedNameContext ctx) {
        IdentifierNode firstIdentifier = new IdentifierNode(ctx.IDENTIFIER(0).getText());
        setLocation(firstIdentifier, ctx);

        // Build chain of attribute accesses: app.route -> app.route
        Expression expr = firstIdentifier;
        for (int i = 1; i < ctx.IDENTIFIER().size(); i++) {
            String attr = ctx.IDENTIFIER(i).getText();
            AttributeAccessNode attribute = new AttributeAccessNode(expr, attr);
            setLocation(attribute, ctx);
            expr = attribute;
        }
        return expr;
    }

    /**
     * Build Parameter object from parameter context
     */
    private Parameter buildParameter(FlaskParser.ParameterContext ctx) {
        String name = ctx.IDENTIFIER().getText();
        Expression defaultValue = null;
        Expression typeHint = null;
        int expressionIndex = 0;

        if (ctx.COLON() != null) {
            typeHint = (Expression) visit(ctx.expression(expressionIndex++));
        }
        if (ctx.ASSIGN() != null) {
            defaultValue = (Expression) visit(ctx.expression(expressionIndex));
        }

        return new Parameter(
                name,
                defaultValue,
                typeHint,
                sourceSpan(ctx),
                sourceSpan(ctx.IDENTIFIER()));
    }

    @Override
    public ASTNode visitForStatement(FlaskParser.ForStatementContext ctx) {
        // A target list is represented as an implicit tuple for unpacking.
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
            asName = buildTargetListExpression(ctx.targetList());
        }

        return new WithItem(contextExpr, asName, sourceSpan(ctx));
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

            exceptClauses.add(new ExceptClause(
                    exceptionType,
                    asName,
                    body,
                    sourceSpan(exceptCtx),
                    sourceSpan(exceptCtx.IDENTIFIER())));
        }

        // except-clause suites belong to ExceptClauseContext, not to
        // TryStatementContext.suite().  The direct suites are therefore always
        // ordered as: try, optional else, optional finally.
        int directSuiteIndex = 1;
        List<Statement> elseBody = null;
        if (ctx.ELSE() != null) {
            elseBody = convertSuiteToStatements(ctx.suite(directSuiteIndex++));
        }

        List<Statement> finallyBody = null;
        if (ctx.FINALLY() != null) {
            finallyBody = convertSuiteToStatements(ctx.suite(directSuiteIndex++));
        }

        if (directSuiteIndex != ctx.suite().size()) {
            throw new IllegalStateException(
                    "Unexpected try-statement suite layout at line " +
                    toAbsoluteLine(ctx.getStart().getLine()));
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

        ClassDefNode node = new ClassDefNode(
                name,
                bases,
                body,
                new ArrayList<DecoratorNode>(),
                sourceSpan(ctx.IDENTIFIER()));
        setLocation(node, ctx);
        return node;
    }

    /** Build an assignment target or an implicit tuple used for unpacking. */
    private Expression buildTargetListExpression(FlaskParser.TargetListContext ctx) {
        if (ctx == null || ctx.target().isEmpty()) {
            throw new IllegalArgumentException("Target list cannot be empty");
        }

        if (ctx.target().size() == 1) {
            return (Expression) visit(ctx.target(0));
        }

        List<Expression> targets = new ArrayList<>();
        for (FlaskParser.TargetContext targetContext : ctx.target()) {
            Expression target = (Expression) visit(targetContext);
            if (target == null) {
                throw new IllegalStateException(
                        "Failed to build target at line " +
                        toAbsoluteLine(targetContext.getStart().getLine()));
            }
            targets.add(target);
        }

        TupleNode tuple = new TupleNode(targets, false);
        setLocation(tuple, ctx);
        return tuple;
    }

    private static final class StringLiteralInfo {
        private final String tokenText;
        private final String content;
        private final int contentStartIndex;
        private final boolean raw;
        private final boolean formatted;

        private StringLiteralInfo(
                String tokenText,
                String content,
                int contentStartIndex,
                boolean raw,
                boolean formatted) {
            this.tokenText = tokenText;
            this.content = content;
            this.contentStartIndex = contentStartIndex;
            this.raw = raw;
            this.formatted = formatted;
        }
    }

    private static final class SourcePosition {
        private final int line;
        private final int column;

        private SourcePosition(int line, int column) {
            this.line = line;
            this.column = column;
        }
    }

    private static final class SyntaxErrorCollector extends BaseErrorListener {
        private String firstError;

        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String msg,
                RecognitionException e) {
            if (firstError == null) {
                firstError = "line " + line + ":" + charPositionInLine + " " + msg;
            }
        }

        private boolean hasErrors() {
            return firstError != null;
        }

        private String getFirstError() {
            return firstError;
        }
    }

    private StringLiteralInfo parseStringLiteralToken(
            String tokenText,
            org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (tokenText == null || tokenText.isEmpty()) {
            throw stringLiteralError("Empty string token", ctx);
        }

        int prefixLength = 0;
        boolean raw = false;
        boolean formatted = false;
        char first = tokenText.charAt(0);
        if (first == 'r' || first == 'R' || first == 'f' || first == 'F') {
            prefixLength = 1;
            raw = first == 'r' || first == 'R';
            formatted = first == 'f' || first == 'F';
        }

        if (prefixLength >= tokenText.length()) {
            throw stringLiteralError("Missing quote after string prefix", ctx);
        }

        char quote = tokenText.charAt(prefixLength);
        if (quote != '\'' && quote != '"') {
            throw stringLiteralError("Unsupported string delimiter", ctx);
        }

        int delimiterLength = startsWithRepeatedQuote(tokenText, prefixLength, quote, 3) ? 3 : 1;
        int contentStart = prefixLength + delimiterLength;
        int contentEnd = tokenText.length() - delimiterLength;
        if (contentEnd < contentStart ||
                !startsWithRepeatedQuote(tokenText, contentEnd, quote, delimiterLength)) {
            throw stringLiteralError("Unterminated string literal", ctx);
        }

        return new StringLiteralInfo(
                tokenText,
                tokenText.substring(contentStart, contentEnd),
                contentStart,
                raw,
                formatted);
    }

    private boolean startsWithRepeatedQuote(String text, int offset, char quote, int count) {
        if (offset < 0 || offset + count > text.length()) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (text.charAt(offset + i) != quote) {
                return false;
            }
        }
        return true;
    }

    private IllegalArgumentException stringLiteralError(
            String message,
            org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (ctx == null || ctx.getStart() == null) {
            return new IllegalArgumentException(message);
        }
        int localLine = ctx.getStart().getLine();
        return new IllegalArgumentException(
                message + " at line " + toAbsoluteLine(localLine) +
                ", column " +
                toAbsoluteColumn(localLine, ctx.getStart().getCharPositionInLine()));
    }

    private String decodePythonEscapes(
            String text,
            org.antlr.v4.runtime.ParserRuleContext ctx) {
        StringBuilder decoded = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current != '\\') {
                decoded.append(current);
                continue;
            }

            if (i + 1 >= text.length()) {
                throw stringLiteralError("Trailing backslash in string literal", ctx);
            }

            char escape = text.charAt(++i);
            switch (escape) {
                case '\n':
                    break;
                case '\r':
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    break;
                case '\\':
                    decoded.append('\\');
                    break;
                case '\'':
                    decoded.append('\'');
                    break;
                case '"':
                    decoded.append('"');
                    break;
                case 'a':
                    decoded.append('\u0007');
                    break;
                case 'b':
                    decoded.append('\b');
                    break;
                case 'f':
                    decoded.append('\f');
                    break;
                case 'n':
                    decoded.append('\n');
                    break;
                case 'r':
                    decoded.append('\r');
                    break;
                case 't':
                    decoded.append('\t');
                    break;
                case 'v':
                    decoded.append('\u000B');
                    break;
                case 'x': {
                    int codePoint = parseFixedHexEscape(text, i + 1, 2, "\\x", ctx);
                    decoded.append((char) codePoint);
                    i += 2;
                    break;
                }
                case 'u': {
                    int codePoint = parseFixedHexEscape(text, i + 1, 4, "\\u", ctx);
                    decoded.appendCodePoint(codePoint);
                    i += 4;
                    break;
                }
                case 'U': {
                    int codePoint = parseFixedHexEscape(text, i + 1, 8, "\\U", ctx);
                    if (!Character.isValidCodePoint(codePoint)) {
                        throw stringLiteralError("Unicode escape is outside the valid range", ctx);
                    }
                    decoded.appendCodePoint(codePoint);
                    i += 8;
                    break;
                }
                default:
                    if (escape >= '0' && escape <= '7') {
                        int value = escape - '0';
                        int consumed = 1;
                        while (consumed < 3 && i + 1 < text.length()) {
                            char digit = text.charAt(i + 1);
                            if (digit < '0' || digit > '7') {
                                break;
                            }
                            value = value * 8 + (digit - '0');
                            i++;
                            consumed++;
                        }
                        decoded.append((char) value);
                    } else {
                        // Python preserves unknown escapes (and may warn about them).
                        decoded.append('\\').append(escape);
                    }
                    break;
            }
        }
        return decoded.toString();
    }

    private int parseFixedHexEscape(
            String text,
            int start,
            int length,
            String escapeName,
            org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (start + length > text.length()) {
            throw stringLiteralError(
                    "Incomplete " + escapeName + " escape in string literal",
                    ctx);
        }

        long value = 0;
        for (int i = start; i < start + length; i++) {
            int digit = Character.digit(text.charAt(i), 16);
            if (digit < 0) {
                throw stringLiteralError(
                        "Invalid hexadecimal digit in " + escapeName + " escape",
                        ctx);
            }
            value = value * 16 + digit;
        }

        if (value > Integer.MAX_VALUE) {
            throw stringLiteralError("Unicode escape is outside the valid range", ctx);
        }
        return (int) value;
    }

    /**
     * Parse the basic f-string subset represented by the current AST.  Format
     * specifications and conversions are rejected explicitly rather than being
     * silently dropped.
     */
    private FStringNode parseFString(
            StringLiteralInfo stringInfo,
            org.antlr.v4.runtime.ParserRuleContext ctx) {
        List<FStringPart> parts = new ArrayList<>();
        String content = stringInfo.content;
        StringBuilder currentString = new StringBuilder();

        int i = 0;
        while (i < content.length()) {
            char current = content.charAt(i);
            if (current == '{') {
                if (i + 1 < content.length() && content.charAt(i + 1) == '{') {
                    currentString.append('{');
                    i += 2;
                    continue;
                }

                appendFStringTextPart(parts, currentString, stringInfo.raw, ctx);
                int closingBrace = findMatchingFStringBrace(content, i + 1);
                if (closingBrace < 0) {
                    SourcePosition position = sourcePositionAt(
                            ctx,
                            stringInfo.tokenText,
                            stringInfo.contentStartIndex + i);
                    throw fStringError("Unmatched '{' in f-string", position, null);
                }

                String expressionText = content.substring(i + 1, closingBrace);
                SourcePosition expressionPosition = sourcePositionAt(
                        ctx,
                        stringInfo.tokenText,
                        stringInfo.contentStartIndex + i + 1);
                if (expressionText.trim().isEmpty()) {
                    throw fStringError(
                            "Empty expression in f-string",
                            expressionPosition,
                            null);
                }

                Expression expression = parseExpressionFromString(
                        expressionText,
                        expressionPosition);
                parts.add(new FStringPart.ExpressionPart(
                        expression,
                        expression.getSourceSpan()));
                i = closingBrace + 1;
            } else if (current == '}') {
                if (i + 1 < content.length() && content.charAt(i + 1) == '}') {
                    currentString.append('}');
                    i += 2;
                    continue;
                }

                SourcePosition position = sourcePositionAt(
                        ctx,
                        stringInfo.tokenText,
                        stringInfo.contentStartIndex + i);
                throw fStringError("Single '}' is not allowed in f-string", position, null);
            } else {
                currentString.append(current);
                i++;
            }
        }

        appendFStringTextPart(parts, currentString, stringInfo.raw, ctx);
        FStringNode node = new FStringNode(parts);
        setLocation(node, ctx);
        return node;
    }

    private void appendFStringTextPart(
            List<FStringPart> parts,
            StringBuilder text,
            boolean raw,
            org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (text.length() == 0) {
            return;
        }
        String value = raw ? text.toString() : decodePythonEscapes(text.toString(), ctx);
        parts.add(new FStringPart.StringPart(value, sourceSpan(ctx)));
        text.setLength(0);
    }

    /**
     * Find the closing brace while ignoring braces inside quoted string
     * literals in the embedded expression.
     */
    private int findMatchingFStringBrace(String content, int start) {
        int depth = 1;
        char quote = '\0';
        int quoteLength = 0;

        for (int i = start; i < content.length(); i++) {
            char current = content.charAt(i);
            if (quote != '\0') {
                if (current == '\\') {
                    i++;
                    continue;
                }
                if (quoteLength == 3) {
                    if (startsWithRepeatedQuote(content, i, quote, 3)) {
                        i += 2;
                        quote = '\0';
                        quoteLength = 0;
                    }
                } else if (current == quote) {
                    quote = '\0';
                    quoteLength = 0;
                }
                continue;
            }

            if (current == '\'' || current == '"') {
                quote = current;
                quoteLength = startsWithRepeatedQuote(content, i, current, 3) ? 3 : 1;
                if (quoteLength == 3) {
                    i += 2;
                }
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private SourcePosition sourcePositionAt(
            org.antlr.v4.runtime.ParserRuleContext ctx,
            String tokenText,
            int tokenIndex) {
        int localLine = ctx.getStart().getLine();
        int line = toAbsoluteLine(localLine);
        int column = toAbsoluteColumn(
                localLine,
                ctx.getStart().getCharPositionInLine());
        int limit = Math.min(Math.max(tokenIndex, 0), tokenText.length());

        for (int i = 0; i < limit; i++) {
            char current = tokenText.charAt(i);
            if (current == '\r') {
                if (i + 1 < limit && tokenText.charAt(i + 1) == '\n') {
                    i++;
                }
                line++;
                column = 0;
            } else if (current == '\n' || current == '\f') {
                line++;
                column = 0;
            } else {
                column++;
            }
        }
        return new SourcePosition(line, column);
    }

    private IllegalArgumentException fStringError(
            String message,
            SourcePosition position,
            Throwable cause) {
        String locatedMessage = message + " at line " + position.line +
                ", column " + position.column;
        return cause == null
                ? new IllegalArgumentException(locatedMessage)
                : new IllegalArgumentException(locatedMessage, cause);
    }

    /** Parse an embedded f-string expression and require complete consumption. */
    private Expression parseExpressionFromString(
            String expressionText,
            SourcePosition sourcePosition) {
        SyntaxErrorCollector lexerErrors = new SyntaxErrorCollector();
        SyntaxErrorCollector parserErrors = new SyntaxErrorCollector();

        try {
            org.antlr.v4.runtime.CharStream input = CharStreams.fromString(expressionText);
            FlaskLexer lexer = new FlaskLexer(input);
            lexer.removeErrorListeners();
            lexer.addErrorListener(lexerErrors);

            CommonTokenStream tokens = new CommonTokenStream(lexer);
            FlaskParser parser = new FlaskParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(parserErrors);

            FlaskParser.ExpressionContext expressionContext = parser.expression();

            // FlaskLexerBase emits a synthetic NEWLINE before EOF.  It is not
            // part of the expression, but no other trailing token is accepted.
            while (tokens.LA(1) == FlaskLexer.NEWLINE) {
                tokens.consume();
            }

            if (lexerErrors.hasErrors()) {
                throw fStringError(
                        "Invalid f-string expression: " + lexerErrors.getFirstError(),
                        sourcePosition,
                        null);
            }
            if (parserErrors.hasErrors() || parser.getNumberOfSyntaxErrors() != 0) {
                String detail = parserErrors.hasErrors()
                        ? parserErrors.getFirstError()
                        : "syntax error";
                throw fStringError(
                        "Invalid f-string expression: " + detail,
                        sourcePosition,
                        null);
            }
            if (tokens.LA(1) != Token.EOF) {
                throw fStringError(
                        "Unsupported trailing syntax in f-string expression near '" +
                        tokens.LT(1).getText() + "'",
                        sourcePosition,
                        null);
            }
            if (expressionContext == null) {
                throw fStringError(
                        "F-string expression did not produce a parse tree",
                        sourcePosition,
                        null);
            }

            ASTBuilder embeddedBuilder = new ASTBuilder(
                    sourceFile,
                    sourcePosition.line - 1,
                    sourcePosition.column);
            ASTNode result = embeddedBuilder.visit(expressionContext);
            if (!(result instanceof Expression)) {
                throw fStringError(
                        "F-string expression did not produce an expression AST",
                        sourcePosition,
                        null);
            }
            return (Expression) result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw fStringError("Failed to parse f-string expression", sourcePosition, e);
        }
    }
}
