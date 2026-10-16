package dialogcanvas.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PluginConfigTest {
    @TempDir Path root;
    @Test void configTextDoesNotExposeHashAndMalformedYamlDoesNotExposeSourceOrCause() throws Exception {
        String hash = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(4, "fixture-only".toCharArray());
        var config = new PluginConfig(8080, "127.0.0.1", hash, root.resolve("data"));
        assertFalse(config.toString().contains(hash)); assertTrue(config.toString().contains("<redacted>"));
        for (String yaml : java.util.List.of("password-hash: ['" + hash + "'\n", "password-hash: '" + hash + "'\npassword-hash: '" + hash + "'\n")) {
            Files.writeString(root.resolve("config.yml"), yaml);
            var failure = assertThrows(IllegalArgumentException.class, () -> PluginConfig.load(root));
            var trace = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(trace));
            assertFalse(trace.toString().contains(hash)); assertNull(failure.getCause());
            assertTrue(failure.getMessage().contains("config.yml"));
        }
    }
    @Test void hashCostsAndFormatAreValidatedWithoutRehashingOrDisplayingInput() {
        String hash = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(4, "fixture-only".toCharArray());
        for (String prefix : java.util.List.of("$2a$", "$2b$", "$2y$"))
            assertDoesNotThrow(() -> PluginConfig.validatePasswordHash(prefix + hash.substring(4)));
        assertDoesNotThrow(() -> PluginConfig.validatePasswordHash(hash.substring(0, 4) + "16" + hash.substring(6)));
        for (String invalid : java.util.List.of("invalid-sensitive-fixture", hash.substring(0, 4) + "31" + hash.substring(6), hash.substring(0, 4) + "03" + hash.substring(6))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> PluginConfig.validatePasswordHash(invalid));
            assertFalse(failure.toString().contains(invalid)); assertNull(failure.getCause());
        }
    }
    @Test void workerLoadsDefaultsWithoutAnyBukkitServer() throws Exception {
        var config = CompletableFuture.supplyAsync(() -> {
            try { return PluginConfig.load(root); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }).get(5, TimeUnit.SECONDS);
        assertEquals("127.0.0.1", config.bindAddress()); assertEquals(8080, config.port());
        assertFalse(config.retention().enabled()); assertFalse(config.retention().archive());
        assertTrue(Files.isRegularFile(root.resolve("config.yml")));
    }
    @Test void retentionConfigIsStrictOptInAndOldConfigRemainsDisabled() throws Exception {
        String base = "port: 8080\nbind-address: 127.0.0.1\npassword-hash: ''\ndata-directory: data\n";
        Files.writeString(root.resolve("config.yml"), base);
        assertFalse(PluginConfig.load(root).retention().enabled());
        Files.writeString(root.resolve("config.yml"), base + "player-data-retention:\n  enabled: true\n  mode: dry-run\n  batch-size: 3\n  expired-days: 60\n");
        var configured = PluginConfig.load(root).retention(); assertTrue(configured.enabled()); assertFalse(configured.archive());
        assertEquals(3, configured.batchSize()); assertEquals(60, configured.expiredDays());
        for (String invalid : java.util.List.of("  enabled: 'true'", "  mode: delete", "  expired-days: -1", "  batch-size: 0")) {
            Files.writeString(root.resolve("config.yml"), base + "player-data-retention:\n" + invalid + "\n");
            assertThrows(IllegalArgumentException.class, () -> PluginConfig.load(root));
        }
    }
    @Test void unrelatedSerializedLookingDataDoesNotInstantiateBukkitObjectsOnWorker() throws Exception {
        Files.writeString(root.resolve("config.yml"), """
                port: 8080
                bind-address: 127.0.0.1
                password-hash: ''
                data-directory: data
                extra:
                  ==: org.bukkit.inventory.ItemStack
                  type: DIAMOND_SWORD
                """);
        var config = CompletableFuture.supplyAsync(() -> {
            try { return PluginConfig.load(root); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }).get(5, TimeUnit.SECONDS);
        assertEquals(root.resolve("data").toAbsolutePath(), config.dataDirectory());
    }
}
