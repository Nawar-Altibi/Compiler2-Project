lexer grammar FlaskLexer;

// Tokens for indentation (must be declared)
tokens { INDENT, DEDENT }

// Options: We put the complex Java logic in a separate base class for cleanliness.
options { superClass=FlaskLexerBase; }

// --- Keywords (مبسطة لـ Flask basics فقط) ---
AND      : 'and' ;
AT       : '@' ;
AS       : 'as' ;
ASSERT   : 'assert';
BREAK    : 'break' ;
CLASS    : 'class' ;
CONTINUE : 'continue' ;
DEF      : 'def' ;
DEL      : 'del' ;
IF       : 'if' ;
ELIF     : 'elif' ;
ELSE     : 'else' ;
EXCEPT   : 'except' ;
FALSE    : 'False' ;
FINALLY  : 'finally' ;
FOR      : 'for' ;
FROM     : 'from' ;
GLOBAL   : 'global' ;
IMPORT   : 'import' ;
IN       : 'in' ;
IS       : 'is' ;
NONE     : 'None' ;
NOT      : 'not' ;
OR       : 'or' ;
PASS     : 'pass' ;
RAISE    : 'raise' ;
RETURN   : 'return' ;
TRY      : 'try' ;
TRUE     : 'True' ;
WHILE    : 'while' ;
WITH     : 'with' ;  // لـ with app.app_context() في Flask

// --- Operators (نفس، لأنها أساسية) ---
GTE         : '>=' ;
LTE         : '<=' ;
NEQ         : '!=' ;
EQ          : '==' ;
POWER       : '**' ;
FLOOR_DIV   : '//' ;
ADD_ASSIGN  : '+=' ;
SUB_ASSIGN  : '-=' ;
MUL_ASSIGN  : '*=' ;
DIV_ASSIGN  : '/=' ;
ELLIPSIS    : '...' ;
ARROW       : '->' ;
ASSIGN      : '=' ;
ADD         : '+' ;
SUB         : '-' ;
MUL         : '*' ;
DIV         : '/' ;
MOD         : '%' ;
GT          : '>' ;
LT          : '<' ;
DOT         : '.' ;

// --- Delimiters (مع brace tracking) ---
LPAREN      : '(' {openBrace();} ;
RPAREN      : ')' {closeBrace();} ;
LBRACK      : '[' {openBrace();} ;
RBRACK      : ']' {closeBrace();} ;
LBRACE      : '{' {openBrace();} ;
RBRACE      : '}' {closeBrace();} ;
COLON       : ':' ;
COMMA       : ',' ;
SEMICOLON   : ';' ;

STRING
    : ('f'|'F'|'r'|'R')? ( SHORT_STRING | LONG_STRING )
    ;

fragment SHORT_STRING
    : '\'' ( STRING_ESCAPE_SEQ | ~[\\\r\n'] )* '\''
    | '"' ( STRING_ESCAPE_SEQ | ~[\\\r\n"] )* '"'
    ;

fragment LONG_STRING
    : '"""' LONG_STRING_ITEM*? '"""'
    | '\'\'\'' LONG_STRING_ITEM*? '\'\'\''
    ;

fragment LONG_STRING_ITEM
    : ~'\\'
    | STRING_ESCAPE_SEQ
    ;

fragment STRING_ESCAPE_SEQ
    : '\\' .
    ;

NUMBER
    : INT ('.' [0-9]+)? EXP?
    ;

fragment INT
    : [0-9]+
    ;

fragment EXP
    : [eE] [+-]? INT
    ;

// --- Identifiers ---
IDENTIFIER : [a-zA-Z_] [a-zA-Z0-9_]* ;

// --- Whitespace, Comments, Newline (نفس السابق، ضروري) ---
NEWLINE
 : ( '\r'? '\n' | '\r' | '\f' ) SPACES?
   {
     onNewLine();
   }
 ;

SKIP_
 : ( SPACES | COMMENT | LINE_JOINING ) -> skip
 ;


fragment SPACES
 : [ \t]+
 ;

fragment COMMENT
 : '#' ~[\r\n\f]*
 ;

fragment LINE_JOINING
 : '\\' SPACES? ( '\r'? '\n' | '\r' | '\f')
 ;

UNKNOWN_CHAR : . ;
