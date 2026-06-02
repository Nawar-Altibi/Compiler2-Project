// Generated from C:/Users/abdsa/IdeaProjects/Compiler_project/src/Rules/CssParser.g4 by ANTLR 4.13.2
package compilers.html_css.antlr;
import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link CssParser}.
 */
public interface CssParserListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link CssParser#stylesheet}.
	 * @param ctx the parse tree
	 */
	void enterStylesheet(CssParser.StylesheetContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParser#stylesheet}.
	 * @param ctx the parse tree
	 */
	void exitStylesheet(CssParser.StylesheetContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParser#ruleset}.
	 * @param ctx the parse tree
	 */
	void enterRuleset(CssParser.RulesetContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParser#ruleset}.
	 * @param ctx the parse tree
	 */
	void exitRuleset(CssParser.RulesetContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParser#selectorGroup}.
	 * @param ctx the parse tree
	 */
	void enterSelectorGroup(CssParser.SelectorGroupContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParser#selectorGroup}.
	 * @param ctx the parse tree
	 */
	void exitSelectorGroup(CssParser.SelectorGroupContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParser#selector}.
	 * @param ctx the parse tree
	 */
	void enterSelector(CssParser.SelectorContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParser#selector}.
	 * @param ctx the parse tree
	 */
	void exitSelector(CssParser.SelectorContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParser#simpleSelector}.
	 * @param ctx the parse tree
	 */
	void enterSimpleSelector(CssParser.SimpleSelectorContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParser#simpleSelector}.
	 * @param ctx the parse tree
	 */
	void exitSimpleSelector(CssParser.SimpleSelectorContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParser#typeSelector}.
	 * @param ctx the parse tree
	 */
	void enterTypeSelector(CssParser.TypeSelectorContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParser#typeSelector}.
	 * @param ctx the parse tree
	 */
	void exitTypeSelector(CssParser.TypeSelectorContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParser#classSelector}.
	 * @param ctx the parse tree
	 */
	void enterClassSelector(CssParser.ClassSelectorContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParser#classSelector}.
	 * @param ctx the parse tree
	 */
	void exitClassSelector(CssParser.ClassSelectorContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParser#idSelector}.
	 * @param ctx the parse tree
	 */
	void enterIdSelector(CssParser.IdSelectorContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParser#idSelector}.
	 * @param ctx the parse tree
	 */
	void exitIdSelector(CssParser.IdSelectorContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParser#declaration}.
	 * @param ctx the parse tree
	 */
	void enterDeclaration(CssParser.DeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParser#declaration}.
	 * @param ctx the parse tree
	 */
	void exitDeclaration(CssParser.DeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParser#value}.
	 * @param ctx the parse tree
	 */
	void enterValue(CssParser.ValueContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParser#value}.
	 * @param ctx the parse tree
	 */
	void exitValue(CssParser.ValueContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParser#valuePart}.
	 * @param ctx the parse tree
	 */
	void enterValuePart(CssParser.ValuePartContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParser#valuePart}.
	 * @param ctx the parse tree
	 */
	void exitValuePart(CssParser.ValuePartContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParser#functionCall}.
	 * @param ctx the parse tree
	 */
	void enterFunctionCall(CssParser.FunctionCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParser#functionCall}.
	 * @param ctx the parse tree
	 */
	void exitFunctionCall(CssParser.FunctionCallContext ctx);
}









