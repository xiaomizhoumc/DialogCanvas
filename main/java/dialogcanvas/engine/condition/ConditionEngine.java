package dialogcanvas.engine.condition;

import dialogcanvas.model.ConditionDefinition;
import dialogcanvas.model.ValueSourceDefinition;
import org.bukkit.Bukkit;
import tools.jackson.databind.JsonNode;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Validates the entire tree, freezes its operands, then evaluates on the main thread. */
public final class ConditionEngine {
    public record Limits(int maxDepth, int maxNodes) {
        public Limits {
            if (maxDepth < 1 || maxNodes < 1) throw new IllegalArgumentException("Limits must be positive");
        }
    }

    private static final Set<String> NUMBER_OPERATORS = Set.of("==", "!=", ">", ">=", "<", "<=");
    private static final Set<String> STRING_OPERATORS = Set.of("==", "!=");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private final Logger logger;
    private final Limits limits;

    public ConditionEngine(Logger logger) {
        this(logger, new Limits(32, 1024));
    }

    public ConditionEngine(Logger logger, Limits limits) {
        this.logger = Objects.requireNonNull(logger);
        this.limits = Objects.requireNonNull(limits);
    }

    public boolean evaluate(ConditionDefinition definition, ConditionContext context) {
        return evaluateDetailed(definition, context).passed();
    }

    /** Pure structural compilation: no player, Bukkit, placeholders or data lookups. */
    public ConditionResult validate(ConditionDefinition definition) {
        try {
            if (definition != null) compile(definition, "conditions", 1, new Counter(),
                    Collections.newSetFromMap(new IdentityHashMap<>()));
            return new ConditionResult(ConditionResult.Status.PASS, "conditions", null, null);
        } catch (ConditionError failure) {
            return new ConditionResult(ConditionResult.Status.ERROR, failure.path, failure.code, failure.getMessage());
        }
    }

    public ConditionResult evaluateDetailed(ConditionDefinition definition, ConditionContext context) {
        return evaluateDetailed(definition, context, "condition");
    }

    public ConditionResult evaluateDetailed(ConditionDefinition definition, ConditionContext context, String location) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("ConditionEngine must be called through Scheduler.sync() on the main thread");
        }
        Objects.requireNonNull(context);
        try {
            if (definition == null) return result(true, "conditions");
            Node compiled = compile(definition, "conditions", 1, new Counter(),
                    Collections.newSetFromMap(new IdentityHashMap<>()));
            return evaluate(compiled, new Frame(context, PlaceholderResolver.logLocation(location)));
        } catch (ConditionError failure) {
            if (!failure.warningLogged) logger.warning("Condition evaluation failed [" + failure.code + "] at " + PlaceholderResolver.logLocation(location) + " " + failure.path);
            return new ConditionResult(ConditionResult.Status.ERROR, failure.path, failure.code, failure.getMessage());
        }
    }

    private Node compile(ConditionDefinition definition, String path, int depth, Counter counter,
                         Set<ConditionDefinition> ancestors) {
        if (depth > limits.maxDepth()) throw error("TREE_DEPTH_LIMIT", path, "Maximum nesting depth exceeded");
        if (++counter.nodes > limits.maxNodes()) throw error("TREE_NODE_LIMIT", path, "Maximum node count exceeded");
        if (definition == null) throw error("INVALID_CONDITION", path, "Null condition node");
        if (!ancestors.add(definition)) throw error("CYCLIC_CONDITION", path, "Cyclic condition object");
        try {
            boolean negate = Boolean.TRUE.equals(definition.getNegate());
            if (definition.getType() == null) {
                fields(definition, Set.of("operator", "items", "negate"), path);
                if (!Set.of("AND", "OR").contains(required(definition.getOperator(), path + ".operator"))) {
                    throw error("INVALID_OPERATOR", path + ".operator", "Group operator must be AND or OR");
                }
                if (definition.getItems() == null || definition.getItems().isEmpty()) {
                    throw error("EMPTY_GROUP", path + ".items", "Group requires at least one child");
                }
                var children = new ArrayList<Node>();
                for (int index = 0; index < definition.getItems().size(); index++) {
                    children.add(compile(definition.getItems().get(index), path + ".items[" + index + "]",
                            depth + 1, counter, ancestors));
                }
                return new Node("group", definition.getOperator(), negate, path, List.copyOf(children), null, null, null, null);
            }
            String type = definition.getType();
            Set<String> common = Set.of("type", "negate");
            if ("permission".equals(type)) {
                fields(definition, union(common, "permission"), path);
                return new Node(type, null, negate, path, List.of(),
                        required(definition.getPermission(), path + ".permission"), null, null, null);
            }
            String comparison;
            Source left, right;
            switch (type) {
                case "numeric", "string" -> {
                    fields(definition, union(common, "left", "right", "operator"), path);
                    comparison = type;
                    left = source(definition.getLeft(), path + ".left");
                    right = source(definition.getRight(), path + ".right");
                }
                case "placeholder", "player_data" -> {
                    String field = "placeholder".equals(type) ? "placeholder" : "key";
                    fields(definition, union(common, field, "comparison", "operator", "value"), path);
                    comparison = definition.getComparison();
                    if (comparison == null && Set.of(">", ">=", "<", "<=").contains(
                            required(definition.getOperator(), path + ".operator"))) comparison = "numeric";
                    left = new Source(type, required("placeholder".equals(type) ? definition.getPlaceholder()
                            : definition.getKey(), path + "." + field), null);
                    if (definition.getValue() == null) throw error("MISSING_FIELD", path + ".value", "value is required");
                    right = new Source("literal", null, definition.getValue());
                }
                default -> throw error("UNKNOWN_CONDITION_TYPE", path + ".type", "Unsupported condition type");
            }
            String operator = required(definition.getOperator(), path + ".operator");
            if (!"numeric".equals(comparison) && !"string".equals(comparison)) {
                throw error("INVALID_COMPARISON", path + ".comparison", "comparison must be numeric or string");
            }
            if (!("numeric".equals(comparison) ? NUMBER_OPERATORS : STRING_OPERATORS).contains(operator)) {
                throw error("INVALID_OPERATOR", path + ".operator", "Unsupported comparison operator");
            }
            // Validate constants even in branches that would be skipped by short-circuiting.
            for (Source operand : List.of(left, right)) {
                if ("literal".equals(operand.kind)) {
                    if ("numeric".equals(comparison)) number(operand.literal, path);
                    else string(operand.literal, path);
                }
            }
            return new Node(type, operator, negate, path, List.of(), null, left, right, comparison);
        } finally {
            ancestors.remove(definition);
        }
    }

    private Source source(ValueSourceDefinition definition, String path) {
        if (definition == null) throw error("MISSING_FIELD", path, "Value source is required");
        String kind = required(definition.getSource(), path + ".source");
        switch (kind) {
            case "literal" -> {
                if (definition.getKey() != null || definition.getPlaceholder() != null) throw error("INVALID_SOURCE", path, "Literal has unrelated fields");
                return new Source(kind, null, scalar(definition.getValue(), path));
            }
            case "input", "player_data" -> {
                if (definition.getValue() != null || definition.getPlaceholder() != null) throw error("INVALID_SOURCE", path, "Key source has unrelated fields");
                return new Source(kind, required(definition.getKey(), path + ".key"), null);
            }
            case "placeholder" -> {
                if (definition.getValue() != null || definition.getKey() != null) throw error("INVALID_SOURCE", path, "Placeholder has unrelated fields");
                return new Source(kind, required(definition.getPlaceholder(), path + ".placeholder"), null);
            }
            default -> throw error("INVALID_SOURCE", path + ".source", "Unknown value source");
        }
    }

    private ConditionResult evaluate(Node node, Frame frame) {
        boolean matched;
        if ("group".equals(node.kind)) {
            matched = "AND".equals(node.operator);
            for (Node child : node.children) {
                boolean value = evaluate(child, frame).passed();
                if ("AND".equals(node.operator) && !value) { matched = false; break; }
                if ("OR".equals(node.operator) && value) { matched = true; break; }
            }
        } else if ("permission".equals(node.kind)) {
            try {
                matched = frame.permissions.computeIfAbsent(node.permission, frame.context.player()::hasPermission);
            } catch (RuntimeException failure) {
                throw error("PERMISSION_LOOKUP_FAILED", node.path, "Permission lookup failed");
            }
        } else {
            Object left = resolve(node.left, node.path + ".left", frame);
            Object right = resolve(node.right, node.path + ".right", frame);
            if ("numeric".equals(node.comparison)) {
                double a = number(left, node.path + ".left"), b = number(right, node.path + ".right");
                matched = switch (node.operator) {
                    case "==" -> a == b;
                    case "!=" -> a != b;
                    case ">" -> a > b;
                    case ">=" -> a >= b;
                    case "<" -> a < b;
                    case "<=" -> a <= b;
                    default -> throw new AssertionError(node.operator);
                };
            } else {
                matched = string(left, node.path + ".left").equals(string(right, node.path + ".right"));
                if ("!=".equals(node.operator)) matched = !matched;
            }
        }
        return result(node.negate ? !matched : matched, node.path);
    }

    private Object resolve(Source source, String path, Frame frame) {
        if ("literal".equals(source.kind)) return source.literal;
        CacheKey key = new CacheKey(source.kind, source.key);
        if (frame.values.containsKey(key)) return frame.values.get(key);
        Object value;
        try {
            value = switch (source.kind) {
                case "input" -> {
                    JsonNode input = frame.context.input(source.key);
                    if (input == null || input.isNull()) throw error("MISSING_INPUT", path, "Input key is missing");
                    yield scalar(input, path);
                }
                case "player_data" -> scalar(frame.context.playerData().find(frame.context.player().getUniqueId(), source.key)
                        .orElseThrow(() -> error("MISSING_PLAYER_DATA", path, "Player data key is missing")), path);
                case "placeholder" -> {
                    PlaceholderResolver.Result resolved = frame.context.placeholders().resolveResult(frame.context.player(), source.key, frame.location + " " + path);
                    if (resolved == null || !resolved.success()) {
                        ConditionError failure = error("PLACEHOLDER_RESOLUTION_FAILED", path,
                                resolved == null ? "NULL_PLACEHOLDER_RESULT" : resolved.diagnosticCode());
                        failure.warningLogged = resolved != null && resolved.warningLogged();
                        throw failure;
                    }
                    PlaceholderResolver.Result checked = PlaceholderResolver.Result.validate(resolved.value());
                    if (!checked.success()) throw error("PLACEHOLDER_RESOLUTION_FAILED", path, checked.error());
                    yield resolved.value();
                }
                default -> throw new AssertionError(source.kind);
            };
        } catch (ConditionError failure) {
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            throw error("placeholder".equals(source.kind) ? "PLACEHOLDER_RESOLUTION_FAILED" : "SOURCE_LOOKUP_FAILED",
                    path, "Value source lookup failed");
        }
        frame.values.put(key, value);
        return value;
    }

    private static Object scalar(JsonNode value, String path) {
        if (value == null || value.isNull()) throw error("MISSING_VALUE", path, "Required value is missing");
        if (value.isString()) return value.asString();
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.doubleValue();
        throw error("INVALID_VALUE_TYPE", path, "Only strings, numbers and booleans are supported");
    }

    private static double number(Object value, String path) {
        double number;
        if (value instanceof Number numeric) number = numeric.doubleValue();
        else if (value instanceof String text && DECIMAL.matcher(text.trim()).matches()) {
            try { number = Double.parseDouble(text.trim()); }
            catch (NumberFormatException failure) { throw error("INVALID_NUMBER", path, "Cannot parse numeric value"); }
        } else throw error("INVALID_NUMBER", path, "Expected a decimal numeric value");
        if (!Double.isFinite(number)) throw error("INVALID_NUMBER", path, "Numeric value must be finite");
        return number;
    }

    private static String string(Object value, String path) {
        if (value instanceof String text) return text;
        if (value instanceof Boolean bool) return bool.toString();
        if (value instanceof Number numeric) return Double.toString(number(numeric, path));
        throw error("INVALID_VALUE_TYPE", path, "Cannot convert value to a string");
    }

    private static void fields(ConditionDefinition value, Set<String> allowed, String path) {
        var supplied = new LinkedHashMap<String, Object>();
        supplied.put("type", value.getType()); supplied.put("items", value.getItems());
        supplied.put("operator", value.getOperator()); supplied.put("negate", value.getNegate());
        supplied.put("permission", value.getPermission()); supplied.put("placeholder", value.getPlaceholder());
        supplied.put("key", value.getKey()); supplied.put("value", value.getValue());
        supplied.put("comparison", value.getComparison()); supplied.put("left", value.getLeft()); supplied.put("right", value.getRight());
        supplied.forEach((name, field) -> {
            if (field != null && !allowed.contains(name)) throw error("UNEXPECTED_FIELD", path + "." + name, "Field does not belong to this condition type");
        });
    }

    private static Set<String> union(Set<String> original, String... fields) {
        var result = new HashSet<>(original);
        result.addAll(List.of(fields));
        return result;
    }

    private static String required(String value, String path) {
        if (value == null || value.isBlank()) throw error("MISSING_FIELD", path, "Nonblank field is required");
        return value;
    }

    private static ConditionResult result(boolean passed, String path) {
        return new ConditionResult(passed ? ConditionResult.Status.PASS : ConditionResult.Status.FAIL, path, null, null);
    }

    private static ConditionError error(String code, String path, String message) {
        return new ConditionError(code, path, message);
    }

    private record Source(String kind, String key, Object literal) {}
    private record CacheKey(String kind, String key) {}
    private record Node(String kind, String operator, boolean negate, String path, List<Node> children,
                        String permission, Source left, Source right, String comparison) {}
    private static final class Counter { int nodes; }
    private static final class Frame {
        final ConditionContext context;
        final String location;
        final Map<CacheKey, Object> values = new HashMap<>();
        final Map<String, Boolean> permissions = new HashMap<>();
        Frame(ConditionContext context, String location) { this.context = context; this.location = location; }
    }
    private static final class ConditionError extends RuntimeException {
        final String code, path;
        boolean warningLogged;
        ConditionError(String code, String path, String message) {
            super(message); this.code = code; this.path = path;
        }
    }
}
