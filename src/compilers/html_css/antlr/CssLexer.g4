lexer grammar CssLexer;

WS : [ \t\r\n\f]+ ;

LBRACE : '{' ;
RBRACE : '}' ;
COLON  : ':' ;
SEMI   : ';' ;
COMMA  : ',' ;
RIGHT_BRACKET : ')' ;
LEFT_BRACKET : '(' ;

GT     : '>' ;

DOT    : '.' ;
HASH   : '#' ;

HexColor : '#' [0-9a-fA-F]+ ;

NUMBER
  : [0-9]+ ('.' [0-9]+)?
  ;

UNIT
  : 'px' | 'em' | 'rem' | '%' | 'vh' | 'vw'
  ;

STRING
  : '"' (~["\r\n])* '"'
  | '\'' (~['\r\n])* '\''
  ;

fragment IDENT_START : [a-zA-Z_-];
fragment IDENT_CHAR  : [a-zA-Z0-9_-];

FUNCTION : IDENT_START IDENT_CHAR* '(' ;
IDENT    : IDENT_START IDENT_CHAR* ;