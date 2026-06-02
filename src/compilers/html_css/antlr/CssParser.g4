parser grammar CssParser;

options { tokenVocab=CssLexer; }

stylesheet
  : ruleset* EOF
  ;

ruleset
  : selectorGroup WS* LBRACE WS* declaration* WS* RBRACE WS*
  ;


selectorGroup
  : selector (COMMA selector)*
  ;

selector
  : simpleSelector ( (GT WS* | WS+) simpleSelector )*
  ;


simpleSelector
  : typeSelector
  | classSelector
  | idSelector
  ;

typeSelector
  : IDENT
  ;

classSelector
  : DOT IDENT
  ;

idSelector
  : HASH IDENT
  ;

declaration
  : IDENT WS* COLON WS* value (WS* COMMA WS* value)* WS* SEMI WS*
  ;

value
  : valuePart (WS+ valuePart)*
  ;


valuePart
  : NUMBER UNIT?
  | IDENT
  | STRING
  | HexColor
  | functionCall
  ;

functionCall
  : FUNCTION WS* value (WS* COMMA WS* value)* WS* RIGHT_BRACKET
  ;

