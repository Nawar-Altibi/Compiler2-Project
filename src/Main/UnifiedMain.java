package Main;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;

import java.io.IOException;
import java.nio.file.Paths;

public class UnifiedMain {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Please provide a test file path.");
            // Default to a test if none provided for convenience
            args = new String[]{"Tests/test_flask.py"};
        }

        String filePath = args[0];
        String fileName = Paths.get(filePath).getFileName().toString().toLowerCase();
        
        try {
            if (fileName.endsWith(".py")) {
                System.out.println("Dispatcher: Detected Python/Flask file.");
                runFlaskCompiler(filePath);
            } else if (fileName.endsWith(".html")) {
                System.out.println("Dispatcher: Detected HTML/CSS/Jinja file.");
                runHtmlCssCompiler(filePath, new java.util.HashMap<>());
            }    else {
                System.out.println("Unknown file type: " + fileName);
                System.out.println("Supported extensions: .py, .html, .txt, .ts");
            }
        } catch (Exception e) {
            System.err.println("Error during compilation:");
            e.printStackTrace();
        }
    }

    private static void runFlaskCompiler(String path) throws Exception {
        CharStream cs = CharStreams.fromFileName(path);
        compilers.flask.antlr_gen.FlaskLexer lexer = new compilers.flask.antlr_gen.FlaskLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        compilers.flask.antlr_gen.FlaskParser parser = new compilers.flask.antlr_gen.FlaskParser(tokens);

        ParseTree tree = parser.program();

        compilers.flask.ast.builder.ASTBuilder builder = new compilers.flask.ast.builder.ASTBuilder();
        compilers.flask.ast.nodes.statements.ProgramNode ast = (compilers.flask.ast.nodes.statements.ProgramNode) builder.visit(tree);

        System.out.println("====== Flask AST ======");
        compilers.flask.Visitor.ASTPrinter printer = new compilers.flask.Visitor.ASTPrinter();
        printer.visitProgram(ast);
        System.out.println(printer.getOutput());

        System.out.println("\n====== Flask SYMBOL TABLE ======");
        compilers.flask.SymbolTable.SymbolTableBuilder stBuilder = new compilers.flask.SymbolTable.SymbolTableBuilder();
        ast.accept(stBuilder);
        compilers.flask.SymbolTable.SymbolTable table = stBuilder.getSymbolTable();
        table.print();
    }

    private static void runHtmlCssCompiler(String path) throws Exception {
        CharStream cs = CharStreams.fromFileName(path);
        compilers.html_css.antlr.HtmlCssLexer lexer = new compilers.html_css.antlr.HtmlCssLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        compilers.html_css.antlr.HtmlCssParser parser = new compilers.html_css.antlr.HtmlCssParser(tokens);

        ParseTree tree = parser.htmlDocument();

        compilers.html_css.Visitor.HtmlAstBuilder builder = new compilers.html_css.Visitor.HtmlAstBuilder();
        compilers.html_css.ast.HtmlNode ast = builder.visit(tree);

        System.out.println("====== HTML/CSS AST ======");
        compilers.html_css.Visitor.AstPrintVisitor printer = new compilers.html_css.Visitor.AstPrintVisitor();
        ast.accept(printer);

        System.out.println("\n====== HTML/CSS SYMBOL TABLE ======");
        compilers.html_css.SymbolTable.SymbolTableBuilder stBuilder = new compilers.html_css.SymbolTable.SymbolTableBuilder();
        compilers.html_css.SymbolTable.SymbolTable table = stBuilder.build(ast);
        System.out.println(table.printSymbolTable());
    }

}
  CommonTokenStream tokens = new CommonTokenStream(lexer);
        compilers.html_css.antlr.HtmlCssParser parser = new compilers.html_css.antlr.HtmlCssParser(tokens);

        ParseTree tree = parser.htmlDocument();

        compilers.html_css.Visitor.HtmlAstBuilder builder = new compilers.html_css.Visitor.HtmlAstBuilder();
        compilers.html_css.ast.HtmlNode ast = builder.visit(tree);

        System.out.println("====== HTML/CSS SYMBOL TABLE BUILDING ======");
        compilers.html_css.SymbolTable.SymbolTableBuilder stBuilder = new compilers.html_css.SymbolTable.SymbolTableBuilder();
        compilers.html_css.SymbolTable.SymbolTable table = stBuilder.build(ast);

        System.out.println("====== HTML/CSS SEMANTIC ANALYSIS ======");
        compilers.flask.semantic.ErrorReporter errorReporter = new compilers.flask.semantic.ErrorReporter();
        compilers.html_css.semantic.HtmlSemanticAnalyzer semanticAnalyzer = new compilers.html_css.semantic.HtmlSemanticAnalyzer(errorReporter, path, templateContexts);
        semanticAnalyzer.analyze(ast);

        if (errorReporter.hasErrors()) {
            errorReporter.printErrors();
        } else {
            System.out.println("No semantic errors found.");
        }

        System.out.println("\n====== HTML/CSS AST ======");
        compilers.html_css.Visitor.AstPrintVisitor printer = new compilers.html_css.Visitor.AstPrintVisitor();
        ast.accept(printer);

        System.out.println("\n====== HTML/CSS SYMBOL TABLE ======");
        System.out.println(table.printSymbolTable());
    }

}
