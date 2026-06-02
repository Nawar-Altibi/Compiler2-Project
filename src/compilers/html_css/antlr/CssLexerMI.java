// Generated from C:/Users/abdsa/IdeaProjects/Compiler_project/src/Rules/CssLexer.g4 by ANTLR 4.13.2
package compilers.html_css.antlr;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.*;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast", "CheckReturnValue", "this-escape"})
public class CssLexerMI extends Lexer {
	static { RuntimeMetaData.checkVersion("4.13.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		WS=1, LBRACE=2, RBRACE=3, COLON=4, SEMI=5, COMMA=6, RIGHT_BRACKET=7, LEFT_BRACKET=8, 
		GT=9, DOT=10, HASH=11, HexColor=12, NUMBER=13, UNIT=14, STRING=15, FUNCTION=16, 
		IDENT=17;
	public static String[] channelNames = {
		"DEFAULT_TOKEN_CHANNEL", "HIDDEN"
	};

	public static String[] modeNames = {
		"DEFAULT_MODE"
	};

	private static String[] makeRuleNames() {
		return new String[] {
			"WS", "LBRACE", "RBRACE", "COLON", "SEMI", "COMMA", "RIGHT_BRACKET", 
			"LEFT_BRACKET", "GT", "DOT", "HASH", "HexColor", "NUMBER", "UNIT", "STRING", 
			"IDENT_START", "IDENT_CHAR", "FUNCTION", "IDENT"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, null, "'{'", "'}'", "':'", "';'", "','", "')'", "'('", "'>'", "'.'", 
			"'#'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, "WS", "LBRACE", "RBRACE", "COLON", "SEMI", "COMMA", "RIGHT_BRACKET", 
			"LEFT_BRACKET", "GT", "DOT", "HASH", "HexColor", "NUMBER", "UNIT", "STRING", 
			"FUNCTION", "IDENT"
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


	public CssLexerMI(CharStream input) {
		super(input);
		_interp = new LexerATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@Override
	public String getGrammarFileName() { return "CssLexer.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public String[] getChannelNames() { return channelNames; }

	@Override
	public String[] getModeNames() { return modeNames; }

	@Override
	public ATN getATN() { return _ATN; }

	public static final String _serializedATN =
		"\u0004\u0000\u0011\u0087\u0006\uffff\uffff\u0002\u0000\u0007\u0000\u0002"+
		"\u0001\u0007\u0001\u0002\u0002\u0007\u0002\u0002\u0003\u0007\u0003\u0002"+
		"\u0004\u0007\u0004\u0002\u0005\u0007\u0005\u0002\u0006\u0007\u0006\u0002"+
		"\u0007\u0007\u0007\u0002\b\u0007\b\u0002\t\u0007\t\u0002\n\u0007\n\u0002"+
		"\u000b\u0007\u000b\u0002\f\u0007\f\u0002\r\u0007\r\u0002\u000e\u0007\u000e"+
		"\u0002\u000f\u0007\u000f\u0002\u0010\u0007\u0010\u0002\u0011\u0007\u0011"+
		"\u0002\u0012\u0007\u0012\u0001\u0000\u0004\u0000)\b\u0000\u000b\u0000"+
		"\f\u0000*\u0001\u0001\u0001\u0001\u0001\u0002\u0001\u0002\u0001\u0003"+
		"\u0001\u0003\u0001\u0004\u0001\u0004\u0001\u0005\u0001\u0005\u0001\u0006"+
		"\u0001\u0006\u0001\u0007\u0001\u0007\u0001\b\u0001\b\u0001\t\u0001\t\u0001"+
		"\n\u0001\n\u0001\u000b\u0001\u000b\u0004\u000bC\b\u000b\u000b\u000b\f"+
		"\u000bD\u0001\f\u0004\fH\b\f\u000b\f\f\fI\u0001\f\u0001\f\u0004\fN\b\f"+
		"\u000b\f\f\fO\u0003\fR\b\f\u0001\r\u0001\r\u0001\r\u0001\r\u0001\r\u0001"+
		"\r\u0001\r\u0001\r\u0001\r\u0001\r\u0001\r\u0001\r\u0003\r`\b\r\u0001"+
		"\u000e\u0001\u000e\u0005\u000ed\b\u000e\n\u000e\f\u000eg\t\u000e\u0001"+
		"\u000e\u0001\u000e\u0001\u000e\u0005\u000el\b\u000e\n\u000e\f\u000eo\t"+
		"\u000e\u0001\u000e\u0003\u000er\b\u000e\u0001\u000f\u0001\u000f\u0001"+
		"\u0010\u0001\u0010\u0001\u0011\u0001\u0011\u0005\u0011z\b\u0011\n\u0011"+
		"\f\u0011}\t\u0011\u0001\u0011\u0001\u0011\u0001\u0012\u0001\u0012\u0005"+
		"\u0012\u0083\b\u0012\n\u0012\f\u0012\u0086\t\u0012\u0000\u0000\u0013\u0001"+
		"\u0001\u0003\u0002\u0005\u0003\u0007\u0004\t\u0005\u000b\u0006\r\u0007"+
		"\u000f\b\u0011\t\u0013\n\u0015\u000b\u0017\f\u0019\r\u001b\u000e\u001d"+
		"\u000f\u001f\u0000!\u0000#\u0010%\u0011\u0001\u0000\u0007\u0003\u0000"+
		"\t\n\f\r  \u0003\u000009AFaf\u0001\u000009\u0003\u0000\n\n\r\r\"\"\u0003"+
		"\u0000\n\n\r\r\'\'\u0004\u0000--AZ__az\u0005\u0000--09AZ__az\u0093\u0000"+
		"\u0001\u0001\u0000\u0000\u0000\u0000\u0003\u0001\u0000\u0000\u0000\u0000"+
		"\u0005\u0001\u0000\u0000\u0000\u0000\u0007\u0001\u0000\u0000\u0000\u0000"+
		"\t\u0001\u0000\u0000\u0000\u0000\u000b\u0001\u0000\u0000\u0000\u0000\r"+
		"\u0001\u0000\u0000\u0000\u0000\u000f\u0001\u0000\u0000\u0000\u0000\u0011"+
		"\u0001\u0000\u0000\u0000\u0000\u0013\u0001\u0000\u0000\u0000\u0000\u0015"+
		"\u0001\u0000\u0000\u0000\u0000\u0017\u0001\u0000\u0000\u0000\u0000\u0019"+
		"\u0001\u0000\u0000\u0000\u0000\u001b\u0001\u0000\u0000\u0000\u0000\u001d"+
		"\u0001\u0000\u0000\u0000\u0000#\u0001\u0000\u0000\u0000\u0000%\u0001\u0000"+
		"\u0000\u0000\u0001(\u0001\u0000\u0000\u0000\u0003,\u0001\u0000\u0000\u0000"+
		"\u0005.\u0001\u0000\u0000\u0000\u00070\u0001\u0000\u0000\u0000\t2\u0001"+
		"\u0000\u0000\u0000\u000b4\u0001\u0000\u0000\u0000\r6\u0001\u0000\u0000"+
		"\u0000\u000f8\u0001\u0000\u0000\u0000\u0011:\u0001\u0000\u0000\u0000\u0013"+
		"<\u0001\u0000\u0000\u0000\u0015>\u0001\u0000\u0000\u0000\u0017@\u0001"+
		"\u0000\u0000\u0000\u0019G\u0001\u0000\u0000\u0000\u001b_\u0001\u0000\u0000"+
		"\u0000\u001dq\u0001\u0000\u0000\u0000\u001fs\u0001\u0000\u0000\u0000!"+
		"u\u0001\u0000\u0000\u0000#w\u0001\u0000\u0000\u0000%\u0080\u0001\u0000"+
		"\u0000\u0000\')\u0007\u0000\u0000\u0000(\'\u0001\u0000\u0000\u0000)*\u0001"+
		"\u0000\u0000\u0000*(\u0001\u0000\u0000\u0000*+\u0001\u0000\u0000\u0000"+
		"+\u0002\u0001\u0000\u0000\u0000,-\u0005{\u0000\u0000-\u0004\u0001\u0000"+
		"\u0000\u0000./\u0005}\u0000\u0000/\u0006\u0001\u0000\u0000\u000001\u0005"+
		":\u0000\u00001\b\u0001\u0000\u0000\u000023\u0005;\u0000\u00003\n\u0001"+
		"\u0000\u0000\u000045\u0005,\u0000\u00005\f\u0001\u0000\u0000\u000067\u0005"+
		")\u0000\u00007\u000e\u0001\u0000\u0000\u000089\u0005(\u0000\u00009\u0010"+
		"\u0001\u0000\u0000\u0000:;\u0005>\u0000\u0000;\u0012\u0001\u0000\u0000"+
		"\u0000<=\u0005.\u0000\u0000=\u0014\u0001\u0000\u0000\u0000>?\u0005#\u0000"+
		"\u0000?\u0016\u0001\u0000\u0000\u0000@B\u0005#\u0000\u0000AC\u0007\u0001"+
		"\u0000\u0000BA\u0001\u0000\u0000\u0000CD\u0001\u0000\u0000\u0000DB\u0001"+
		"\u0000\u0000\u0000DE\u0001\u0000\u0000\u0000E\u0018\u0001\u0000\u0000"+
		"\u0000FH\u0007\u0002\u0000\u0000GF\u0001\u0000\u0000\u0000HI\u0001\u0000"+
		"\u0000\u0000IG\u0001\u0000\u0000\u0000IJ\u0001\u0000\u0000\u0000JQ\u0001"+
		"\u0000\u0000\u0000KM\u0005.\u0000\u0000LN\u0007\u0002\u0000\u0000ML\u0001"+
		"\u0000\u0000\u0000NO\u0001\u0000\u0000\u0000OM\u0001\u0000\u0000\u0000"+
		"OP\u0001\u0000\u0000\u0000PR\u0001\u0000\u0000\u0000QK\u0001\u0000\u0000"+
		"\u0000QR\u0001\u0000\u0000\u0000R\u001a\u0001\u0000\u0000\u0000ST\u0005"+
		"p\u0000\u0000T`\u0005x\u0000\u0000UV\u0005e\u0000\u0000V`\u0005m\u0000"+
		"\u0000WX\u0005r\u0000\u0000XY\u0005e\u0000\u0000Y`\u0005m\u0000\u0000"+
		"Z`\u0005%\u0000\u0000[\\\u0005v\u0000\u0000\\`\u0005h\u0000\u0000]^\u0005"+
		"v\u0000\u0000^`\u0005w\u0000\u0000_S\u0001\u0000\u0000\u0000_U\u0001\u0000"+
		"\u0000\u0000_W\u0001\u0000\u0000\u0000_Z\u0001\u0000\u0000\u0000_[\u0001"+
		"\u0000\u0000\u0000_]\u0001\u0000\u0000\u0000`\u001c\u0001\u0000\u0000"+
		"\u0000ae\u0005\"\u0000\u0000bd\b\u0003\u0000\u0000cb\u0001\u0000\u0000"+
		"\u0000dg\u0001\u0000\u0000\u0000ec\u0001\u0000\u0000\u0000ef\u0001\u0000"+
		"\u0000\u0000fh\u0001\u0000\u0000\u0000ge\u0001\u0000\u0000\u0000hr\u0005"+
		"\"\u0000\u0000im\u0005\'\u0000\u0000jl\b\u0004\u0000\u0000kj\u0001\u0000"+
		"\u0000\u0000lo\u0001\u0000\u0000\u0000mk\u0001\u0000\u0000\u0000mn\u0001"+
		"\u0000\u0000\u0000np\u0001\u0000\u0000\u0000om\u0001\u0000\u0000\u0000"+
		"pr\u0005\'\u0000\u0000qa\u0001\u0000\u0000\u0000qi\u0001\u0000\u0000\u0000"+
		"r\u001e\u0001\u0000\u0000\u0000st\u0007\u0005\u0000\u0000t \u0001\u0000"+
		"\u0000\u0000uv\u0007\u0006\u0000\u0000v\"\u0001\u0000\u0000\u0000w{\u0003"+
		"\u001f\u000f\u0000xz\u0003!\u0010\u0000yx\u0001\u0000\u0000\u0000z}\u0001"+
		"\u0000\u0000\u0000{y\u0001\u0000\u0000\u0000{|\u0001\u0000\u0000\u0000"+
		"|~\u0001\u0000\u0000\u0000}{\u0001\u0000\u0000\u0000~\u007f\u0005(\u0000"+
		"\u0000\u007f$\u0001\u0000\u0000\u0000\u0080\u0084\u0003\u001f\u000f\u0000"+
		"\u0081\u0083\u0003!\u0010\u0000\u0082\u0081\u0001\u0000\u0000\u0000\u0083"+
		"\u0086\u0001\u0000\u0000\u0000\u0084\u0082\u0001\u0000\u0000\u0000\u0084"+
		"\u0085\u0001\u0000\u0000\u0000\u0085&\u0001\u0000\u0000\u0000\u0086\u0084"+
		"\u0001\u0000\u0000\u0000\f\u0000*DIOQ_emq{\u0084\u0000";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}









