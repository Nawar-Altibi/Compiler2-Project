package compilers.html_css.semantic;

import compilers.flask.semantic.ErrorReporter;
import compilers.html_css.ast.HtmlNode;
import java.util.Set;
import java.util.Map;

public class HtmlSemanticAnalyzer {
    private final ErrorReporter errorReporter;
    private final String sourceFile;
    private final Map<String, Set<String>> templateContexts; // templateName -> set of provided variables

    public HtmlSemanticAnalyzer(ErrorReporter errorReporter, String sourceFile, Map<String, Set<String>> templateContexts) {
        this.errorReporter = errorReporter;
        this.sourceFile = sourceFile;
        this.templateContexts = templateContexts;
    }

    public void analyze(HtmlNode root) {
        String fileName = new java.io.File(sourceFile).getName();
        Set<String> providedVars = templateContexts.get(fileName);
        
        new JinjaSemanticAnalyzer(errorReporter, sourceFile, providedVars).analyze(root);
    }
}
