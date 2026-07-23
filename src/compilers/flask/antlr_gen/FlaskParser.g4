parser grammar FlaskParser;

options {
    tokenVocab = FlaskLexer;
}

program
    : (NEWLINE | statement)* EOF
    ;

statement
    : simple_statement
    | compound_statement
    ;

simple_statement
    : small_stmt NEWLINE
    ;

// ✅ FIX: استبدلنا assignmentStatement و expression_statement بـ exprOrAssignment
//    لحل الـ ambiguity — كلاهما كان يبدأ بـ IDENTIFIER
small_stmt
    : importStatement
    | exprOrAssignment
    | returnStatement
    | passStatement
    | breakStatement
    | continueStatement
    | delStatement
    | assertStatement
    | globalStatement
    | raiseStatement
    ;

// ✅ FIX: قاعدة موحدة — ابدأ بـ expression دائماً، وبعدين شوف إذا في = أو لأ
//    foo()          → expression فقط
//    x = 5          → simple assignment
//    x += 1         → augmented assignment
//    app.config['KEY'] = 'val' → subscript assignment
exprOrAssignment
    : expression ASSIGN expression
    | expression augmentedAssignmentOp expression
    | expression
    ;

returnStatement
    : RETURN expression_list?
    ;

passStatement
    : PASS
    ;

breakStatement
    : BREAK
    ;

continueStatement
    : CONTINUE
    ;

delStatement
    : DEL targetList
    ;

targetList
    : target (COMMA target)*
    ;

assertStatement
    : ASSERT expression (COMMA expression)?
    ;

globalStatement
    : GLOBAL IDENTIFIER (COMMA IDENTIFIER)*
    ;

raiseStatement
    : RAISE (expression (FROM expression)?)?
    ;

compound_statement
    : decoratedDef
    | functionDef
    | ifStatement
    | forStatement
    | whileStatement
    | withStatement
    | tryStatement
    | classStatement
    ;

ifStatement
    : IF expression COLON suite
      (ELIF expression COLON suite)*
      (ELSE COLON suite)?
    ;

forStatement
    : FOR targetList IN expression COLON suite (ELSE COLON suite)?
    ;

whileStatement
    : WHILE expression COLON suite (ELSE COLON suite)?
    ;

withStatement
    : WITH withItem (COMMA withItem)* COLON suite
    ;

withItem
    : expression (AS targetList)?
    ;

tryStatement
    : TRY COLON suite
      (
        exceptClause+ (ELSE COLON suite)? (FINALLY COLON suite)?
      | FINALLY COLON suite
      )
    ;

exceptClause
    : EXCEPT expression (AS IDENTIFIER)? COLON suite
    | EXCEPT COLON suite
    ;

classStatement
    : CLASS IDENTIFIER (LPAREN expression_list? RPAREN)? COLON suite
    ;

decoratedDef
    : decorator+ (functionDef | classStatement)
    ;

decorator
    : AT expression NEWLINE
    ;

functionDef
    : DEF IDENTIFIER LPAREN parameters? RPAREN (ARROW expression)? COLON suite
    ;

parameters
    : parameter (COMMA parameter)* COMMA?
    ;

parameter
    : IDENTIFIER (COLON expression)? (ASSIGN expression)?
    ;

suite
    : simple_statement
    | NEWLINE INDENT statement+ DEDENT
    ;

block
    : suite
    ;

augmentedAssignmentOp
    : ADD_ASSIGN | SUB_ASSIGN | MUL_ASSIGN | DIV_ASSIGN
    ;

target
    : IDENTIFIER (target_trailer)*
    ;

target_trailer
    : DOT IDENTIFIER
    | LBRACK expression RBRACK
    ;

importStatement
    : importNameStatement
    | importFromStatement
    ;

importNameStatement
    : IMPORT dottedName (AS IDENTIFIER)?
    ;

importFromStatement
    : FROM dottedName IMPORT (importList | MUL)
    ;

importList
    : importItem (COMMA importItem)* COMMA?
    ;

importItem
    : IDENTIFIER (AS IDENTIFIER)?
    ;

dottedName
    : IDENTIFIER (DOT IDENTIFIER)*
    ;

expression
    : or_boolean_expression
    ;

or_boolean_expression
    : and_boolean_expression (OR and_boolean_expression)*
    ;

and_boolean_expression
    : not_boolean_expression (AND not_boolean_expression)*
    ;

not_boolean_expression
    : NOT not_boolean_expression
    | comparison_expression
    ;

comparison_expression
    : additive_expression (comp_op additive_expression)*
    ;

comp_op
    : EQ | NEQ | LT | GT | LTE | GTE | IN | IS
    ;

additive_expression
    : multiplicative_expression ((ADD | SUB) multiplicative_expression)*
    ;

multiplicative_expression
    : unary_expression ((MUL | DIV | FLOOR_DIV | MOD) unary_expression)*
    ;

unary_expression
    : (ADD | SUB) unary_expression
    | power_expression
    ;

power_expression
    : atom_expression (POWER power_expression)?
    ;

atom_expression
    : atom (trailer)*
    ;

atom
    : IDENTIFIER
    | NUMBER
    | STRING
    | TRUE
    | FALSE
    | NONE
    | parenthesized
    | LBRACK expression_list? RBRACK
    | LBRACE NEWLINE? dict_or_set? NEWLINE? RBRACE
    ;

// Parentheses are grouping only when no comma is present.  Empty and
// comma-bearing forms are tuple displays, including the singleton `(x,)`.
parenthesized
    : LPAREN RPAREN
    | LPAREN expression (COMMA expression)* COMMA? RPAREN
    ;

trailer
    : DOT IDENTIFIER
    | LPAREN arglist? RPAREN
    | LBRACK expression RBRACK
    ;

dict_or_set
    : dict_items
    | expression_list
    ;

dict_items
    : dict_item (COMMA NEWLINE? dict_item)* COMMA?
    ;

dict_item
    : expression COLON expression
    ;

expression_list
    : expression (COMMA expression)* COMMA?
    ;

arglist
    : argument (COMMA argument)* COMMA?
    ;

argument
    : expression
    | IDENTIFIER ASSIGN expression
    ;
