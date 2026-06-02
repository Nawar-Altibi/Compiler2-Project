// Generated from C:/Users/abdsa/IdeaProjects/Compiler_project/src/Rules/HtmlCssParser.g4 by ANTLR 4.13.2
package compilers.html_css.antlr;
import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link HtmlCssParser}.
 */
public interface HtmlCssParserListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link HtmlCssParser#htmlDocument}.
	 * @param ctx the parse tree
	 */
	void enterHtmlDocument(HtmlCssParser.HtmlDocumentContext ctx);
	/**
	 * Exit a parse tree produced by {@link HtmlCssParser#htmlDocument}.
	 * @param ctx the parse tree
	 */
	void exitHtmlDocument(HtmlCssParser.HtmlDocumentContext ctx);
	/**
	 * Enter a parse tree produced by {@link HtmlCssParser#htmlChardata}.
	 * @param ctx the parse tree
	 */
	void enterHtmlChardata(HtmlCssParser.HtmlChardataContext ctx);
	/**
	 * Exit a parse tree produced by {@link HtmlCssParser#htmlChardata}.
	 * @param ctx the parse tree
	 */
	void exitHtmlChardata(HtmlCssParser.HtmlChardataContext ctx);
	/**
	 * Enter a parse tree produced by {@link HtmlCssParser#htmlComment}.
	 * @param ctx the parse tree
	 */
	void enterHtmlComment(HtmlCssParser.HtmlCommentContext ctx);
	/**
	 * Exit a parse tree produced by {@link HtmlCssParser#htmlComment}.
	 * @param ctx the parse tree
	 */
	void exitHtmlComment(HtmlCssParser.HtmlCommentContext ctx);
	/**
	 * Enter a parse tree produced by the {@code selfClosingElement}
	 * labeled alternative in {@link HtmlCssParser#htmlElement}.
	 * @param ctx the parse tree
	 */
	void enterSelfClosingElement(HtmlCssParser.SelfClosingElementContext ctx);
	/**
	 * Exit a parse tree produced by the {@code selfClosingElement}
	 * labeled alternative in {@link HtmlCssParser#htmlElement}.
	 * @param ctx the parse tree
	 */
	void exitSelfClosingElement(HtmlCssParser.SelfClosingElementContext ctx);
	/**
	 * Enter a parse tree produced by the {@code normalElement}
	 * labeled alternative in {@link HtmlCssParser#htmlElement}.
	 * @param ctx the parse tree
	 */
	void enterNormalElement(HtmlCssParser.NormalElementContext ctx);
	/**
	 * Exit a parse tree produced by the {@code normalElement}
	 * labeled alternative in {@link HtmlCssParser#htmlElement}.
	 * @param ctx the parse tree
	 */
	void exitNormalElement(HtmlCssParser.NormalElementContext ctx);
	/**
	 * Enter a parse tree produced by the {@code styleElement}
	 * labeled alternative in {@link HtmlCssParser#htmlElement}.
	 * @param ctx the parse tree
	 */
	void enterStyleElement(HtmlCssParser.StyleElementContext ctx);
	/**
	 * Exit a parse tree produced by the {@code styleElement}
	 * labeled alternative in {@link HtmlCssParser#htmlElement}.
	 * @param ctx the parse tree
	 */
	void exitStyleElement(HtmlCssParser.StyleElementContext ctx);
	/**
	 * Enter a parse tree produced by {@link HtmlCssParser#htmlContent}.
	 * @param ctx the parse tree
	 */
	void enterHtmlContent(HtmlCssParser.HtmlContentContext ctx);
	/**
	 * Exit a parse tree produced by {@link HtmlCssParser#htmlContent}.
	 * @param ctx the parse tree
	 */
	void exitHtmlContent(HtmlCssParser.HtmlContentContext ctx);
	/**
	 * Enter a parse tree produced by {@link HtmlCssParser#htmlAttribute}.
	 * @param ctx the parse tree
	 */
	void enterHtmlAttribute(HtmlCssParser.HtmlAttributeContext ctx);
	/**
	 * Exit a parse tree produced by {@link HtmlCssParser#htmlAttribute}.
	 * @param ctx the parse tree
	 */
	void exitHtmlAttribute(HtmlCssParser.HtmlAttributeContext ctx);
	/**
	 * Enter a parse tree produced by {@link HtmlCssParser#style}.
	 * @param ctx the parse tree
	 */
	void enterStyle(HtmlCssParser.StyleContext ctx);
	/**
	 * Exit a parse tree produced by {@link HtmlCssParser#style}.
	 * @param ctx the parse tree
	 */
	void exitStyle(HtmlCssParser.StyleContext ctx);
	/**
	 * Enter a parse tree produced by {@link HtmlCssParser#jinjaExpression}.
	 * @param ctx the parse tree
	 */
	void enterJinjaExpression(HtmlCssParser.JinjaExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link HtmlCssParser#jinjaExpression}.
	 * @param ctx the parse tree
	 */
	void exitJinjaExpression(HtmlCssParser.JinjaExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link HtmlCssParser#jinjaStatement}.
	 * @param ctx the parse tree
	 */
	void enterJinjaStatement(HtmlCssParser.JinjaStatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link HtmlCssParser#jinjaStatement}.
	 * @param ctx the parse tree
	 */
	void exitJinjaStatement(HtmlCssParser.JinjaStatementContext ctx);
}









