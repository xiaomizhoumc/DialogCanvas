package dialogcanvas.engine.action;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Restricted arithmetic: decimal constants, numeric input references, + - * / and parentheses. */
public final class NumericExpression {
    private final String text;
    private final Map<String, JsonNode> inputs;
    private int position;
    private boolean syntaxOnly;

    private NumericExpression(String text, Map<String, JsonNode> inputs) {
        if (text == null || text.length() > 1024) throw new IllegalArgumentException("Invalid numeric expression");
        this.text = text;
        this.inputs = inputs;
    }

    public static double evaluate(String expression, Map<String, JsonNode> inputs) {
        var parser = new NumericExpression(expression, inputs);
        double result = parser.sum(0);
        parser.space();
        if (parser.position != expression.length() || !Double.isFinite(result)) {
            throw new IllegalArgumentException("Invalid or non-finite numeric expression");
        }
        return result;
    }

    /** Reuses the runtime grammar without substituting real player input or evaluating dynamic arithmetic. */
    public static void validate(String expression, java.util.Set<String> numericInputKeys) {
        var values = new java.util.HashMap<String, JsonNode>();
        numericInputKeys.forEach(key -> values.put(key, tools.jackson.databind.node.JsonNodeFactory.instance.numberNode(1)));
        var parser = new NumericExpression(expression, values);
        parser.syntaxOnly = true;
        parser.sum(0); parser.space();
        if (parser.position != expression.length()) throw new IllegalArgumentException("Invalid numeric expression");
        if (!expression.contains("$(")) evaluate(expression, Map.of());
    }

    private double sum(int depth) {
        double value = product(depth);
        for (;;) {
            if (take('+')) value += product(depth);
            else if (take('-')) value -= product(depth);
            else return syntaxOnly ? 1 : finite(value);
        }
    }

    private double product(int depth) {
        double value = atom(depth);
        for (;;) {
            if (take('*')) value *= atom(depth);
            else if (take('/')) {
                double denominator = atom(depth);
                if (!syntaxOnly && denominator == 0) throw new IllegalArgumentException("Division by zero");
                value /= denominator;
            } else return syntaxOnly ? 1 : finite(value);
        }
    }

    private double atom(int depth) {
        if (depth > 32) throw new IllegalArgumentException("Numeric expression is too deeply nested");
        if (take('+')) return atom(depth + 1);
        if (take('-')) return -atom(depth + 1);
        if (take('(')) {
            double result = sum(depth + 1);
            if (!take(')')) throw new IllegalArgumentException("Missing closing parenthesis");
            return result;
        }
        space();
        if (text.startsWith("$(", position)) {
            int end = text.indexOf(')', position + 2);
            if (end < 0) throw new IllegalArgumentException("Invalid input reference");
            String key = text.substring(position + 2, end);
            if (!key.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid input key");
            position = end + 1;
            JsonNode value = inputs.get(key);
            if (value == null || !value.isNumber()) throw new IllegalArgumentException("Numeric input is missing: " + key);
            return finite(value.doubleValue());
        }
        int start = position;
        while (position < text.length() && (Character.isDigit(text.charAt(position)) || text.charAt(position) == '.')) position++;
        if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
            position++;
            if (position < text.length() && (text.charAt(position) == '+' || text.charAt(position) == '-')) position++;
            while (position < text.length() && Character.isDigit(text.charAt(position))) position++;
        }
        if (start == position) throw new IllegalArgumentException("Expected decimal number");
        return finite(Double.parseDouble(text.substring(start, position)));
    }

    private boolean take(char token) {
        space();
        if (position < text.length() && text.charAt(position) == token) { position++; return true; }
        return false;
    }

    private void space() { while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++; }
    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite numeric value");
        return value;
    }
}
