package dialogcanvas.engine.condition;

import dialogcanvas.model.ConditionDefinition;
import dialogcanvas.model.MenuJson;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;

import static org.junit.jupiter.api.Assertions.*;

class ConditionEngineTest {
    private final JsonMapper mapper = MenuJson.createMapper();
    private PlayerMock player;
    private ConditionEngine engine;
    private Logger logger;
    private List<LogRecord> logs;

    @BeforeEach
    void setUp() {
        var server = MockBukkit.mock();
        player = server.addPlayer();
        player.addAttachment(MockBukkit.createMockPlugin(), "shop.buy", true);
        logs = new ArrayList<>();
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            public void publish(LogRecord record) { logs.add(record); }
            public void flush() {}
            public void close() {}
        });
        engine = new ConditionEngine(logger);
    }

    @AfterEach
    void tearDown() { MockBukkit.unmock(); }

    private ConditionDefinition condition(String json) {
        return mapper.readValue(json, ConditionDefinition.class);
    }

    private JsonNode value(String json) { return mapper.readTree(json); }

    private ConditionContext context(Map<String, JsonNode> inputs, PlaceholderResolver resolver, PlayerDataView data) {
        return new ConditionContext(player, inputs, resolver, data);
    }

    private ConditionContext emptyContext() {
        return context(Map.of(), (actor, token) -> Optional.empty(), (id, key) -> Optional.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "null", "unchecked", "exception", "linkage", "secret", "number", "nan", "empty"})
    void providerFailuresCannotPassInequalityNegationOrNestedGroups(String mode) {
        var calls = new AtomicInteger();
        PlaceholderResolver resolver = new PlaceholderResolver() {
            public Optional<String> resolve(org.bukkit.entity.Player p, String token) { throw new AssertionError(); }
            public Result resolveResult(org.bukkit.entity.Player p, String token) {
                calls.incrementAndGet();
                return switch (mode) {
                    case "absent" -> Result.failure("PLACEHOLDERAPI_UNAVAILABLE");
                    case "null" -> null;
                    case "unchecked" -> Result.ok("%private_output%");
                    case "exception" -> throw new IllegalArgumentException("private_output");
                    case "linkage" -> throw new NoClassDefFoundError("private_output");
                    case "secret" -> Result.failure("private_output\nprivate_input");
                    case "number" -> Result.ok("private_output");
                    case "nan" -> Result.ok("NaN");
                    default -> Result.ok("");
                };
            }
        };
        for (boolean negate : List.of(false, true)) {
            var leaf = condition("""
                {"type":"placeholder","placeholder":"%private_input%","comparison":"numeric","operator":"!=","value":"0"}
                """);
            leaf.setNegate(negate);
            for (String group : List.of("AND", "OR")) {
                var nested = new ConditionDefinition(); nested.setOperator(group); nested.setNegate(true);
                nested.setItems(List.of(leaf, condition("{\"type\":\"permission\",\"permission\":\"shop.buy\"}")));
                var result = engine.evaluateDetailed(nested, context(Map.of(), resolver, (id, key) -> Optional.empty()), "transaction=test");
                assertEquals(ConditionResult.Status.ERROR, result.status()); assertFalse(result.passed());
                assertFalse(result.toString().contains("private_"));
            }
        }
        assertEquals(4, calls.get()); assertEquals(4, logs.size());
        for (var log : logs) {
            assertTrue(log.getMessage().contains("transaction=test conditions.items[0].left"));
            assertFalse(log.getMessage().contains("private_"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"==", "!="})
    void unavailableStringComparisonCannotBeInverted(String operator) {
        var leaf = condition("""
            {"type":"placeholder","placeholder":"%unavailable%","comparison":"string","operator":"==","value":""}
            """);
        leaf.setOperator(operator);
        for (boolean negate : List.of(false, true)) {
            leaf.setNegate(negate);
            assertEquals(ConditionResult.Status.ERROR, engine.evaluateDetailed(leaf, emptyContext()).status());
        }
        assertEquals(2, logs.size());
    }

    @ParameterizedTest @ValueSource(strings = {"AND", "OR"})
    void skippedUnavailableProviderDoesNotRunOrWarn(String group) {
        var tree = new ConditionDefinition(); tree.setOperator(group);
        var first = condition("{\"type\":\"permission\",\"permission\":\"shop.buy\"}");
        first.setNegate(group.equals("AND"));
        tree.setItems(List.of(first, condition("""
            {"type":"placeholder","placeholder":"%unavailable%","comparison":"string","operator":"!=","value":"x","negate":true}
            """)));
        var result = engine.evaluateDetailed(tree, context(Map.of(), (p, token) -> { fail("short circuited source"); return Optional.empty(); }, (id, key) -> Optional.empty()));
        assertEquals(group.equals("OR") ? ConditionResult.Status.PASS : ConditionResult.Status.FAIL, result.status());
        assertTrue(logs.isEmpty());
    }

    private String numeric(String left, String operator, String right) {
        return "{\"type\":\"numeric\",\"left\":{\"source\":\"literal\",\"value\":" + left
                + "},\"operator\":\"" + operator + "\",\"right\":{\"source\":\"literal\",\"value\":" + right + "}}";
    }

    @Test
    void coversAllFiveTypes() {
        var context = context(Map.of("confirm", value("true")), (actor, token) -> {
            assertTrue(Bukkit.isPrimaryThread());
            return Optional.of("1500");
        }, (id, key) -> {
            assertEquals(player.getUniqueId(), id);
            return Optional.of(value("\"enabled\""));
        });
        var tree = condition("""
                {"operator":"AND","items":[
                  {"type":"permission","permission":"shop.buy"},
                  {"type":"placeholder","placeholder":"%balance%","operator":">=","value":"1000"},
                  {"type":"player_data","key":"status","comparison":"string","operator":"==","value":"enabled"},
                  {"type":"numeric","left":{"source":"literal","value":2},"operator":"<","right":{"source":"literal","value":"3"}},
                  {"type":"string","left":{"source":"input","key":"confirm"},"operator":"==","right":{"source":"literal","value":"true"}}
                ]}
                """);
        assertTrue(engine.evaluate(tree, context));
        assertTrue(logs.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"==,1,1.0,true", "!=,1,2,true", ">,2,1,true", ">=,1,1,true",
            "<,1,2,true", "<=,1,1,true", "==,1,2,false", "==,-0.0,0.0,true",
            ">,-1,-2,true", "==,1e3,1000,true"})
    void comparesFiniteDoubles(String operator, String left, String right, boolean expected) {
        assertEquals(expected, engine.evaluate(condition(numeric(left, operator, right)), emptyContext()));
    }

    @Test
    void parsesTrimmedDecimalStringAndReportsNormalMismatchWithoutWarning() {
        assertTrue(engine.evaluate(condition(numeric("\"  +1.5  \"", "==", "1.5")), emptyContext()));
        assertFalse(engine.evaluate(condition(numeric("1", ">", "2")), emptyContext()));
        assertTrue(logs.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"NaN\"", "\"Infinity\"", "\"1e400\"", "\"abc\"", "\"\"", "true", "\"0x1.0p0\"", "\"1 && true\""})
    void rejectsInvalidNumericInputWithoutCoercingItToZero(String raw) {
        var context = context(Map.of("amount", value(raw)), (actor, token) -> Optional.empty(), (id, key) -> Optional.empty());
        var tree = condition("""
                {"type":"numeric","left":{"source":"input","key":"amount"},"operator":"==",
                 "right":{"source":"literal","value":0},"negate":true}
                """);
        var result = engine.evaluateDetailed(tree, context);
        assertEquals(ConditionResult.Status.ERROR, result.status());
        assertEquals("INVALID_NUMBER", result.errorCode());
        assertFalse(result.passed());
    }

    @Test
    void stringComparisonIsCaseSensitiveAndDoesNotTrim() {
        var tree = condition("""
                {"type":"string","left":{"source":"input","key":"text"},"operator":"==",
                 "right":{"source":"literal","value":"Admin"}}
                """);
        assertFalse(engine.evaluate(tree, context(Map.of("text", value("\"admin\"")),
                (actor, token) -> Optional.empty(), (id, key) -> Optional.empty())));
        assertFalse(engine.evaluate(tree, context(Map.of("text", value("\" Admin \"")),
                (actor, token) -> Optional.empty(), (id, key) -> Optional.empty())));
        tree.setOperator("!=");
        assertTrue(engine.evaluate(tree, context(Map.of("text", value("\"admin\"")),
                (actor, token) -> Optional.empty(), (id, key) -> Optional.empty())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "unchanged", "exception", "null"})
    void placeholderFailureReturnsFalseAndWarnsOnceEvenUnderNegation(String mode) {
        PlaceholderResolver resolver = (actor, token) -> switch (mode) {
            case "empty" -> Optional.empty();
            case "unchanged" -> Optional.of(token);
            case "null" -> null;
            default -> throw new IllegalStateException("Test resolver failure");
        };
        var tree = condition("""
                {"type":"placeholder","placeholder":"%balance%","comparison":"numeric",
                 "operator":">=","value":"1000","negate":true}
                """);
        assertFalse(engine.evaluate(tree, context(Map.of(), resolver, (id, key) -> Optional.empty())));
        assertEquals(1, logs.size());
        assertEquals(Level.WARNING, logs.getFirst().getLevel());
        assertTrue(logs.getFirst().getMessage().contains("PLACEHOLDER_RESOLUTION_FAILED"));
    }

    @Test
    void malformedPlaceholderNumberAlsoReturnsFalseWithWarning() {
        var tree = condition("{\"type\":\"placeholder\",\"placeholder\":\"%balance%\",\"operator\":\">=\",\"value\":\"1\"}");
        assertFalse(engine.evaluate(tree, context(Map.of(), (actor, token) -> Optional.of("not a number"),
                (id, key) -> Optional.empty())));
        assertEquals(Level.WARNING, logs.getFirst().getLevel());
    }

    @Test
    void nestedGroupsApplyNegationAfterOrderedShortCircuiting() {
        var calls = new ArrayList<String>();
        var context = context(Map.of(), (actor, token) -> { calls.add(token); return Optional.of("ok"); },
                (id, key) -> Optional.empty());
        var tree = condition("""
                {"operator":"AND","items":[
                  {"operator":"OR","items":[
                    {"type":"permission","permission":"missing"},
                    {"type":"placeholder","placeholder":"%first%","comparison":"string","operator":"==","value":"ok"},
                    {"type":"placeholder","placeholder":"%skip%","comparison":"string","operator":"==","value":"ok"}]},
                  {"operator":"AND","negate":true,"items":[
                    {"type":"permission","permission":"missing"},
                    {"type":"placeholder","placeholder":"%also_skip%","comparison":"string","operator":"==","value":"ok"}]},
                  {"type":"placeholder","placeholder":"%second%","comparison":"string","operator":"==","value":"ok"}]}
                """);
        assertTrue(engine.evaluate(tree, context));
        assertEquals(List.of("%first%", "%second%"), calls);
    }

    @Test
    void runtimeErrorAbortsOrBeforeAnOtherwisePassingBranch() {
        var tree = condition("""
                {"operator":"OR","negate":true,"items":[
                  {"type":"placeholder","placeholder":"%missing%","comparison":"string","operator":"==","value":"ok"},
                  {"type":"permission","permission":"shop.buy"}]}
                """);
        var result = engine.evaluateDetailed(tree, emptyContext());
        assertEquals(ConditionResult.Status.ERROR, result.status());
        assertFalse(result.passed());
        assertTrue(result.path().startsWith("conditions.items[0]"));
    }

    @Test
    void cachesEachSourceOncePerEvaluationButNotAcrossEvaluations() {
        var placeholders = new AtomicInteger();
        var data = new AtomicInteger();
        var context = context(Map.of(), (actor, token) -> { placeholders.incrementAndGet(); return Optional.of("2"); },
                (id, key) -> { data.incrementAndGet(); return Optional.of(value("2")); });
        var tree = condition("""
                {"operator":"AND","items":[
                  {"type":"placeholder","placeholder":"%amount%","operator":">=","value":"1"},
                  {"type":"numeric","left":{"source":"placeholder","placeholder":"%amount%"},"operator":"==",
                   "right":{"source":"player_data","key":"amount"}},
                  {"type":"player_data","key":"amount","operator":"<=","value":"3"}]}
                """);
        assertTrue(engine.evaluate(tree, context));
        assertEquals(1, placeholders.get());
        assertEquals(1, data.get());
        assertTrue(engine.evaluate(tree, context));
        assertEquals(2, placeholders.get());
        assertEquals(2, data.get());
    }

    @Test
    void validatesSkippedBranchesAndDoesNotResolveSourcesForInvalidTrees() {
        var calls = new AtomicInteger();
        var tree = condition("""
                {"operator":"OR","items":[{"type":"permission","permission":"shop.buy"},
                 {"type":"string","left":{"source":"literal","value":"x"},"operator":">",
                  "right":{"source":"placeholder","placeholder":"%never%"}}]}
                """);
        var result = engine.evaluateDetailed(tree, context(Map.of(), (actor, token) -> {
            calls.incrementAndGet(); return Optional.of("x");
        }, (id, key) -> Optional.empty()));
        assertEquals("INVALID_OPERATOR", result.errorCode());
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"operator\":\"AND\",\"items\":[]}",
            "{\"type\":\"unknown\"}",
            "{\"type\":\"permission\",\"permission\":\"shop.buy\",\"items\":[]}",
            "{\"type\":\"placeholder\",\"placeholder\":\"%x%\",\"operator\":\"==\",\"value\":\"1\"}",
            "{\"operator\":\"XOR\",\"items\":[{\"type\":\"permission\",\"permission\":\"shop.buy\"}]}"})
    void rejectsInvalidNodeStructures(String json) {
        assertEquals(ConditionResult.Status.ERROR, engine.evaluateDetailed(condition(json), emptyContext()).status());
    }

    @Test
    void reportsMissingInputsDataAndCompoundValues() {
        var numericInput = condition("""
                {"type":"numeric","left":{"source":"input","key":"amount"},"operator":">=",
                 "right":{"source":"literal","value":1}}
                """);
        assertEquals("MISSING_INPUT", engine.evaluateDetailed(numericInput, emptyContext()).errorCode());
        assertEquals("INVALID_VALUE_TYPE", engine.evaluateDetailed(numericInput,
                context(Map.of("amount", value("[]")), (actor, token) -> Optional.empty(), (id, key) -> Optional.empty())).errorCode());
        var data = condition("{\"type\":\"player_data\",\"key\":\"count\",\"operator\":\">=\",\"value\":\"1\"}");
        assertEquals("MISSING_PLAYER_DATA", engine.evaluateDetailed(data, emptyContext()).errorCode());
    }

    @Test
    void enforcesDepthNodeAndCycleConstraints() {
        var leaf = condition("{\"type\":\"permission\",\"permission\":\"shop.buy\"}");
        var tree = condition("{\"operator\":\"AND\",\"items\":[]}");
        tree.setItems(List.of(leaf, leaf));
        assertEquals("TREE_NODE_LIMIT", new ConditionEngine(logger, new ConditionEngine.Limits(4, 2))
                .evaluateDetailed(tree, emptyContext()).errorCode());
        assertEquals("TREE_DEPTH_LIMIT", new ConditionEngine(logger, new ConditionEngine.Limits(1, 10))
                .evaluateDetailed(tree, emptyContext()).errorCode());
        tree.setItems(List.of(tree));
        assertEquals("CYCLIC_CONDITION", engine.evaluateDetailed(tree, emptyContext()).errorCode());
    }

    @Test
    void inputContextCopiesMutableNodesAndNullConditionsPass() {
        var original = value("{\"x\":1}");
        var context = context(Map.of("data", original), (actor, token) -> Optional.empty(), (id, key) -> Optional.empty());
        assertNotSame(original, context.input("data"));
        assertTrue(engine.evaluate(null, context));
        assertThrows(IllegalArgumentException.class, () -> new ConditionEngine.Limits(0, 1));
    }

    @Test
    void rejectsEvaluationOnWorkerThreadBeforeCallingAdapters() {
        var calls = new AtomicInteger();
        var context = context(Map.of(), (actor, token) -> { calls.incrementAndGet(); return Optional.of("1"); },
                (id, key) -> Optional.empty());
        CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class,
                () -> engine.evaluate(null, context))).join();
        assertEquals(0, calls.get());
    }
}
