package Main;

import compilers.diagnostics.DiagnosticReporter;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class UnifiedMain {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Please provide a test file path.");
            args = new String[]{"Tests/html_css/errors/undefined_jinja.html"};
        }

        String filePath = args[0];
        String fileName = Paths.get(filePath).getFileName().toString().toLowerCase();

        try {
            if (fileName.endsWith(".py")) {
                System.out.println("Dispatcher: Detected Python/Flask file.");
                runFlaskCompiler(filePath);
            } else if (fileName.endsWith(".html")) {
                System.out.println("Dispatcher: Detected HTML/CSS/Jinja file.");
                runHtmlCssCompiler(filePath, new HashMap<>());
            } else {
                System.out.println("Unknown file type: " + fileName);
                System.out.println("Supported extensions: .py, .html, .txt, .ts");
            }
        } catch (Exception e) {
            System.err.println("Error during compilation:");
            e.printStackTrace();
        }
    }

    private static void runFlaskCompiler(String path) throws Exception {
        DiagnosticReporter reporter = new DiagnosticReporter();

        CharStream cs = CharStreams.fromFileName(path);
        compilers.flask.antlr_gen.FlaskLexer lexer = new compilers.flask.antlr_gen.FlaskLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        compilers.flask.antlr_gen.FlaskParser parser = new compilers.flask.antlr_gen.FlaskParser(tokens);

        ParseTree tree = parser.program();

        compilers.flask.ast.builder.ASTBuilder builder = new compilers.flask.ast.builder.ASTBuilder();
        compilers.flask.ast.nodes.statements.ProgramNode ast = (compilers.flask.ast.nodes.statements.ProgramNode) builder.visit(tree);

        System.out.println("====== Flask SYMBOL TABLE BUILDING ======");
        compilers.flask.SymbolTable.SymbolTableBuilder stBuilder =
                new compilers.flask.SymbolTable.SymbolTableBuilder(reporter, path);
        ast.accept(stBuilder);
        compilers.flask.SymbolTable.SymbolTable table = stBuilder.getSymbolTable();

        System.out.println("====== Flask SEMANTIC ANALYSIS ======");
        compilers.flask.semantic.SemanticAnalyzer semanticAnalyzer =
                new compilers.flask.semantic.SemanticAnalyzer(table, path, reporter);
        semanticAnalyzer.analyze(ast);

        printDiagnostics(reporter);

        System.out.println("\n====== Flask AST ======");
        compilers.flask.Visitor.ASTPrinter printer = new compilers.flask.Visitor.ASTPrinter();
        printer.visitProgram(ast);
        System.out.println(printer.getOutput());

        System.out.println("\n====== Flask SYMBOL TABLE ======");
        table.print();

        compilers.flask.semantic.TemplateContextCollector collector = new compilers.flask.semantic.TemplateContextCollector();
        ast.accept(collector);
        Map<String, Set<String>> templateContexts = collector.getTemplateContexts();

        if (!templateContexts.isEmpty()) {
            System.out.println("\n====== CROSS-FILE SEMANTIC ANALYSIS (Templates) ======");
            String parentDir = Paths.get(path).getParent().toString();
            for (String templateName : templateContexts.keySet()) {
                java.io.File templateFile = new java.io.File(parentDir, templateName);
                if (templateFile.exists()) {
                    System.out.println("Analyzing referenced template: " + templateName);
                    runHtmlCssCompiler(templateFile.getAbsolutePath(), templateContexts);
                } else {
                    System.out.println("Template not found for cross-analysis: " + templateName);
                }
            }
        }
    }

    private static void runHtmlCssCompiler(String path, Map<String, Set<String>> templateContexts) throws Exception {
        DiagnosticReporter reporter = new DiagnosticReporter();

        CharStream cs = CharStreams.fromFileName(path);
        compilers.html_css.antlr.HtmlCssLexer lexer = new compilers.html_css.antlr.HtmlCssLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        compilers.html_css.antlr.HtmlCssParser parser = new compilers.html_css.antlr.HtmlCssParser(tokens);

        ParseTree tree = parser.htmlDocument();

        compilers.html_css.Visitor.HtmlAstBuilder builder = new compilers.html_css.Visitor.HtmlAstBuilder();
        compilers.html_css.ast.HtmlNode ast = builder.visit(tree);

        System.out.println("====== HTML/CSS SYMBOL TABLE BUILDING ======");
        compilers.html_css.SymbolTable.SymbolTableBuilder stBuilder = new compilers.html_css.SymbolTable.SymbolTableBuilder();
        compilers.html_css.SymbolTable.SymbolTable table = stBuilder.build(ast);

        System.out.println("====== HTML/CSS SEMANTIC ANALYSIS ======");
        compilers.html_css.semantic.HtmlSemanticAnalyzer semanticAnalyzer =
                new compilers.html_css.semantic.HtmlSemanticAnalyzer(reporter, path, templateContexts);
        semanticAnalyzer.analyze(ast);

        printDiagnostics(reporter);

        System.out.println("\n====== HTML/CSS AST ======");
        compilers.html_css.Visitor.AstPrintVisitor printer = new compilers.html_css.Visitor.AstPrintVisitor();
        ast.accept(printer);

        System.out.println("\n====== HTML/CSS SYMBOL TABLE ======");
        System.out.println(table.printSymbolTable());
    }

    private static void printDiagnostics(DiagnosticReporter reporter) {
        if (reporter.hasDiagnostics()) {
            reporter.printAll();
        } else {
            System.out.println("No diagnostics found.");
        }
    }
}
