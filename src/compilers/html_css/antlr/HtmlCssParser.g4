parser grammar HtmlCssParser;

options {
    tokenVocab = HtmlCssLexer;
}

htmlDocument
    : ( htmlElement
      | htmlChardata
      | htmlComment
      | jinjaExpression
      | jinjaStatement
      | JINJA_COMMENT
      | DTD
      | XML
      )* EOF
    ;


htmlChardata
    : HTML_TEXT
    | SEA_WS
    ;


htmlComment
    : HTML_COMMENT
    | HTML_CONDITIONAL_COMMENT
    ;

htmlElement
    : TAG_OPEN TAG_NAME htmlAttribute* TAG_SLASH_CLOSE                           # selfClosingElement
    | TAG_OPEN TAG_NAME htmlAttribute* TAG_CLOSE htmlContent
      TAG_OPEN TAG_SLASH TAG_NAME TAG_CLOSE                                      # normalElement
    | style                                                                      # styleElement
    ;



htmlContent
    : ( htmlChardata
      | htmlElement
      | htmlComment
      | CDATA
      | jinjaExpression
      | jinjaStatement
      | JINJA_COMMENT
      )*
    ;


htmlAttribute
    : TAG_NAME (TAG_EQUALS ATTVALUE_VALUE)?
    ;

style
    : STYLE_OPEN (STYLE_BODY | STYLE_SHORT_BODY)
    ;

jinjaExpression
    : JINJA_EXPRESSION_START JINJA_EXPRESSION_CONTENT JINJA_EXPRESSION_END
    ;

jinjaStatement
    : JINJA_STATEMENT_START JINJA_STATEMENT_CONTENT JINJA_STATEMENT_END
    ;
