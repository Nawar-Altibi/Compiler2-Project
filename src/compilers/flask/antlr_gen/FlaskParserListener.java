// Generated from /home/ghaith/IdeaProjects/Compiler2-Project/src/compilers/flask/antlr_gen/FlaskParser.g4 by ANTLR 4.13.1
package compilers.flask.antlr_gen;

import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link FlaskParser}.
 */
public interface FlaskParserListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link FlaskParser#program}.
	 * @param ctx the parse tree
	 */
	void enterProgram(FlaskParser.ProgramContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#program}.
	 * @param ctx the parse tree
	 */
	void exitProgram(FlaskParser.ProgramContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterStatement(FlaskParser.StatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitStatement(FlaskParser.StatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#simple_statement}.
	 * @param ctx the parse tree
	 */
	void enterSimple_statement(FlaskParser.Simple_statementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#simple_statement}.
	 * @param ctx the parse tree
	 */
	void exitSimple_statement(FlaskParser.Simple_statementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#small_stmt}.
	 * @param ctx the parse tree
	 */
	void enterSmall_stmt(FlaskParser.Small_stmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#small_stmt}.
	 * @param ctx the parse tree
	 */
	void exitSmall_stmt(FlaskParser.Small_stmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#exprOrAssignment}.
	 * @param ctx the parse tree
	 */
	void enterExprOrAssignment(FlaskParser.ExprOrAssignmentContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#exprOrAssignment}.
	 * @param ctx the parse tree
	 */
	void exitExprOrAssignment(FlaskParser.ExprOrAssignmentContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#returnStatement}.
	 * @param ctx the parse tree
	 */
	void enterReturnStatement(FlaskParser.ReturnStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#returnStatement}.
	 * @param ctx the parse tree
	 */
	void exitReturnStatement(FlaskParser.ReturnStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#passStatement}.
	 * @param ctx the parse tree
	 */
	void enterPassStatement(FlaskParser.PassStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#passStatement}.
	 * @param ctx the parse tree
	 */
	void exitPassStatement(FlaskParser.PassStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#breakStatement}.
	 * @param ctx the parse tree
	 */
	void enterBreakStatement(FlaskParser.BreakStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#breakStatement}.
	 * @param ctx the parse tree
	 */
	void exitBreakStatement(FlaskParser.BreakStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#continueStatement}.
	 * @param ctx the parse tree
	 */
	void enterContinueStatement(FlaskParser.ContinueStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#continueStatement}.
	 * @param ctx the parse tree
	 */
	void exitContinueStatement(FlaskParser.ContinueStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#delStatement}.
	 * @param ctx the parse tree
	 */
	void enterDelStatement(FlaskParser.DelStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#delStatement}.
	 * @param ctx the parse tree
	 */
	void exitDelStatement(FlaskParser.DelStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#targetList}.
	 * @param ctx the parse tree
	 */
	void enterTargetList(FlaskParser.TargetListContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#targetList}.
	 * @param ctx the parse tree
	 */
	void exitTargetList(FlaskParser.TargetListContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#assertStatement}.
	 * @param ctx the parse tree
	 */
	void enterAssertStatement(FlaskParser.AssertStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#assertStatement}.
	 * @param ctx the parse tree
	 */
	void exitAssertStatement(FlaskParser.AssertStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#globalStatement}.
	 * @param ctx the parse tree
	 */
	void enterGlobalStatement(FlaskParser.GlobalStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#globalStatement}.
	 * @param ctx the parse tree
	 */
	void exitGlobalStatement(FlaskParser.GlobalStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#raiseStatement}.
	 * @param ctx the parse tree
	 */
	void enterRaiseStatement(FlaskParser.RaiseStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#raiseStatement}.
	 * @param ctx the parse tree
	 */
	void exitRaiseStatement(FlaskParser.RaiseStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#compound_statement}.
	 * @param ctx the parse tree
	 */
	void enterCompound_statement(FlaskParser.Compound_statementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#compound_statement}.
	 * @param ctx the parse tree
	 */
	void exitCompound_statement(FlaskParser.Compound_statementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#ifStatement}.
	 * @param ctx the parse tree
	 */
	void enterIfStatement(FlaskParser.IfStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#ifStatement}.
	 * @param ctx the parse tree
	 */
	void exitIfStatement(FlaskParser.IfStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#forStatement}.
	 * @param ctx the parse tree
	 */
	void enterForStatement(FlaskParser.ForStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#forStatement}.
	 * @param ctx the parse tree
	 */
	void exitForStatement(FlaskParser.ForStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#whileStatement}.
	 * @param ctx the parse tree
	 */
	void enterWhileStatement(FlaskParser.WhileStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#whileStatement}.
	 * @param ctx the parse tree
	 */
	void exitWhileStatement(FlaskParser.WhileStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#withStatement}.
	 * @param ctx the parse tree
	 */
	void enterWithStatement(FlaskParser.WithStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#withStatement}.
	 * @param ctx the parse tree
	 */
	void exitWithStatement(FlaskParser.WithStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#withItem}.
	 * @param ctx the parse tree
	 */
	void enterWithItem(FlaskParser.WithItemContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#withItem}.
	 * @param ctx the parse tree
	 */
	void exitWithItem(FlaskParser.WithItemContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#tryStatement}.
	 * @param ctx the parse tree
	 */
	void enterTryStatement(FlaskParser.TryStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#tryStatement}.
	 * @param ctx the parse tree
	 */
	void exitTryStatement(FlaskParser.TryStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#exceptClause}.
	 * @param ctx the parse tree
	 */
	void enterExceptClause(FlaskParser.ExceptClauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#exceptClause}.
	 * @param ctx the parse tree
	 */
	void exitExceptClause(FlaskParser.ExceptClauseContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#classStatement}.
	 * @param ctx the parse tree
	 */
	void enterClassStatement(FlaskParser.ClassStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#classStatement}.
	 * @param ctx the parse tree
	 */
	void exitClassStatement(FlaskParser.ClassStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#decoratedDef}.
	 * @param ctx the parse tree
	 */
	void enterDecoratedDef(FlaskParser.DecoratedDefContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#decoratedDef}.
	 * @param ctx the parse tree
	 */
	void exitDecoratedDef(FlaskParser.DecoratedDefContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#decorator}.
	 * @param ctx the parse tree
	 */
	void enterDecorator(FlaskParser.DecoratorContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#decorator}.
	 * @param ctx the parse tree
	 */
	void exitDecorator(FlaskParser.DecoratorContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#functionDef}.
	 * @param ctx the parse tree
	 */
	void enterFunctionDef(FlaskParser.FunctionDefContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#functionDef}.
	 * @param ctx the parse tree
	 */
	void exitFunctionDef(FlaskParser.FunctionDefContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#parameters}.
	 * @param ctx the parse tree
	 */
	void enterParameters(FlaskParser.ParametersContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#parameters}.
	 * @param ctx the parse tree
	 */
	void exitParameters(FlaskParser.ParametersContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#parameter}.
	 * @param ctx the parse tree
	 */
	void enterParameter(FlaskParser.ParameterContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#parameter}.
	 * @param ctx the parse tree
	 */
	void exitParameter(FlaskParser.ParameterContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#suite}.
	 * @param ctx the parse tree
	 */
	void enterSuite(FlaskParser.SuiteContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#suite}.
	 * @param ctx the parse tree
	 */
	void exitSuite(FlaskParser.SuiteContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#block}.
	 * @param ctx the parse tree
	 */
	void enterBlock(FlaskParser.BlockContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#block}.
	 * @param ctx the parse tree
	 */
	void exitBlock(FlaskParser.BlockContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#augmentedAssignmentOp}.
	 * @param ctx the parse tree
	 */
	void enterAugmentedAssignmentOp(FlaskParser.AugmentedAssignmentOpContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#augmentedAssignmentOp}.
	 * @param ctx the parse tree
	 */
	void exitAugmentedAssignmentOp(FlaskParser.AugmentedAssignmentOpContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#target}.
	 * @param ctx the parse tree
	 */
	void enterTarget(FlaskParser.TargetContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#target}.
	 * @param ctx the parse tree
	 */
	void exitTarget(FlaskParser.TargetContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#target_trailer}.
	 * @param ctx the parse tree
	 */
	void enterTarget_trailer(FlaskParser.Target_trailerContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#target_trailer}.
	 * @param ctx the parse tree
	 */
	void exitTarget_trailer(FlaskParser.Target_trailerContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#importStatement}.
	 * @param ctx the parse tree
	 */
	void enterImportStatement(FlaskParser.ImportStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#importStatement}.
	 * @param ctx the parse tree
	 */
	void exitImportStatement(FlaskParser.ImportStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#importNameStatement}.
	 * @param ctx the parse tree
	 */
	void enterImportNameStatement(FlaskParser.ImportNameStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#importNameStatement}.
	 * @param ctx the parse tree
	 */
	void exitImportNameStatement(FlaskParser.ImportNameStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#importFromStatement}.
	 * @param ctx the parse tree
	 */
	void enterImportFromStatement(FlaskParser.ImportFromStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#importFromStatement}.
	 * @param ctx the parse tree
	 */
	void exitImportFromStatement(FlaskParser.ImportFromStatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#importList}.
	 * @param ctx the parse tree
	 */
	void enterImportList(FlaskParser.ImportListContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#importList}.
	 * @param ctx the parse tree
	 */
	void exitImportList(FlaskParser.ImportListContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#dottedName}.
	 * @param ctx the parse tree
	 */
	void enterDottedName(FlaskParser.DottedNameContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#dottedName}.
	 * @param ctx the parse tree
	 */
	void exitDottedName(FlaskParser.DottedNameContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterExpression(FlaskParser.ExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitExpression(FlaskParser.ExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#or_boolean_expression}.
	 * @param ctx the parse tree
	 */
	void enterOr_boolean_expression(FlaskParser.Or_boolean_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#or_boolean_expression}.
	 * @param ctx the parse tree
	 */
	void exitOr_boolean_expression(FlaskParser.Or_boolean_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#and_boolean_expression}.
	 * @param ctx the parse tree
	 */
	void enterAnd_boolean_expression(FlaskParser.And_boolean_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#and_boolean_expression}.
	 * @param ctx the parse tree
	 */
	void exitAnd_boolean_expression(FlaskParser.And_boolean_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#not_boolean_expression}.
	 * @param ctx the parse tree
	 */
	void enterNot_boolean_expression(FlaskParser.Not_boolean_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#not_boolean_expression}.
	 * @param ctx the parse tree
	 */
	void exitNot_boolean_expression(FlaskParser.Not_boolean_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#comparison_expression}.
	 * @param ctx the parse tree
	 */
	void enterComparison_expression(FlaskParser.Comparison_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#comparison_expression}.
	 * @param ctx the parse tree
	 */
	void exitComparison_expression(FlaskParser.Comparison_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#comp_op}.
	 * @param ctx the parse tree
	 */
	void enterComp_op(FlaskParser.Comp_opContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#comp_op}.
	 * @param ctx the parse tree
	 */
	void exitComp_op(FlaskParser.Comp_opContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#additive_expression}.
	 * @param ctx the parse tree
	 */
	void enterAdditive_expression(FlaskParser.Additive_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#additive_expression}.
	 * @param ctx the parse tree
	 */
	void exitAdditive_expression(FlaskParser.Additive_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#multiplicative_expression}.
	 * @param ctx the parse tree
	 */
	void enterMultiplicative_expression(FlaskParser.Multiplicative_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#multiplicative_expression}.
	 * @param ctx the parse tree
	 */
	void exitMultiplicative_expression(FlaskParser.Multiplicative_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#unary_expression}.
	 * @param ctx the parse tree
	 */
	void enterUnary_expression(FlaskParser.Unary_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#unary_expression}.
	 * @param ctx the parse tree
	 */
	void exitUnary_expression(FlaskParser.Unary_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#power_expression}.
	 * @param ctx the parse tree
	 */
	void enterPower_expression(FlaskParser.Power_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#power_expression}.
	 * @param ctx the parse tree
	 */
	void exitPower_expression(FlaskParser.Power_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#atom_expression}.
	 * @param ctx the parse tree
	 */
	void enterAtom_expression(FlaskParser.Atom_expressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#atom_expression}.
	 * @param ctx the parse tree
	 */
	void exitAtom_expression(FlaskParser.Atom_expressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#atom}.
	 * @param ctx the parse tree
	 */
	void enterAtom(FlaskParser.AtomContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#atom}.
	 * @param ctx the parse tree
	 */
	void exitAtom(FlaskParser.AtomContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#trailer}.
	 * @param ctx the parse tree
	 */
	void enterTrailer(FlaskParser.TrailerContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#trailer}.
	 * @param ctx the parse tree
	 */
	void exitTrailer(FlaskParser.TrailerContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#dict_or_set}.
	 * @param ctx the parse tree
	 */
	void enterDict_or_set(FlaskParser.Dict_or_setContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#dict_or_set}.
	 * @param ctx the parse tree
	 */
	void exitDict_or_set(FlaskParser.Dict_or_setContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#dict_items}.
	 * @param ctx the parse tree
	 */
	void enterDict_items(FlaskParser.Dict_itemsContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#dict_items}.
	 * @param ctx the parse tree
	 */
	void exitDict_items(FlaskParser.Dict_itemsContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#dict_item}.
	 * @param ctx the parse tree
	 */
	void enterDict_item(FlaskParser.Dict_itemContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#dict_item}.
	 * @param ctx the parse tree
	 */
	void exitDict_item(FlaskParser.Dict_itemContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#expression_list}.
	 * @param ctx the parse tree
	 */
	void enterExpression_list(FlaskParser.Expression_listContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#expression_list}.
	 * @param ctx the parse tree
	 */
	void exitExpression_list(FlaskParser.Expression_listContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#arglist}.
	 * @param ctx the parse tree
	 */
	void enterArglist(FlaskParser.ArglistContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#arglist}.
	 * @param ctx the parse tree
	 */
	void exitArglist(FlaskParser.ArglistContext ctx);
	/**
	 * Enter a parse tree produced by {@link FlaskParser#argument}.
	 * @param ctx the parse tree
	 */
	void enterArgument(FlaskParser.ArgumentContext ctx);
	/**
	 * Exit a parse tree produced by {@link FlaskParser#argument}.
	 * @param ctx the parse tree
	 */
	void exitArgument(FlaskParser.ArgumentContext ctx);
}