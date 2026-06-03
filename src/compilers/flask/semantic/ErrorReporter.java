package compilers.flask.semantic;

import java.util.ArrayList;
import java.util.List;

public class ErrorReporter {
    private final List<SemanticError> errors = new ArrayList<>();

    public void report(SemanticError error) {
        errors.add(error);
    }

    public List<SemanticError> getErrors() {
        return errors;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public void printErrors() {
        for (SemanticError error : errors) {
            System.err.println(error);
        }
    }
}
