// Generated from C:/Users/abdsa/IdeaProjects/Compiler_project/src/Rules/CssParser.g4 by ANTLR 4.13.2
package compilers.html_css.antlr;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link CssParserMI}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface CssParserMIVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link CssParserMI#stylesheet}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStylesheet(CssParserMI.StylesheetContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParserMI#ruleset}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRuleset(CssParserMI.RulesetContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParserMI#selectorGroup}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSelectorGroup(CssParserMI.SelectorGroupContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParserMI#selector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSelector(CssParserMI.SelectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParserMI#simpleSelector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSimpleSelector(CssParserMI.SimpleSelectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParserMI#typeSelector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTypeSelector(CssParserMI.TypeSelectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParserMI#classSelector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassSelector(CssParserMI.ClassSelectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParserMI#idSelector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIdSelector(CssParserMI.IdSelectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParserMI#declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDeclaration(CssParserMI.DeclarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParserMI#value}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValue(CssParserMI.ValueContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParserMI#valuePart}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValuePart(CssParserMI.ValuePartContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParserMI#functionCall}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunctionCall(CssParserMI.FunctionCallContext ctx);
}









