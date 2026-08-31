package compilers.server;

import compilers.flask.antlr_gen.FlaskLexer;
import compilers.flask.antlr_gen.FlaskParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Persists an editable top-level Python list without changing surrounding source. */
final class PythonSourceCollectionWriter {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private PythonSourceCollectionWriter() {
    }

    static void write(Path appPy, String collectionName, List<?> collection)
            throws IOException {
        if (!IDENTIFIER.matcher(collectionName).matches()) {
            throw new IOException("Invalid Python collection name: " + collectionName);
        }

        String source = Files.readString(appPy, StandardCharsets.UTF_8);
        AssignmentSpan assignment = findTopLevelAssignment(source, collectionName);
        String replacement = formatCollection(collection, lineSeparator(source));
        String updated = source.substring(0, assignment.start)
                + replacement
                + source.substring(assignment.endExclusive);

        // Refuse to touch app.py unless the complete updated source still parses.
        findTopLevelAssignment(updated, collectionName);
        writeAtomically(appPy, updated);
    }

    private static AssignmentSpan findTopLevelAssignment(
            String source, String collectionName) throws IOException {
        SyntaxErrors errors = new SyntaxErrors();
        FlaskLexer lexer = new FlaskLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(errors);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        FlaskParser parser = new FlaskParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errors);
        FlaskParser.ProgramContext program = parser.program();

        if (errors.count != 0) {
            throw new IOException("Cannot update app.py because it has Python syntax "
                    + "errors" + errors.firstMessage());
        }

        AssignmentSpan match = null;
        for (FlaskParser.StatementContext statement : program.statement()) {
            FlaskParser.Simple_statementContext simple = statement.simple_statement();
            if (simple == null || simple.small_stmt() == null
                    || simple.small_stmt().exprOrAssignment() == null) {
                continue;
            }
            FlaskParser.ExprOrAssignmentContext assignment =
                    simple.small_stmt().exprOrAssignment();
            if (assignment.ASSIGN() == null || assignment.expression().size() != 2
                    || !collectionName.equals(assignment.expression(0).getText())) {
                continue;
            }

            int start = assignment.expression(1).getStart().getStartIndex();
            int end = assignment.expression(1).getStop().getStopIndex() + 1;
            match = new AssignmentSpan(start, end);
        }

        if (match == null) {
            throw new IOException("No top-level assignment named '" + collectionName
                    + "' was found in app.py");
        }
        return match;
    }

    private static String formatCollection(List<?> collection, String newline)
            throws IOException {
        if (collection.isEmpty()) {
            return "[]";
        }
        StringBuilder output = new StringBuilder("[").append(newline);
        for (int index = 0; index < collection.size(); index++) {
            output.append("    ").append(pythonLiteral(collection.get(index)));
            if (index + 1 < collection.size()) {
                output.append(',');
            }
            output.append(newline);
        }
        return output.append(']').toString();
    }

    private static String pythonLiteral(Object value) throws IOException {
        if (value == null) {
            return "None";
        }
        if (value instanceof String) {
            return quote((String) value);
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "True" : "False";
        }
        if (value instanceof BigInteger || value instanceof Byte
                || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new IOException("Cannot persist a non-finite Python number");
            }
            return value.toString();
        }
        if (value instanceof List) {
            StringBuilder output = new StringBuilder("[");
            List<?> list = (List<?>) value;
            for (int index = 0; index < list.size(); index++) {
                if (index != 0) {
                    output.append(", ");
                }
                output.append(pythonLiteral(list.get(index)));
            }
            return output.append(']').toString();
        }
        if (value instanceof Map) {
            StringBuilder output = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    output.append(", ");
                }
                output.append(pythonLiteral(entry.getKey()))
                        .append(": ")
                        .append(pythonLiteral(entry.getValue()));
                first = false;
            }
            return output.append('}').toString();
        }
        throw new IOException("Cannot persist Python value of type "
                + value.getClass().getSimpleName());
    }

    private static String quote(String value) {
        StringBuilder output = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\':
                    output.append("\\\\");
                    break;
                case '"':
                    output.append("\\\"");
                    break;
                case '\n':
                    output.append("\\n");
                    break;
                case '\r':
                    output.append("\\r");
                    break;
                case '\t':
                    output.append("\\t");
                    break;
                case '\b':
                    output.append("\\b");
                    break;
                case '\f':
                    output.append("\\f");
                    break;
                default:
                    if (character < 0x20 || character == 0x7f) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
            }
        }
        return output.append('"').toString();
    }

    private static String lineSeparator(String source) {
        return source.contains("\r\n") ? "\r\n" : "\n";
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("app.py has no parent directory: " + target);
        }
        Path temporary = Files.createTempFile(parent, ".app-py-update-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static final class AssignmentSpan {
        final int start;
        final int endExclusive;

        AssignmentSpan(int start, int endExclusive) {
            this.start = start;
            this.endExclusive = endExclusive;
        }
    }

    private static final class SyntaxErrors extends BaseErrorListener {
        int count;
        String first;

        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String message,
                RecognitionException failure) {
            count++;
            if (first == null) {
                first = " at " + line + ":" + (charPositionInLine + 1)
                        + " (" + message + ")";
            }
        }

        String firstMessage() {
            return first == null ? "" : first;
        }
    }
}
