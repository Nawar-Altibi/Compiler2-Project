// Generated from C:/Users/abdsa/IdeaProjects/Compiler_project/src/Rules/HtmlCssParser.g4 by ANTLR 4.13.2
package compilers.html_css.antlr;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast", "CheckReturnValue", "this-escape"})
public class HtmlCssParser extends Parser {
	static { RuntimeMetaData.checkVersion("4.13.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		JINJA_EXPRESSION_START=1, JINJA_STATEMENT_START=2, JINJA_COMMENT=3, HTML_COMMENT=4, 
		HTML_CONDITIONAL_COMMENT=5, XML=6, CDATA=7, DTD=8, SCRIPTLET=9, SEA_WS=10, 
		STYLE_OPEN=11, TAG_OPEN=12, HTML_TEXT=13, TAG_CLOSE=14, TAG_SLASH_CLOSE=15, 
		TAG_SLASH=16, TAG_EQUALS=17, TAG_NAME=18, TAG_WHITESPACE=19, ATTVALUE_VALUE=20, 
		ATTRIBUTE=21, STYLE_BODY=22, STYLE_SHORT_BODY=23, JINJA_EXPRESSION_END=24, 
		JINJA_EXPRESSION_CONTENT=25, JINJA_STATEMENT_END=26, JINJA_STATEMENT_CONTENT=27;
	public static final int
		RULE_htmlDocument = 0, RULE_htmlChardata = 1, RULE_htmlComment = 2, RULE_htmlElement = 3, 
		RULE_htmlContent = 4, RULE_htmlAttribute = 5, RULE_style = 6, RULE_jinjaExpression = 7, 
		RULE_jinjaStatement = 8;
	private static String[] makeRuleNames() {
		return new String[] {
			"htmlDocument", "htmlChardata", "htmlComment", "htmlElement", "htmlContent", 
			"htmlAttribute", "style", "jinjaExpression", "jinjaStatement"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'{{'", "'{%'", null, null, null, null, null, null, null, null, 
			null, "'<'", null, "'>'", "'/>'", "'/'", "'='", null, null, null, null, 
			null, null, "'}}'", null, "'%}'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, "JINJA_EXPRESSION_START", "JINJA_STATEMENT_START", "JINJA_COMMENT", 
			"HTML_COMMENT", "HTML_CONDITIONAL_COMMENT", "XML", "CDATA", "DTD", "SCRIPTLET", 
			"SEA_WS", "STYLE_OPEN", "TAG_OPEN", "HTML_TEXT", "TAG_CLOSE", "TAG_SLASH_CLOSE", 
			"TAG_SLASH", "TAG_EQUALS", "TAG_NAME", "TAG_WHITESPACE", "ATTVALUE_VALUE", 
			"ATTRIBUTE", "STYLE_BODY", "STYLE_SHORT_BODY", "JINJA_EXPRESSION_END", 
			"JINJA_EXPRESSION_CONTENT", "JINJA_STATEMENT_END", "JINJA_STATEMENT_CONTENT"
		};
	}
	private static final String[] _SYMBOLIC_NAMES = makeSymbolicNames();
	public static final Vocabulary VOCABULARY = new VocabularyImpl(_LITERAL_NAMES, _SYMBOLIC_NAMES);

	/**
	 * @deprecated Use {@link #VOCABULARY} instead.
	 */
	@Deprecated
	public static final String[] tokenNames;
	static {
		tokenNames = new String[_SYMBOLIC_NAMES.length];
		for (int i = 0; i < tokenNames.length; i++) {
			tokenNames[i] = VOCABULARY.getLiteralName(i);
			if (tokenNames[i] == null) {
				tokenNames[i] = VOCABULARY.getSymbolicName(i);
			}

			if (tokenNames[i] == null) {
				tokenNames[i] = "<INVALID>";
			}
		}
	}

	@Override
	@Deprecated
	public String[] getTokenNames() {
		return tokenNames;
	}

	@Override

	public Vocabulary getVocabulary() {
		return VOCABULARY;
	}

	@Override
	public String getGrammarFileName() { return "HtmlCssParser.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public ATN getATN() { return _ATN; }

	public HtmlCssParser(TokenStream input) {
		super(input);
		_interp = new ParserATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@SuppressWarnings("CheckReturnValue")
	public static class HtmlDocumentContext extends ParserRuleContext {
		public TerminalNode EOF() { return getToken(HtmlCssParser.EOF, 0); }
		public List<HtmlElementContext> htmlElement() {
			return getRuleContexts(HtmlElementContext.class);
		}
		public HtmlElementContext htmlElement(int i) {
			return getRuleContext(HtmlElementContext.class,i);
		}
		public List<HtmlChardataContext> htmlChardata() {
			return getRuleContexts(HtmlChardataContext.class);
		}
		public HtmlChardataContext htmlChardata(int i) {
			return getRuleContext(HtmlChardataContext.class,i);
		}
		public List<HtmlCommentContext> htmlComment() {
			return getRuleContexts(HtmlCommentContext.class);
		}
		public HtmlCommentContext htmlComment(int i) {
			return getRuleContext(HtmlCommentContext.class,i);
		}
		public List<JinjaExpressionContext> jinjaExpression() {
			return getRuleContexts(JinjaExpressionContext.class);
		}
		public JinjaExpressionContext jinjaExpression(int i) {
			return getRuleContext(JinjaExpressionContext.class,i);
		}
		public List<JinjaStatementContext> jinjaStatement() {
			return getRuleContexts(JinjaStatementContext.class);
		}
		public JinjaStatementContext jinjaStatement(int i) {
			return getRuleContext(JinjaStatementContext.class,i);
		}
		public List<TerminalNode> JINJA_COMMENT() { return getTokens(HtmlCssParser.JINJA_COMMENT); }
		public TerminalNode JINJA_COMMENT(int i) {
			return getToken(HtmlCssParser.JINJA_COMMENT, i);
		}
		public List<TerminalNode> DTD() { return getTokens(HtmlCssParser.DTD); }
		public TerminalNode DTD(int i) {
			return getToken(HtmlCssParser.DTD, i);
		}
		public List<TerminalNode> XML() { return getTokens(HtmlCssParser.XML); }
		public TerminalNode XML(int i) {
			return getToken(HtmlCssParser.XML, i);
		}
		public HtmlDocumentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_htmlDocument; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).enterHtmlDocument(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).exitHtmlDocument(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof HtmlCssParserVisitor ) return ((HtmlCssParserVisitor<? extends T>)visitor).visitHtmlDocument(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HtmlDocumentContext htmlDocument() throws RecognitionException {
		HtmlDocumentContext _localctx = new HtmlDocumentContext(_ctx, getState());
		enterRule(_localctx, 0, RULE_htmlDocument);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(28);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while ((((_la) & ~0x3f) == 0 && ((1L << _la) & 15742L) != 0)) {
				{
				setState(26);
				_errHandler.sync(this);
				switch (_input.LA(1)) {
				case STYLE_OPEN:
				case TAG_OPEN:
					{
					setState(18);
					htmlElement();
					}
					break;
				case SEA_WS:
				case HTML_TEXT:
					{
					setState(19);
					htmlChardata();
					}
					break;
				case HTML_COMMENT:
				case HTML_CONDITIONAL_COMMENT:
					{
					setState(20);
					htmlComment();
					}
					break;
				case JINJA_EXPRESSION_START:
					{
					setState(21);
					jinjaExpression();
					}
					break;
				case JINJA_STATEMENT_START:
					{
					setState(22);
					jinjaStatement();
					}
					break;
				case JINJA_COMMENT:
					{
					setState(23);
					match(JINJA_COMMENT);
					}
					break;
				case DTD:
					{
					setState(24);
					match(DTD);
					}
					break;
				case XML:
					{
					setState(25);
					match(XML);
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				}
				setState(30);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(31);
			match(EOF);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class HtmlChardataContext extends ParserRuleContext {
		public TerminalNode HTML_TEXT() { return getToken(HtmlCssParser.HTML_TEXT, 0); }
		public TerminalNode SEA_WS() { return getToken(HtmlCssParser.SEA_WS, 0); }
		public HtmlChardataContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_htmlChardata; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).enterHtmlChardata(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).exitHtmlChardata(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof HtmlCssParserVisitor ) return ((HtmlCssParserVisitor<? extends T>)visitor).visitHtmlChardata(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HtmlChardataContext htmlChardata() throws RecognitionException {
		HtmlChardataContext _localctx = new HtmlChardataContext(_ctx, getState());
		enterRule(_localctx, 2, RULE_htmlChardata);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(33);
			_la = _input.LA(1);
			if ( !(_la==SEA_WS || _la==HTML_TEXT) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class HtmlCommentContext extends ParserRuleContext {
		public TerminalNode HTML_COMMENT() { return getToken(HtmlCssParser.HTML_COMMENT, 0); }
		public TerminalNode HTML_CONDITIONAL_COMMENT() { return getToken(HtmlCssParser.HTML_CONDITIONAL_COMMENT, 0); }
		public HtmlCommentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_htmlComment; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).enterHtmlComment(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).exitHtmlComment(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof HtmlCssParserVisitor ) return ((HtmlCssParserVisitor<? extends T>)visitor).visitHtmlComment(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HtmlCommentContext htmlComment() throws RecognitionException {
		HtmlCommentContext _localctx = new HtmlCommentContext(_ctx, getState());
		enterRule(_localctx, 4, RULE_htmlComment);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(35);
			_la = _input.LA(1);
			if ( !(_la==HTML_COMMENT || _la==HTML_CONDITIONAL_COMMENT) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class HtmlElementContext extends ParserRuleContext {
		public HtmlElementContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_htmlElement; }
	 
		public HtmlElementContext() { }
		public void copyFrom(HtmlElementContext ctx) {
			super.copyFrom(ctx);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class SelfClosingElementContext extends HtmlElementContext {
		public TerminalNode TAG_OPEN() { return getToken(HtmlCssParser.TAG_OPEN, 0); }
		public TerminalNode TAG_NAME() { return getToken(HtmlCssParser.TAG_NAME, 0); }
		public TerminalNode TAG_SLASH_CLOSE() { return getToken(HtmlCssParser.TAG_SLASH_CLOSE, 0); }
		public List<HtmlAttributeContext> htmlAttribute() {
			return getRuleContexts(HtmlAttributeContext.class);
		}
		public HtmlAttributeContext htmlAttribute(int i) {
			return getRuleContext(HtmlAttributeContext.class,i);
		}
		public SelfClosingElementContext(HtmlElementContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).enterSelfClosingElement(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).exitSelfClosingElement(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof HtmlCssParserVisitor ) return ((HtmlCssParserVisitor<? extends T>)visitor).visitSelfClosingElement(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class NormalElementContext extends HtmlElementContext {
		public List<TerminalNode> TAG_OPEN() { return getTokens(HtmlCssParser.TAG_OPEN); }
		public TerminalNode TAG_OPEN(int i) {
			return getToken(HtmlCssParser.TAG_OPEN, i);
		}
		public List<TerminalNode> TAG_NAME() { return getTokens(HtmlCssParser.TAG_NAME); }
		public TerminalNode TAG_NAME(int i) {
			return getToken(HtmlCssParser.TAG_NAME, i);
		}
		public List<TerminalNode> TAG_CLOSE() { return getTokens(HtmlCssParser.TAG_CLOSE); }
		public TerminalNode TAG_CLOSE(int i) {
			return getToken(HtmlCssParser.TAG_CLOSE, i);
		}
		public HtmlContentContext htmlContent() {
			return getRuleContext(HtmlContentContext.class,0);
		}
		public TerminalNode TAG_SLASH() { return getToken(HtmlCssParser.TAG_SLASH, 0); }
		public List<HtmlAttributeContext> htmlAttribute() {
			return getRuleContexts(HtmlAttributeContext.class);
		}
		public HtmlAttributeContext htmlAttribute(int i) {
			return getRuleContext(HtmlAttributeContext.class,i);
		}
		public NormalElementContext(HtmlElementContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).enterNormalElement(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).exitNormalElement(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof HtmlCssParserVisitor ) return ((HtmlCssParserVisitor<? extends T>)visitor).visitNormalElement(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class StyleElementContext extends HtmlElementContext {
		public StyleContext style() {
			return getRuleContext(StyleContext.class,0);
		}
		public StyleElementContext(HtmlElementContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).enterStyleElement(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).exitStyleElement(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof HtmlCssParserVisitor ) return ((HtmlCssParserVisitor<? extends T>)visitor).visitStyleElement(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HtmlElementContext htmlElement() throws RecognitionException {
		HtmlElementContext _localctx = new HtmlElementContext(_ctx, getState());
		enterRule(_localctx, 6, RULE_htmlElement);
		int _la;
		try {
			setState(62);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,4,_ctx) ) {
			case 1:
				_localctx = new SelfClosingElementContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(37);
				match(TAG_OPEN);
				setState(38);
				match(TAG_NAME);
				setState(42);
				_errHandler.sync(this);
				_la = _input.LA(1);
				while (_la==TAG_NAME) {
					{
					{
					setState(39);
					htmlAttribute();
					}
					}
					setState(44);
					_errHandler.sync(this);
					_la = _input.LA(1);
				}
				setState(45);
				match(TAG_SLASH_CLOSE);
				}
				break;
			case 2:
				_localctx = new NormalElementContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(46);
				match(TAG_OPEN);
				setState(47);
				match(TAG_NAME);
				setState(51);
				_errHandler.sync(this);
				_la = _input.LA(1);
				while (_la==TAG_NAME) {
					{
					{
					setState(48);
					htmlAttribute();
					}
					}
					setState(53);
					_errHandler.sync(this);
					_la = _input.LA(1);
				}
				setState(54);
				match(TAG_CLOSE);
				setState(55);
				htmlContent();
				setState(56);
				match(TAG_OPEN);
				setState(57);
				match(TAG_SLASH);
				setState(58);
				match(TAG_NAME);
				setState(59);
				match(TAG_CLOSE);
				}
				break;
			case 3:
				_localctx = new StyleElementContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(61);
				style();
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class HtmlContentContext extends ParserRuleContext {
		public List<HtmlChardataContext> htmlChardata() {
			return getRuleContexts(HtmlChardataContext.class);
		}
		public HtmlChardataContext htmlChardata(int i) {
			return getRuleContext(HtmlChardataContext.class,i);
		}
		public List<HtmlElementContext> htmlElement() {
			return getRuleContexts(HtmlElementContext.class);
		}
		public HtmlElementContext htmlElement(int i) {
			return getRuleContext(HtmlElementContext.class,i);
		}
		public List<HtmlCommentContext> htmlComment() {
			return getRuleContexts(HtmlCommentContext.class);
		}
		public HtmlCommentContext htmlComment(int i) {
			return getRuleContext(HtmlCommentContext.class,i);
		}
		public List<TerminalNode> CDATA() { return getTokens(HtmlCssParser.CDATA); }
		public TerminalNode CDATA(int i) {
			return getToken(HtmlCssParser.CDATA, i);
		}
		public List<JinjaExpressionContext> jinjaExpression() {
			return getRuleContexts(JinjaExpressionContext.class);
		}
		public JinjaExpressionContext jinjaExpression(int i) {
			return getRuleContext(JinjaExpressionContext.class,i);
		}
		public List<JinjaStatementContext> jinjaStatement() {
			return getRuleContexts(JinjaStatementContext.class);
		}
		public JinjaStatementContext jinjaStatement(int i) {
			return getRuleContext(JinjaStatementContext.class,i);
		}
		public List<TerminalNode> JINJA_COMMENT() { return getTokens(HtmlCssParser.JINJA_COMMENT); }
		public TerminalNode JINJA_COMMENT(int i) {
			return getToken(HtmlCssParser.JINJA_COMMENT, i);
		}
		public HtmlContentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_htmlContent; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).enterHtmlContent(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).exitHtmlContent(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof HtmlCssParserVisitor ) return ((HtmlCssParserVisitor<? extends T>)visitor).visitHtmlContent(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HtmlContentContext htmlContent() throws RecognitionException {
		HtmlContentContext _localctx = new HtmlContentContext(_ctx, getState());
		enterRule(_localctx, 8, RULE_htmlContent);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(73);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,6,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					setState(71);
					_errHandler.sync(this);
					switch (_input.LA(1)) {
					case SEA_WS:
					case HTML_TEXT:
						{
						setState(64);
						htmlChardata();
						}
						break;
					case STYLE_OPEN:
					case TAG_OPEN:
						{
						setState(65);
						htmlElement();
						}
						break;
					case HTML_COMMENT:
					case HTML_CONDITIONAL_COMMENT:
						{
						setState(66);
						htmlComment();
						}
						break;
					case CDATA:
						{
						setState(67);
						match(CDATA);
						}
						break;
					case JINJA_EXPRESSION_START:
						{
						setState(68);
						jinjaExpression();
						}
						break;
					case JINJA_STATEMENT_START:
						{
						setState(69);
						jinjaStatement();
						}
						break;
					case JINJA_COMMENT:
						{
						setState(70);
						match(JINJA_COMMENT);
						}
						break;
					default:
						throw new NoViableAltException(this);
					}
					} 
				}
				setState(75);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,6,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class HtmlAttributeContext extends ParserRuleContext {
		public TerminalNode TAG_NAME() { return getToken(HtmlCssParser.TAG_NAME, 0); }
		public TerminalNode TAG_EQUALS() { return getToken(HtmlCssParser.TAG_EQUALS, 0); }
		public TerminalNode ATTVALUE_VALUE() { return getToken(HtmlCssParser.ATTVALUE_VALUE, 0); }
		public HtmlAttributeContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_htmlAttribute; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).enterHtmlAttribute(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).exitHtmlAttribute(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof HtmlCssParserVisitor ) return ((HtmlCssParserVisitor<? extends T>)visitor).visitHtmlAttribute(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HtmlAttributeContext htmlAttribute() throws RecognitionException {
		HtmlAttributeContext _localctx = new HtmlAttributeContext(_ctx, getState());
		enterRule(_localctx, 10, RULE_htmlAttribute);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(76);
			match(TAG_NAME);
			setState(79);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==TAG_EQUALS) {
				{
				setState(77);
				match(TAG_EQUALS);
				setState(78);
				match(ATTVALUE_VALUE);
				}
			}

			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class StyleContext extends ParserRuleContext {
		public TerminalNode STYLE_OPEN() { return getToken(HtmlCssParser.STYLE_OPEN, 0); }
		public TerminalNode STYLE_BODY() { return getToken(HtmlCssParser.STYLE_BODY, 0); }
		public TerminalNode STYLE_SHORT_BODY() { return getToken(HtmlCssParser.STYLE_SHORT_BODY, 0); }
		public StyleContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_style; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).enterStyle(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).exitStyle(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof HtmlCssParserVisitor ) return ((HtmlCssParserVisitor<? extends T>)visitor).visitStyle(this);
			else return visitor.visitChildren(this);
		}
	}

	public final StyleContext style() throws RecognitionException {
		StyleContext _localctx = new StyleContext(_ctx, getState());
		enterRule(_localctx, 12, RULE_style);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(81);
			match(STYLE_OPEN);
			setState(82);
			_la = _input.LA(1);
			if ( !(_la==STYLE_BODY || _la==STYLE_SHORT_BODY) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class JinjaExpressionContext extends ParserRuleContext {
		public TerminalNode JINJA_EXPRESSION_START() { return getToken(HtmlCssParser.JINJA_EXPRESSION_START, 0); }
		public TerminalNode JINJA_EXPRESSION_CONTENT() { return getToken(HtmlCssParser.JINJA_EXPRESSION_CONTENT, 0); }
		public TerminalNode JINJA_EXPRESSION_END() { return getToken(HtmlCssParser.JINJA_EXPRESSION_END, 0); }
		public JinjaExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_jinjaExpression; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).enterJinjaExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).exitJinjaExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof HtmlCssParserVisitor ) return ((HtmlCssParserVisitor<? extends T>)visitor).visitJinjaExpression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final JinjaExpressionContext jinjaExpression() throws RecognitionException {
		JinjaExpressionContext _localctx = new JinjaExpressionContext(_ctx, getState());
		enterRule(_localctx, 14, RULE_jinjaExpression);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(84);
			match(JINJA_EXPRESSION_START);
			setState(85);
			match(JINJA_EXPRESSION_CONTENT);
			setState(86);
			match(JINJA_EXPRESSION_END);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class JinjaStatementContext extends ParserRuleContext {
		public TerminalNode JINJA_STATEMENT_START() { return getToken(HtmlCssParser.JINJA_STATEMENT_START, 0); }
		public TerminalNode JINJA_STATEMENT_CONTENT() { return getToken(HtmlCssParser.JINJA_STATEMENT_CONTENT, 0); }
		public TerminalNode JINJA_STATEMENT_END() { return getToken(HtmlCssParser.JINJA_STATEMENT_END, 0); }
		public JinjaStatementContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_jinjaStatement; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).enterJinjaStatement(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof HtmlCssParserListener ) ((HtmlCssParserListener)listener).exitJinjaStatement(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof HtmlCssParserVisitor ) return ((HtmlCssParserVisitor<? extends T>)visitor).visitJinjaStatement(this);
			else return visitor.visitChildren(this);
		}
	}

	public final JinjaStatementContext jinjaStatement() throws RecognitionException {
		JinjaStatementContext _localctx = new JinjaStatementContext(_ctx, getState());
		enterRule(_localctx, 16, RULE_jinjaStatement);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(88);
			match(JINJA_STATEMENT_START);
			setState(89);
			match(JINJA_STATEMENT_CONTENT);
			setState(90);
			match(JINJA_STATEMENT_END);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static final String _serializedATN =
		"\u0004\u0001\u001b]\u0002\u0000\u0007\u0000\u0002\u0001\u0007\u0001\u0002"+
		"\u0002\u0007\u0002\u0002\u0003\u0007\u0003\u0002\u0004\u0007\u0004\u0002"+
		"\u0005\u0007\u0005\u0002\u0006\u0007\u0006\u0002\u0007\u0007\u0007\u0002"+
		"\b\u0007\b\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000"+
		"\u0001\u0000\u0001\u0000\u0001\u0000\u0005\u0000\u001b\b\u0000\n\u0000"+
		"\f\u0000\u001e\t\u0000\u0001\u0000\u0001\u0000\u0001\u0001\u0001\u0001"+
		"\u0001\u0002\u0001\u0002\u0001\u0003\u0001\u0003\u0001\u0003\u0005\u0003"+
		")\b\u0003\n\u0003\f\u0003,\t\u0003\u0001\u0003\u0001\u0003\u0001\u0003"+
		"\u0001\u0003\u0005\u00032\b\u0003\n\u0003\f\u00035\t\u0003\u0001\u0003"+
		"\u0001\u0003\u0001\u0003\u0001\u0003\u0001\u0003\u0001\u0003\u0001\u0003"+
		"\u0001\u0003\u0003\u0003?\b\u0003\u0001\u0004\u0001\u0004\u0001\u0004"+
		"\u0001\u0004\u0001\u0004\u0001\u0004\u0001\u0004\u0005\u0004H\b\u0004"+
		"\n\u0004\f\u0004K\t\u0004\u0001\u0005\u0001\u0005\u0001\u0005\u0003\u0005"+
		"P\b\u0005\u0001\u0006\u0001\u0006\u0001\u0006\u0001\u0007\u0001\u0007"+
		"\u0001\u0007\u0001\u0007\u0001\b\u0001\b\u0001\b\u0001\b\u0001\b\u0000"+
		"\u0000\t\u0000\u0002\u0004\u0006\b\n\f\u000e\u0010\u0000\u0003\u0002\u0000"+
		"\n\n\r\r\u0001\u0000\u0004\u0005\u0001\u0000\u0016\u0017g\u0000\u001c"+
		"\u0001\u0000\u0000\u0000\u0002!\u0001\u0000\u0000\u0000\u0004#\u0001\u0000"+
		"\u0000\u0000\u0006>\u0001\u0000\u0000\u0000\bI\u0001\u0000\u0000\u0000"+
		"\nL\u0001\u0000\u0000\u0000\fQ\u0001\u0000\u0000\u0000\u000eT\u0001\u0000"+
		"\u0000\u0000\u0010X\u0001\u0000\u0000\u0000\u0012\u001b\u0003\u0006\u0003"+
		"\u0000\u0013\u001b\u0003\u0002\u0001\u0000\u0014\u001b\u0003\u0004\u0002"+
		"\u0000\u0015\u001b\u0003\u000e\u0007\u0000\u0016\u001b\u0003\u0010\b\u0000"+
		"\u0017\u001b\u0005\u0003\u0000\u0000\u0018\u001b\u0005\b\u0000\u0000\u0019"+
		"\u001b\u0005\u0006\u0000\u0000\u001a\u0012\u0001\u0000\u0000\u0000\u001a"+
		"\u0013\u0001\u0000\u0000\u0000\u001a\u0014\u0001\u0000\u0000\u0000\u001a"+
		"\u0015\u0001\u0000\u0000\u0000\u001a\u0016\u0001\u0000\u0000\u0000\u001a"+
		"\u0017\u0001\u0000\u0000\u0000\u001a\u0018\u0001\u0000\u0000\u0000\u001a"+
		"\u0019\u0001\u0000\u0000\u0000\u001b\u001e\u0001\u0000\u0000\u0000\u001c"+
		"\u001a\u0001\u0000\u0000\u0000\u001c\u001d\u0001\u0000\u0000\u0000\u001d"+
		"\u001f\u0001\u0000\u0000\u0000\u001e\u001c\u0001\u0000\u0000\u0000\u001f"+
		" \u0005\u0000\u0000\u0001 \u0001\u0001\u0000\u0000\u0000!\"\u0007\u0000"+
		"\u0000\u0000\"\u0003\u0001\u0000\u0000\u0000#$\u0007\u0001\u0000\u0000"+
		"$\u0005\u0001\u0000\u0000\u0000%&\u0005\f\u0000\u0000&*\u0005\u0012\u0000"+
		"\u0000\')\u0003\n\u0005\u0000(\'\u0001\u0000\u0000\u0000),\u0001\u0000"+
		"\u0000\u0000*(\u0001\u0000\u0000\u0000*+\u0001\u0000\u0000\u0000+-\u0001"+
		"\u0000\u0000\u0000,*\u0001\u0000\u0000\u0000-?\u0005\u000f\u0000\u0000"+
		"./\u0005\f\u0000\u0000/3\u0005\u0012\u0000\u000002\u0003\n\u0005\u0000"+
		"10\u0001\u0000\u0000\u000025\u0001\u0000\u0000\u000031\u0001\u0000\u0000"+
		"\u000034\u0001\u0000\u0000\u000046\u0001\u0000\u0000\u000053\u0001\u0000"+
		"\u0000\u000067\u0005\u000e\u0000\u000078\u0003\b\u0004\u000089\u0005\f"+
		"\u0000\u00009:\u0005\u0010\u0000\u0000:;\u0005\u0012\u0000\u0000;<\u0005"+
		"\u000e\u0000\u0000<?\u0001\u0000\u0000\u0000=?\u0003\f\u0006\u0000>%\u0001"+
		"\u0000\u0000\u0000>.\u0001\u0000\u0000\u0000>=\u0001\u0000\u0000\u0000"+
		"?\u0007\u0001\u0000\u0000\u0000@H\u0003\u0002\u0001\u0000AH\u0003\u0006"+
		"\u0003\u0000BH\u0003\u0004\u0002\u0000CH\u0005\u0007\u0000\u0000DH\u0003"+
		"\u000e\u0007\u0000EH\u0003\u0010\b\u0000FH\u0005\u0003\u0000\u0000G@\u0001"+
		"\u0000\u0000\u0000GA\u0001\u0000\u0000\u0000GB\u0001\u0000\u0000\u0000"+
		"GC\u0001\u0000\u0000\u0000GD\u0001\u0000\u0000\u0000GE\u0001\u0000\u0000"+
		"\u0000GF\u0001\u0000\u0000\u0000HK\u0001\u0000\u0000\u0000IG\u0001\u0000"+
		"\u0000\u0000IJ\u0001\u0000\u0000\u0000J\t\u0001\u0000\u0000\u0000KI\u0001"+
		"\u0000\u0000\u0000LO\u0005\u0012\u0000\u0000MN\u0005\u0011\u0000\u0000"+
		"NP\u0005\u0014\u0000\u0000OM\u0001\u0000\u0000\u0000OP\u0001\u0000\u0000"+
		"\u0000P\u000b\u0001\u0000\u0000\u0000QR\u0005\u000b\u0000\u0000RS\u0007"+
		"\u0002\u0000\u0000S\r\u0001\u0000\u0000\u0000TU\u0005\u0001\u0000\u0000"+
		"UV\u0005\u0019\u0000\u0000VW\u0005\u0018\u0000\u0000W\u000f\u0001\u0000"+
		"\u0000\u0000XY\u0005\u0002\u0000\u0000YZ\u0005\u001b\u0000\u0000Z[\u0005"+
		"\u001a\u0000\u0000[\u0011\u0001\u0000\u0000\u0000\b\u001a\u001c*3>GIO";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}









