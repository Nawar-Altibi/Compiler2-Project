package compilers.html_css.semantic;

import compilers.diagnostics.DiagnosticReporter;
import compilers.html_css.ast.HtmlNode;
import java.util.Set;
import java.util.Map;

public class HtmlSemanticAnalyzer {
    private final DiagnosticReporter reporter;
    private final String sourceFile;
    private final Map<String, Set<String>> templateContexts;

    public HtmlSemanticAnalyzer(DiagnosticReporter reporter, String sourceFile, Map<String, Set<String>> templateContexts) {
        this.reporter = reporter;
        this.sourceFile = sourceFile;
        this.templateContexts = templateContexts;
    }

    public void analyze(HtmlNode root) {
        String fileName = new java.io.File(sourceFile).getName();
        Set<String> providedVars = templateContexts.get(fileName);

        new JinjaSemanticAnalyzer(reporter, sourceFile, providedVars).analyze(root);
    }
}
