package dialogcanvas.engine.action;

import dialogcanvas.model.TextSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Numeric message substitutions use the same validated input snapshot and grammar as money actions. */
public final class ActionMessageTemplate {
    private static final Pattern PAPI = Pattern.compile("%[^%\\s]+%");
    private ActionMessageTemplate() { }

    public static TextSpec resolve(TextSpec original, Map<String, JsonNode> inputs,
                                   Function<String, String> placeholder) {
        TextSpec copy = new TextSpec();
        copy.setFormat(original.getFormat());
        copy.setValue(map(original, source -> {
            String numeric = expand(source, expression -> {
                double value = NumericExpression.evaluate(inputs.containsKey(expression) ? "$(" + expression + ")" : expression, inputs);
                return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
            });
            var matcher = PAPI.matcher(numeric);
            var out = new StringBuilder();
            while (matcher.find()) matcher.appendReplacement(out,
                    java.util.regex.Matcher.quoteReplacement(placeholder.apply(matcher.group())));
            matcher.appendTail(out);
            return out.toString();
        }));
        return copy;
    }

    public static void validate(TextSpec original, Set<String> numericKeys) {
        map(original, source -> expand(source, expression -> {
            NumericExpression.validate(numericKeys.contains(expression) ? "$(" + expression + ")" : expression, numericKeys);
            return "0";
        }));
    }

    private static JsonNode map(TextSpec spec, Function<String, String> transform) {
        if (!"component".equals(spec.getFormat())) {
            return JsonNodeFactory.instance.stringNode(transform.apply(spec.getValue().asString()));
        }
        JsonNode copy = spec.getValue().deepCopy();
        component(copy, transform);
        return copy;
    }

    // Component metadata (commands, URL, translation key, etc.) is never interpolated.
    private static void component(JsonNode node, Function<String, String> transform) {
        if (node.isArray()) node.forEach(child -> component(child, transform));
        else if (node instanceof ObjectNode object) {
            if (object.has("text") && object.get("text").isString()) {
                object.put("text", transform.apply(object.get("text").asString()));
            }
            for (String children : java.util.List.of("extra", "with")) {
                if (object.has(children)) component(object.get(children), transform);
            }
        }
    }

    private static String expand(String source, Function<String, String> evaluate) {
        StringBuilder out = new StringBuilder();
        int cursor = 0, start;
        while ((start = source.indexOf("$(", cursor)) >= 0) {
            out.append(source, cursor, start);
            int depth = 1, end = start + 2;
            for (; end < source.length() && depth > 0; end++) {
                char c = source.charAt(end);
                if (c == '(') { if (++depth > 32) throw new IllegalArgumentException("Message expression too deeply nested"); }
                else if (c == ')') depth--;
            }
            if (depth != 0) throw new IllegalArgumentException("Message expression is missing closing parenthesis");
            String expression = source.substring(start + 2, end - 1);
            out.append(evaluate.apply(expression));
            cursor = end;
        }
        return out.append(source, cursor, source.length()).toString();
    }
}
