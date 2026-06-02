// Generated from C:/Users/abdsa/IdeaProjects/Compiler_project/src/Rules/CssParser.g4 by ANTLR 4.13.2
package compilers.html_css.antlr;
import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link CssParserMI}.
 */
public interface CssParserMIListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link CssParserMI#stylesheet}.
	 * @param ctx the parse tree
	 */
	void enterStylesheet(CssParserMI.StylesheetContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParserMI#stylesheet}.
	 * @param ctx the parse tree
	 */
	void exitStylesheet(CssParserMI.StylesheetContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParserMI#ruleset}.
	 * @param ctx the parse tree
	 */
	void enterRuleset(CssParserMI.RulesetContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParserMI#ruleset}.
	 * @param ctx the parse tree
	 */
	void exitRuleset(CssParserMI.RulesetContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParserMI#selectorGroup}.
	 * @param ctx the parse tree
	 */
	void enterSelectorGroup(CssParserMI.SelectorGroupContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParserMI#selectorGroup}.
	 * @param ctx the parse tree
	 */
	void exitSelectorGroup(CssParserMI.SelectorGroupContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParserMI#selector}.
	 * @param ctx the parse tree
	 */
	void enterSelector(CssParserMI.SelectorContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParserMI#selector}.
	 * @param ctx the parse tree
	 */
	void exitSelector(CssParserMI.SelectorContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParserMI#simpleSelector}.
	 * @param ctx the parse tree
	 */
	void enterSimpleSelector(CssParserMI.SimpleSelectorContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParserMI#simpleSelector}.
	 * @param ctx the parse tree
	 */
	void exitSimpleSelector(CssParserMI.SimpleSelectorContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParserMI#typeSelector}.
	 * @param ctx the parse tree
	 */
	void enterTypeSelector(CssParserMI.TypeSelectorContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParserMI#typeSelector}.
	 * @param ctx the parse tree
	 */
	void exitTypeSelector(CssParserMI.TypeSelectorContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParserMI#classSelector}.
	 * @param ctx the parse tree
	 */
	void enterClassSelector(CssParserMI.ClassSelectorContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParserMI#classSelector}.
	 * @param ctx the parse tree
	 */
	void exitClassSelector(CssParserMI.ClassSelectorContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParserMI#idSelector}.
	 * @param ctx the parse tree
	 */
	void enterIdSelector(CssParserMI.IdSelectorContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParserMI#idSelector}.
	 * @param ctx the parse tree
	 */
	void exitIdSelector(CssParserMI.IdSelectorContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParserMI#declaration}.
	 * @param ctx the parse tree
	 */
	void enterDeclaration(CssParserMI.DeclarationContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParserMI#declaration}.
	 * @param ctx the parse tree
	 */
	void exitDeclaration(CssParserMI.DeclarationContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParserMI#value}.
	 * @param ctx the parse tree
	 */
	void enterValue(CssParserMI.ValueContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParserMI#value}.
	 * @param ctx the parse tree
	 */
	void exitValue(CssParserMI.ValueContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParserMI#valuePart}.
	 * @param ctx the parse tree
	 */
	void enterValuePart(CssParserMI.ValuePartContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParserMI#valuePart}.
	 * @param ctx the parse tree
	 */
	void exitValuePart(CssParserMI.ValuePartContext ctx);
	/**
	 * Enter a parse tree produced by {@link CssParserMI#functionCall}.
	 * @param ctx the parse tree
	 */
	void enterFunctionCall(CssParserMI.FunctionCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link CssParserMI#functionCall}.
	 * @param ctx the parse tree
	 */
	void exitFunctionCall(CssParserMI.FunctionCallContext ctx);
}









