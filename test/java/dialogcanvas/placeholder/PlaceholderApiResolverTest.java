package dialogcanvas.placeholder;

import dialogcanvas.engine.condition.*;
import dialogcanvas.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

class PlaceholderApiResolverTest {
    PlayerMock player;
    Logger logger;
    List<LogRecord> logs;
    @BeforeEach void setup() {
        player = MockBukkit.mock().addPlayer(); logs = new ArrayList<>();
        logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            public void publish(LogRecord record) { logs.add(record); }
            public void flush() {} public void close() {}
        });
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }
    @Test void missingOptionalPluginFailsWithoutLinkingPapi() {
        var resolver = new PlaceholderApiResolver(logger);
        var result = resolver.resolveResult(player, "%player_name%");
        assertFalse(result.success()); assertEquals("PLACEHOLDERAPI_UNAVAILABLE", result.error());
        assertEquals(1, logs.size()); assertEquals(Level.WARNING, logs.getFirst().getLevel());
    }
    @Test void plainTextNeedsNoPapiAndSuccessHasNoWarning() {
        var resolver = new PlaceholderApiResolver(logger, () -> { fail("no dependency lookup needed"); return false; },
                (p, text) -> { fail("plain text must bypass PAPI"); return null; });
        assertEquals("literal text", resolver.resolveResult(player, "literal text").value());
        assertTrue(logs.isEmpty());
    }
    @Test void resolvedTextAndEmptyValueAreSuccessful() {
        var resolver = new PlaceholderApiResolver(logger, () -> true, (p, text) -> {
            assertSame(player, p); assertTrue(org.bukkit.Bukkit.isPrimaryThread()); return "Buyer has 1000";
        });
        var result = resolver.resolveResult(player, "%player_name% has %balance%");
        assertTrue(result.success()); assertEquals("Buyer has 1000", result.value()); assertNull(result.error());
        assertEquals(Optional.of("Buyer has 1000"), resolver.resolve(player, "%player_name%"));
        assertTrue(new PlaceholderApiResolver(logger, () -> true, (p, s) -> "").resolveResult(player, "%empty%").success());
        assertTrue(logs.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"%balance%", "1000 %missing%", "50%", "%"})
    void anyRemainingPercentIsFailureAndWarnsOnce(String output) {
        var resolver = new PlaceholderApiResolver(logger, () -> true, (p, text) -> output);
        var result = resolver.resolveResult(player, "%balance%");
        assertFalse(result.success()); assertEquals("UNRESOLVED_PERCENT", result.error()); assertNull(result.value());
        assertEquals(1, logs.size()); assertFalse(logs.getFirst().getMessage().contains(output));
    }
    @ParameterizedTest @ValueSource(strings = {"null", "exception", "linkage"})
    void parserFailuresReturnResult(String mode) {
        var resolver = new PlaceholderApiResolver(logger, () -> true, (p, text) -> {
            if (mode.equals("null")) return null;
            if (mode.equals("linkage")) throw new NoClassDefFoundError("optional API missing");
            throw new IllegalStateException("backend failed");
        });
        assertFalse(resolver.resolveResult(player, "%balance%").success()); assertEquals(1, logs.size());
    }
    @Test void workerParsingDoesNotInvokeBackend() throws Exception {
        var calls = new AtomicInteger();
        var resolver = new PlaceholderApiResolver(logger, () -> { calls.incrementAndGet(); return true; }, (p, s) -> "1");
        var result = CompletableFuture.supplyAsync(() -> resolver.resolveResult(player, "%balance%")).get();
        assertFalse(result.success()); assertEquals("NOT_MAIN_THREAD", result.error()); assertEquals(0, calls.get());
    }
    @Test void conditionFailsClosedUnderNegationAndOrWithoutDuplicateWarn() {
        var resolver = new PlaceholderApiResolver(logger, () -> true, (p, text) -> "1000 %missing%");
        var tree = MenuJson.createMapper().readValue("""
            {"operator":"OR","items":[
              {"type":"placeholder","placeholder":"%balance%","operator":">=","value":"1","negate":true},
              {"type":"permission","permission":"shop.buy"}]}
            """, ConditionDefinition.class);
        // Use a valid always-passing second branch; it must not hide a visited parse ERROR.
        var second = new ConditionDefinition(); second.setType("permission"); second.setPermission("shop.buy");
        player.addAttachment(MockBukkit.createMockPlugin(), "shop.buy", true);
        tree.setItems(List.of(tree.getItems().getFirst(), second));
        var result = new ConditionEngine(logger).evaluateDetailed(tree,
                new ConditionContext(player, Map.of(), resolver, (id, key) -> Optional.empty()));
        assertFalse(result.passed()); assertEquals(ConditionResult.Status.ERROR, result.status());
        assertEquals("PLACEHOLDER_RESOLUTION_FAILED", result.errorCode()); assertEquals(1, logs.size());
    }
    @ParameterizedTest @ValueSource(strings = {"absent", "extension", "partial", "null", "exception", "linkage", "availability", "number"})
    void productionProviderFailureWarnsOnceWithSafeConditionLocation(String mode) {
        var resolver = new PlaceholderApiResolver(logger,
            () -> { if (mode.equals("availability")) throw new IllegalStateException("private_input"); return !mode.equals("absent"); },
            (p, text) -> switch (mode) {
                case "extension" -> text;
                case "partial" -> "private_output %missing%";
                case "null" -> null;
                case "exception" -> throw new IllegalStateException("private_output");
                case "linkage" -> throw new NoClassDefFoundError("private_output");
                case "number" -> "private_output";
                default -> "1";
            });
        var leaf = MenuJson.createMapper().readValue("""
            {"type":"placeholder","placeholder":"%private_input%","comparison":"numeric","operator":"!=","value":"0","negate":true}
            """, ConditionDefinition.class);
        var result = new ConditionEngine(logger).evaluateDetailed(leaf,
            new ConditionContext(player, Map.of(), resolver, (id, key) -> Optional.empty()), "transaction=test");
        assertEquals(ConditionResult.Status.ERROR, result.status()); assertEquals(1, logs.size());
        String log = logs.getFirst().getMessage(); assertTrue(log.contains("transaction=test conditions.left"));
        assertFalse(log.contains("private_")); assertEquals(Level.WARNING, logs.getFirst().getLevel());
    }
    @Test void ordinaryConditionMismatchDoesNotWarn() {
        var resolver = new PlaceholderApiResolver(logger, () -> true, (p, text) -> "5");
        var leaf = MenuJson.createMapper().readValue("""
            {"type":"placeholder","placeholder":"%balance%","operator":">=","value":"10"}
            """, ConditionDefinition.class);
        assertEquals(ConditionResult.Status.FAIL, new ConditionEngine(logger).evaluateDetailed(leaf,
            new ConditionContext(player, Map.of(), resolver, (id, key) -> Optional.empty())).status());
        assertTrue(logs.isEmpty());
    }
    @Test void nullArgumentsFailBeforeBackendAndResultRepresentationIsRedacted() {
        var resolver = new PlaceholderApiResolver(logger, () -> { fail("invalid input"); return true; }, (p, text) -> "1");
        assertEquals("INVALID_PLACEHOLDER_INPUT", resolver.resolveResult(null, "%x%").error());
        assertEquals("INVALID_PLACEHOLDER_INPUT", resolver.resolveResult(player, null).error());
        assertEquals(2, logs.size());
        assertFalse(PlaceholderResolver.Result.ok("private_output").toString().contains("private_"));
        assertFalse(PlaceholderResolver.Result.failure("private_input").toString().contains("private_"));
    }

    @Test void conditionUsesParsedValueAndCachesPerEvaluation() {
        var calls = new AtomicInteger();
        var resolver = new PlaceholderApiResolver(logger, () -> true, (p, text) -> { calls.incrementAndGet(); return "1000"; });
        var leaf = MenuJson.createMapper().readValue("""
            {"type":"placeholder","placeholder":"%balance%","operator":">=","value":"1"}
            """, ConditionDefinition.class);
        var tree = new ConditionDefinition(); tree.setOperator("AND"); tree.setItems(List.of(leaf, leaf));
        assertTrue(new ConditionEngine(logger).evaluate(tree,
                new ConditionContext(player, Map.of(), resolver, (id, key) -> Optional.empty())));
        assertEquals(1, calls.get()); assertTrue(logs.isEmpty());
    }
}
