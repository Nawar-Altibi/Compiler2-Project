// Generated from C:/Users/abdsa/IdeaProjects/Compiler_project/src/Rules/HtmlCssParser.g4 by ANTLR 4.13.2
package compilers.html_css.antlr;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link HtmlCssParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface HtmlCssParserVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link HtmlCssParser#htmlDocument}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHtmlDocument(HtmlCssParser.HtmlDocumentContext ctx);
	/**
	 * Visit a parse tree produced by {@link HtmlCssParser#htmlChardata}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHtmlChardata(HtmlCssParser.HtmlChardataContext ctx);
	/**
	 * Visit a parse tree produced by {@link HtmlCssParser#htmlComment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHtmlComment(HtmlCssParser.HtmlCommentContext ctx);
	/**
	 * Visit a parse tree produced by the {@code selfClosingElement}
	 * labeled alternative in {@link HtmlCssParser#htmlElement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSelfClosingElement(HtmlCssParser.SelfClosingElementContext ctx);
	/**
	 * Visit a parse tree produced by the {@code normalElement}
	 * labeled alternative in {@link HtmlCssParser#htmlElement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNormalElement(HtmlCssParser.NormalElementContext ctx);
	/**
	 * Visit a parse tree produced by the {@code styleElement}
	 * labeled alternative in {@link HtmlCssParser#htmlElement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStyleElement(HtmlCssParser.StyleElementContext ctx);
	/**
	 * Visit a parse tree produced by {@link HtmlCssParser#htmlContent}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHtmlContent(HtmlCssParser.HtmlContentContext ctx);
	/**
	 * Visit a parse tree produced by {@link HtmlCssParser#htmlAttribute}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHtmlAttribute(HtmlCssParser.HtmlAttributeContext ctx);
	/**
	 * Visit a parse tree produced by {@link HtmlCssParser#style}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStyle(HtmlCssParser.StyleContext ctx);
	/**
	 * Visit a parse tree produced by {@link HtmlCssParser#jinjaExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitJinjaExpression(HtmlCssParser.JinjaExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link HtmlCssParser#jinjaStatement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitJinjaStatement(HtmlCssParser.JinjaStatementContext ctx);
}









