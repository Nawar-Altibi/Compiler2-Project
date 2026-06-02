package compilers.html_css.SymbolTable;

import compilers.html_css.ast.*;

public class SymbolTableBuilder implements HtmlVisitor, CssVisitor {
    private SymbolTable symbolTable;

    public SymbolTableBuilder() {
        this.symbolTable = new SymbolTable();
    }

    public SymbolTableBuilder(SymbolTable symbolTable) {
        this.symbolTable = symbolTable;
    }

    public SymbolTable build(HtmlNode root) {
        if (root != null) root.accept(this);
        return symbolTable;
    }
    
    public SymbolTable build(CssNode root) {
        if (root != null) root.accept(this);
        return symbolTable;
    }

    public SymbolTable getSymbolTable() {
        return symbolTable;
    }

    // --- HtmlVisitor Methods ---

    @Override
    public void visit(HtmlDocumentNode node) {
        for (HtmlNode child : node.getChildren()) {
            if(child != null) child.accept(this);
        }
    }

    @Override
    public void visit(ElementNode node) {
        String tagName = node.getTagName();
        if (tagName != null && !tagName.isEmpty()) {
            Symbol symbol = new Symbol(tagName, SymbolType.HTML_TAG,
                    symbolTable.getCurrentScopeName(),
                    node.getLine());
            symbolTable.define(symbol);
        }

        // Always assume tags can have scope in this structure (since we don't have isSelfClosing directly here, 
        // but children list might be empty)
        String elementScope = "element:" + (tagName != null ? tagName : "unknown");
        symbolTable.enterScope(elementScope);
        
        for (AttributeNode attr : node.getAttributes()) {
            if(attr != null) attr.accept(this);
        }

        for (HtmlNode child : node.getChildren()) {
            if(child != null) child.accept(this);
        }

        symbolTable.exitScope();
    }

    @Override
    public void visit(AttributeNode node) {
        String attrName = node.getName();
        if (attrName != null && !attrName.isEmpty()) {
            Symbol symbol = new Symbol(attrName, SymbolType.HTML_ATTRIBUTE,
                    symbolTable.getCurrentScopeName(),
                    node.getLine());
            symbolTable.define(symbol);
        }
        
        if (node.getValue() != null) {
            node.getValue().accept(this);
        }
    }

    @Override
    public void visit(TextNode node) {
        // Nothing to add to symbol table for plain text
    }

    @Override
    public void visit(JinjaExpressionNode node) {
        String expression = node.getExpression();
        if (expression != null && !expression.trim().isEmpty()) {
            Symbol symbol = new Symbol(expression.trim(), SymbolType.JINJA_EXPRESSION,
                    symbolTable.getCurrentScopeName(),
                    node.getLine());
            symbolTable.define(symbol);

            String[] tokens = expression.trim().split("[\\s.()\\[\\]|]+");
            for (String token : tokens) {
                token = token.trim();
                if (!token.isEmpty() && isPotentialVariable(token)) {
                    Symbol varSymbol = new Symbol(token, SymbolType.JINJA_VARIABLE,
                            symbolTable.getCurrentScopeName(),
                            node.getLine());
                    symbolTable.define(varSymbol);
                }
            }
        }
    }

    @Override
    public void visit(JinjaStatementNode node) {
        String statement = node.getStatement();
        if (statement != null && !statement.trim().isEmpty()) {
            String blockName = extractBlockName(statement);
            Symbol symbol = new Symbol(blockName, SymbolType.JINJA_BLOCK,
                    symbolTable.getCurrentScopeName(),
                    node.getLine());
            symbolTable.define(symbol);
            
            // Note: AST structure doesn't easily show what's inside the block 
            // since Jinja blocks are treated as single nodes here. 
            // We just add it to the current scope.
        }
    }

    @Override
    public void visit(LiteralAttributeValueNode node) {
        // Nothing to do
    }

    @Override
    public void visit(JinjaAttributeValueNode node) {
        String expression = node.getExpression();
        if (expression != null && !expression.trim().isEmpty()) {
            Symbol symbol = new Symbol(expression.trim(), SymbolType.JINJA_EXPRESSION,
                    symbolTable.getCurrentScopeName(),
                    node.getLine());
            symbolTable.define(symbol);

            String[] tokens = expression.trim().split("[\\s.()\\[\\]|]+");
            for (String token : tokens) {
                token = token.trim();
                if (!token.isEmpty() && isPotentialVariable(token)) {
                    Symbol varSymbol = new Symbol(token, SymbolType.JINJA_VARIABLE,
                            symbolTable.getCurrentScopeName(),
                            node.getLine());
                    symbolTable.define(varSymbol);
                }
            }
        }
    }

    @Override
    public void visit(StyleNode node) {
        symbolTable.enterScope("style");
        if (node.getCssAst() != null) {
            node.getCssAst().accept(this);
        }
        symbolTable.exitScope();
    }
    
    // --- CssVisitor Methods ---

    @Override
    public void visit(StylesheetNode n) {
        for (CssNode statement : n.statements) {
            if(statement != null) statement.accept(this);
        }
    }

    @Override
    public void visit(RulesetNode n) {
        String selectorName = getFirstSelectorName(n.selectors);
        
        symbolTable.enterScope("rule:" + (selectorName != null ? selectorName : "unknown"));
        
        if (n.selectors != null) n.selectors.accept(this);
        if (n.block != null) n.block.accept(this);
        
        symbolTable.exitScope();
    }
    
    private String getFirstSelectorName(SelectorGroupNode group) {
        if(group == null || group.selectors.isEmpty()) return "unknown";
        SelectorNode first = group.selectors.get(0);
        if(first.parts.isEmpty()) return "unknown";
        Object firstPart = first.parts.get(0);
        if(firstPart instanceof SimpleSelectorSequenceNode) {
            SimpleSelectorSequenceNode seq = (SimpleSelectorSequenceNode) firstPart;
            if(!seq.items.isEmpty()) {
                return seq.items.get(0);
            }
        }
        return "unknown";
    }

    @Override
    public void visit(SelectorGroupNode n) {
        for (SelectorNode s : n.selectors) {
            if(s != null) s.accept(this);
        }
    }

    @Override
    public void visit(SelectorNode n) {
        StringBuilder selectorText = new StringBuilder();
        for (Object p : n.parts) {
            if (p instanceof SimpleSelectorSequenceNode) {
                for (String it : ((SimpleSelectorSequenceNode) p).items) {
                    if (it != null) selectorText.append(it);
                }
            } else if (p instanceof Combinator) {
                if(p == Combinator.DESCENDANT) selectorText.append(" ");
                else if(p == Combinator.CHILD) selectorText.append(" > ");
            }
        }
        
        String sel = selectorText.toString().trim();
        if (!sel.isEmpty()) {
            Symbol symbol = new Symbol(sel, SymbolType.CSS_SELECTOR,
                    symbolTable.getCurrentScopeName(),
                    n.line);
            symbolTable.define(symbol);
        }
    }

    @Override
    public void visit(SimpleSelectorSequenceNode n) {
        // Handled in SelectorNode
    }

    @Override
    public void visit(DeclarationBlockNode n) {
        for (DeclarationNode d : n.declarations) {
            if (d != null) d.accept(this);
        }
    }

    @Override
    public void visit(DeclarationNode n) {
        if (n.property != null && !n.property.isEmpty()) {
            Symbol symbol = new Symbol(n.property, SymbolType.CSS_PROPERTY,
                    symbolTable.getCurrentScopeName(),
                    n.line);
            symbolTable.define(symbol);
        }
        if (n.value != null) {
            n.value.accept(this);
        }
    }

    @Override
    public void visit(ValueNode n) {
        for(ExprNode e : n.groups) {
            if(e != null) e.accept(this);
        }
    }

    @Override
    public void visit(ExprNode n) {
        for(TermNode t : n.terms) {
            if(t != null) t.accept(this);
        }
    }

    @Override
    public void visit(TermNode n) {
        if ("FUNC".equals(n.kind) && n.funcArgs != null) {
            n.funcArgs.accept(this);
        }
    }

    // --- Helper Methods ---

    private String extractBlockName(String blockContent) {
        if (blockContent == null || blockContent.trim().isEmpty()) return "block";
        String trimmed = blockContent.trim();
        int spaceIndex = trimmed.indexOf(' ');
        return spaceIndex > 0 ? trimmed.substring(0, spaceIndex) : trimmed;
    }

    private boolean isPotentialVariable(String token) {
        if (token == null || token.isEmpty()) return false;

        String[] keywords = {"and","or","not","in","is","if","else","for","with"};
        for (String keyword : keywords) if (token.equalsIgnoreCase(keyword)) return false;

        if (token.matches("^[+\\-*/%=<>!]+$")) return false;
        if (token.matches("^\\d+(\\.\\d+)?$")) return false;
        if ((token.startsWith("\"") && token.endsWith("\"")) ||
                (token.startsWith("'") && token.endsWith("'"))) return false;

        return token.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }
}