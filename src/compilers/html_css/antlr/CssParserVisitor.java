// Generated from C:/Users/abdsa/IdeaProjects/Compiler_project/src/Rules/CssParser.g4 by ANTLR 4.13.2
package compilers.html_css.antlr;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link CssParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface CssParserVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link CssParser#stylesheet}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStylesheet(CssParser.StylesheetContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParser#ruleset}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRuleset(CssParser.RulesetContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParser#selectorGroup}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSelectorGroup(CssParser.SelectorGroupContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParser#selector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSelector(CssParser.SelectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParser#simpleSelector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSimpleSelector(CssParser.SimpleSelectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParser#typeSelector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTypeSelector(CssParser.TypeSelectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParser#classSelector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassSelector(CssParser.ClassSelectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParser#idSelector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIdSelector(CssParser.IdSelectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParser#declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDeclaration(CssParser.DeclarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParser#value}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValue(CssParser.ValueContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParser#valuePart}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValuePart(CssParser.ValuePartContext ctx);
	/**
	 * Visit a parse tree produced by {@link CssParser#functionCall}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunctionCall(CssParser.FunctionCallContext ctx);
}









