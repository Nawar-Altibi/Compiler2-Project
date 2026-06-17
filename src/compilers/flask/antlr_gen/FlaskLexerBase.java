package compilers.flask.antlr_gen;

import org.antlr.v4.runtime.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;

public abstract class FlaskLexerBase extends Lexer {

    private LinkedList<Token> tokens = new LinkedList<>();
    private Deque<Integer> indents = new ArrayDeque<>();
    private int opened = 0;
    private Token lastToken = null;
    private boolean expectIndent = false;  // ← NEW: تتبع إذا كنا نتوقع INDENT
    private boolean eofEmitted = false;


    protected FlaskLexerBase(CharStream input) {
        super(input);
        indents.push(0);
    }

    @Override
    public void emit(Token t) {
        super.setToken(t);
        tokens.offer(t);

        // ✅ NEW: تتبع إذا كان آخر token هو COLON
        if (t.getChannel() == Token.DEFAULT_CHANNEL) {
            if (t.getType() == FlaskLexer.COLON) {
                expectIndent = true;  // الآن نتوقع INDENT في السطر التالي
            } else if (t.getType() != FlaskLexer.NEWLINE &&
                    t.getType() != FlaskLexer.INDENT &&
                    t.getType() != FlaskLexer.DEDENT) {
                // إذا جاء أي token آخر (غير NEWLINE/INDENT/DEDENT), reset
                expectIndent = false;
            }
        }
    }


//    @Override
//    public Token nextToken() {
//        // تحقق إذا وصلنا لنهاية الملف
//        if (_input.LA(1) == EOF) {
//
//            // 1. نظف أي EOF قديم موجود في القائمة
//            for (int i = tokens.size() - 1; i >= 0; i--) {
//                if (tokens.get(i).getType() == EOF) {
//                    tokens.remove(i);
//                }
//            }
//
//            // 2. ✅ التعديل السحري: أضف NEWLINE فقط إذا كان آخر Token ليس NEWLINE
//            // هذا يضمن أن أي جملة في نهاية الملف ستُغلق بشكل صحيح دون إضافة NEWLINE مزدوج
//            if (this.lastToken == null || this.lastToken.getType() != FlaskLexer.NEWLINE) {
//                this.emit(createToken(FlaskLexer.NEWLINE, "\n"));
//            }
//
//            // 3. أغلق أي كتل مفتوحة (DEDENTs) - نفس كودك القديم ولكن بدون شرط indents > 1 في الخارج
//            while (indents.size() > 1) {
//                this.emit(createDedent());
//                indents.pop();
//            }
//
//            // 4. أضف الـ EOF النهائي
//            this.emit(createToken(FlaskLexer.EOF, "<EOF>"));
//        }
//
//        // باقي الكود كما هو تماماً
//        Token next = super.nextToken();
//
//        if (next.getChannel() == Token.DEFAULT_CHANNEL) {
//            this.lastToken = next;
//        }
//
//        return tokens.isEmpty() ? next : tokens.poll();
//    }


    @Override
    public Token nextToken() {
        // Handle EOF: نضيف NEWLINE و DEDENTs مرة واحدة فقط
        if (_input.LA(1) == EOF && !eofEmitted) {

            // ✅ أغلق الباب، لا تدخل هنا مرة أخرى أبداً
            eofEmitted = true;

            // نظف أي EOF قديم
            for (int i = tokens.size() - 1; i >= 0; i--) {
                if (tokens.get(i).getType() == EOF) {
                    tokens.remove(i);
                }
            }

            // أضف NEWLINE إذا كان آخر Token ليس NEWLINE
            if (this.lastToken == null || this.lastToken.getType() != FlaskLexer.NEWLINE) {
                this.emit(createToken(FlaskLexer.NEWLINE, "\n"));
            }

            // أغلق الكتل المفتوحة (DEDENTs)
            while (indents.size() > 1) {
                this.emit(createDedent());
                indents.pop();
            }

            // أضف الـ EOF النهائي
            this.emit(createToken(FlaskLexer.EOF, "<EOF>"));
        }

        Token next = super.nextToken();

        // تحديث lastToken (تأكدنا أيضاً ألا يكون EOF)
        if (next.getChannel() == Token.DEFAULT_CHANNEL && next.getType() != EOF) {
            this.lastToken = next;
        }

        return tokens.isEmpty() ? next : tokens.poll();
    }


    private Token createDedent() {
        CommonToken dedent = createToken(FlaskLexer.DEDENT, "");
        if (this.lastToken != null) {
            dedent.setLine(this.lastToken.getLine());
        }
        return dedent;
    }

    private CommonToken createToken(int type, String text) {
        CommonToken token = new CommonToken(type, text);
        token.setLine(this.getLine());

        int charPos = this.getCharPositionInLine();
        if (type == FlaskLexer.NEWLINE) {
            charPos = Math.max(0, charPos - 1);
        } else if (type == FlaskLexer.INDENT || type == FlaskLexer.DEDENT) {
            charPos = 0;
        }

        token.setCharPositionInLine(charPos);
        token.setStartIndex(this.getCharIndex());
        token.setStopIndex(this.getCharIndex() + text.length() - 1);
        return token;
    }

    static int getIndentationCount(String spaces) {
        int count = 0;
        for (char ch : spaces.toCharArray()) {
            switch (ch) {
                case '\t':
                    count += 8 - (count % 8);
                    break;
                default:
                    count++;
            }
        }
        return count;
    }

    protected boolean atStartOfInput() {
        return super.getCharPositionInLine() == 0 && super.getLine() == 1;
    }

    protected void openBrace() {
        this.opened++;
    }

    protected void closeBrace() {
        this.opened--;
    }

    protected void onNewLine() {
        String fullText = getText();
        String newLine = fullText.replaceAll("[^\r\n\f]+", "");
        String spaces = fullText.replaceAll("[\r\n\f]+", "");

        int next = _input.LA(1);
        int nextnext = _input.LA(2);

        // Skip newlines inside parentheses/brackets/braces or blank lines
        if (opened > 0 || (nextnext != -1 && (next == '\r' || next == '\n' || next == '\f' || next == '#'))) {
            skip();
        } else {
            // Emit NEWLINE token
            emit(createToken(FlaskLexer.NEWLINE, newLine));

            // Calculate indentation
            int indent = getIndentationCount(spaces);
            int previous = indents.isEmpty() ? 0 : indents.peek();

            if (indent == previous) {
                // Same level
                skip();
                expectIndent = false;  // Reset
            } else if (indent > previous) {
                // ========================================
                // ✅ CRITICAL FIX: Validate INDENT
                // ========================================

                // Check if we expected an INDENT (after COLON)
                if (!expectIndent) {
                    throw new RuntimeException(
                            String.format(
                                    "IndentationError: unexpected indent at line %d\n" +
                                            "  Previous indentation: %d spaces\n" +
                                            "  Current indentation: %d spaces\n" +
                                            "  Hint: Indentation can only increase after ':', 'if:', 'for:', 'def:', etc.",
                                    getLine(), previous, indent
                            )
                    );
                }

                // Valid INDENT
                indents.push(indent);
                emit(createToken(FlaskLexer.INDENT, spaces));
                expectIndent = false;  // Reset after processing

            } else {
                // Decreased indentation - DEDENT(s)
                while (indents.size() > 1 && indents.peek() > indent) {
                    this.emit(createDedent());
                    indents.pop();
                }

                // Validate: must match existing level
                if (indents.peek() != indent) {
                    StringBuilder validIndents = new StringBuilder();
                    for (Integer i : indents) {
                        if (validIndents.length() > 0) validIndents.append(", ");
                        validIndents.append(i);
                    }

                    throw new RuntimeException(
                            String.format(
                                    "IndentationError: unindent does not match any outer indentation level\n" +
                                            "  Line %d: found %d spaces, expected one of [%s]",
                                    getLine(), indent, validIndents.toString()
                            )
                    );
                }

                expectIndent = false;  // Reset
            }
        }
    }

    @Override
    public void reset() {
        tokens = new LinkedList<>();
        indents = new ArrayDeque<>();
        indents.push(0);
        opened = 0;
        lastToken = null;
        expectIndent = false;  // ← NEW: Reset
        eofEmitted = false;
        super.reset();
    }
}