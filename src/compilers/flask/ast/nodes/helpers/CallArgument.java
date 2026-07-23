package compilers.flask.ast.nodes.helpers;

import compilers.flask.ast.nodes.Expression;
import compilers.flask.ast.nodes.SourceSpan;

/** A call argument in its original source order. */
public final class CallArgument {

    public enum Kind {
        POSITIONAL,
        KEYWORD
    }

    private final Kind kind;
    private final String keywordName;
    private final Expression value;
    private final SourceSpan span;

    public CallArgument(
            Kind kind,
            String keywordName,
            Expression value,
            SourceSpan span) {
        if (kind == null) {
            throw new IllegalArgumentException("Call argument kind cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Call argument value cannot be null");
        }
        if (kind == Kind.POSITIONAL && keywordName != null) {
            throw new IllegalArgumentException(
                    "A positional argument cannot have a keyword name");
        }
        if (kind == Kind.KEYWORD
                && (keywordName == null || keywordName.isEmpty())) {
            throw new IllegalArgumentException(
                    "A keyword argument must have a non-empty name");
        }
        this.kind = kind;
        this.keywordName = keywordName;
        this.value = value;
        this.span = span == null ? SourceSpan.UNKNOWN : span;
    }

    public static CallArgument positional(Expression value, SourceSpan span) {
        return new CallArgument(Kind.POSITIONAL, null, value, span);
    }

    public static CallArgument positional(Expression value) {
        return positional(value, SourceSpan.UNKNOWN);
    }

    public static CallArgument keyword(
            String keywordName, Expression value, SourceSpan span) {
        return new CallArgument(Kind.KEYWORD, keywordName, value, span);
    }

    public static CallArgument keyword(String keywordName, Expression value) {
        return keyword(keywordName, value, SourceSpan.UNKNOWN);
    }

    public Kind getKind() {
        return kind;
    }

    public boolean isPositional() {
        return kind == Kind.POSITIONAL;
    }

    public boolean isKeyword() {
        return kind == Kind.KEYWORD;
    }

    public String getKeywordName() {
        return keywordName;
    }

    public Expression getValue() {
        return value;
    }

    public SourceSpan getSpan() {
        return span;
    }
}
