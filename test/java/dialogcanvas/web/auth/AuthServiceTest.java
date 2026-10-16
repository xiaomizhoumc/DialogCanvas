package dialogcanvas.web.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.ArrayList;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {
    @TempDir Path directory;
    static final String PASSWORD = "test-only-password";
    static String hash() { return BCrypt.withDefaults().hashToString(4, PASSWORD.toCharArray()); }

    private AuthService configured() throws Exception {
        String hash = hash();
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "port: 9876\nbind-address: '127.0.0.1'\ndata-directory: data\npassword-hash: '" + hash + "'\ncustom-setting: preserved\n");
        var service = new AuthService(new MutableClock(), 4);
        service.initialize(file, hash, ignored -> fail()).join(); return service;
    }
    @Test void passwordChangePersistsOnlyHashInvalidatesAllSessionsAndSurvivesRestart() throws Exception {
        String next = "new-test-password";
        try (var auth = configured()) {
            var one = auth.login(PASSWORD).join(); var two = auth.login(PASSWORD).join();
            auth.changePassword(one.token(), PASSWORD, next, next).join();
            assertThrows(AuthException.class, () -> auth.authenticate(one.token()));
            assertThrows(AuthException.class, () -> auth.authenticate(two.token()));
            assertThrows(CompletionException.class, () -> auth.login(PASSWORD).join());
            auth.authenticate(auth.login(next).join().token());
            String yaml = Files.readString(directory.resolve("config.yml")); assertFalse(yaml.contains(next)); assertFalse(yaml.contains(PASSWORD));
            var document = new org.yaml.snakeyaml.Yaml().<java.util.Map<String, Object>>load(yaml);
            assertEquals(9876, document.get("port")); assertEquals("preserved", document.get("custom-setting"));
            String stored = (String) document.get("password-hash"); assertTrue(BCrypt.verifyer().verify(next.toCharArray(), stored).verified);
            try (var restart = new AuthService(new MutableClock(), 4)) {
                restart.initialize(directory.resolve("config.yml"), stored, ignored -> fail()).join();
                restart.authenticate(restart.login(next).join().token()); assertThrows(CompletionException.class, () -> restart.login(PASSWORD).join());
            }
        }
    }
    @Test void wrongPasswordAndInvalidNewPasswordsKeepHashAndSessions() throws Exception {
        try (var auth = configured()) {
            var login = auth.login(PASSWORD).join(); String before = Files.readString(directory.resolve("config.yml"));
            var wrong = assertInstanceOf(AuthException.class, assertThrows(CompletionException.class,
                () -> auth.changePassword(login.token(), "wrong", "new-password", "new-password").join()).getCause());
            assertEquals("CURRENT_PASSWORD_INCORRECT", wrong.code());
            for (String next : java.util.List.of("short", "我".repeat(25), "😀".repeat(7))) {
                var invalid = assertInstanceOf(AuthException.class, assertThrows(CompletionException.class,
                    () -> auth.changePassword(login.token(), PASSWORD, next, next).join()).getCause()); assertEquals(422, invalid.status());
            }
            var mismatch = assertInstanceOf(AuthException.class, assertThrows(CompletionException.class,
                () -> auth.changePassword(login.token(), PASSWORD, "new-password", "different").join()).getCause()); assertEquals("PASSWORD_MISMATCH", mismatch.code());
            assertEquals(before, Files.readString(directory.resolve("config.yml"))); auth.authenticate(login.token());
            auth.authenticate(auth.login(PASSWORD).join().token());
        }
    }
    @Test void failedPersistenceDoesNotChangeInMemoryPasswordOrInvalidateSessions() throws Exception {
        try (var auth = configured()) {
            var login = auth.login(PASSWORD).join(); Path file = directory.resolve("config.yml"); Files.delete(file); Files.createDirectory(file);
            var failure = assertInstanceOf(AuthException.class, assertThrows(CompletionException.class,
                () -> auth.changePassword(login.token(), PASSWORD, "new-password", "new-password").join()).getCause());
            assertEquals("PASSWORD_SAVE_FAILED", failure.code()); auth.authenticate(login.token()); auth.authenticate(auth.login(PASSWORD).join().token());
        }
    }
    @Test void redirectedConfigCannotChangePasswordOrCreateDefaults() throws Exception {
        Path actual = directory.resolve("actual"); Files.createDirectories(actual);
        Path redirect = directory.resolve("redirect"); dialogcanvas.storage.PersistenceTestLinks.redirectDirectory(redirect, actual);
        String stored = hash(); String text = "password-hash: '" + stored + "'\n"; Files.writeString(actual.resolve("config.yml"), text);
        assertThrows(java.io.IOException.class, () -> dialogcanvas.config.PluginConfig.load(redirect));
        try (var auth = new AuthService(new MutableClock(), 4)) {
            auth.initialize(redirect.resolve("config.yml"), stored, ignored -> fail()).join();
            var login = auth.login(PASSWORD).join();
            var failure = assertInstanceOf(AuthException.class, assertThrows(CompletionException.class,
                    () -> auth.changePassword(login.token(), PASSWORD, "new-password", "new-password").join()).getCause());
            assertEquals("PASSWORD_SAVE_FAILED", failure.code());
            auth.authenticate(login.token()); assertEquals(text, Files.readString(actual.resolve("config.yml")));
        }
    }
    @Test void externallyChangedHashRefusesOverwrite() throws Exception {
        try (var auth = configured()) {
            var login = auth.login(PASSWORD).join(); Path file = directory.resolve("config.yml"); String external = "password-hash: '" + hash() + "'\n"; Files.writeString(file, external);
            var failure = assertInstanceOf(AuthException.class, assertThrows(CompletionException.class,
                () -> auth.changePassword(login.token(), PASSWORD, "new-password", "new-password").join()).getCause());
            assertEquals(409, failure.status()); assertEquals(external, Files.readString(file)); auth.authenticate(login.token());
        }
    }
    @Test void concurrentChangesCannotOverwriteEachOther() throws Exception {
        try (var auth = configured()) {
            var login = auth.login(PASSWORD).join();
            var a = auth.changePassword(login.token(), PASSWORD, "new-password-A", "new-password-A");
            var b = auth.changePassword(login.token(), PASSWORD, "new-password-B", "new-password-B");
            boolean first = a.handle((value, failure) -> failure == null).join(); boolean second = b.handle((value, failure) -> failure == null).join();
            assertNotEquals(first, second); auth.authenticate(auth.login(first ? "new-password-A" : "new-password-B").join().token());
        }
    }
    @Test void inFlightOldPasswordLoginsCannotRestoreValidSessionsAfterChange() throws Exception {
        try (var auth = configured()) {
            var login = auth.login(PASSWORD).join(); var a = auth.login(PASSWORD); var b = auth.login(PASSWORD);
            auth.changePassword(login.token(), PASSWORD, "new-password", "new-password").join();
            for (var request : java.util.List.of(a, b)) request.handle((value, failure) -> {
                if (value != null) assertThrows(AuthException.class, () -> auth.authenticate(value.token()));
                return null;
            }).join();
            auth.authenticate(auth.login("new-password").join().token());
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-11T00:00:00Z"));
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    }

    @Test void bootstrapPersistsOnlyHashOffMainThreadAndNeverPrintsOnRestart() throws Exception {
        MockBukkit.mock();
        try {
            assertTrue(org.bukkit.Bukkit.isPrimaryThread());
            Thread main = Thread.currentThread();
            var printed = new ArrayList<String>();
            var printingThread = new AtomicReference<Thread>();
            Path file = directory.resolve("config.yml");
            Files.writeString(file, "port: 8080\nbind-address: '127.0.0.1'\ndata-directory: data\n");
            var config = dialogcanvas.config.PluginConfig.load(directory);
            assertEquals("", config.passwordHash());
            try (var auth = new AuthService(new MutableClock(), 4)) {
                auth.initialize(file, config.passwordHash(), password -> {
                    printingThread.set(Thread.currentThread()); printed.add(password);
                    assertTrue(Files.exists(file));
                }).join();
                assertNotSame(main, printingThread.get());
                assertEquals(1, printed.size());
                String yaml = Files.readString(file);
                assertFalse(yaml.contains(printed.getFirst()));
                var document = new org.yaml.snakeyaml.Yaml().<java.util.Map<String, Object>>load(yaml);
                String stored = (String) document.get("password-hash");
                assertTrue(stored.startsWith("$2"));
                assertTrue(BCrypt.verifyer().verify(printed.getFirst().toCharArray(), stored).verified);
                assertEquals(8080, document.get("port"));
                auth.authenticate(auth.login(printed.getFirst()).join().token());
                try (var restarted = new AuthService(new MutableClock(), 4)) {
                    restarted.initialize(file, stored, ignored -> fail("Must not print on restart")).join();
                    restarted.authenticate(restarted.login(printed.getFirst()).join().token());
                }
            }
        } finally { MockBukkit.unmock(); }
    }

    @Test void failedPersistenceNeverPrintsGeneratedPassword() {
        var output = new ArrayList<String>();
        try (var auth = new AuthService(new MutableClock(), 4)) {
            assertThrows(CompletionException.class, () -> auth.initialize(directory.resolve("missing.yml"), "", output::add).join());
            assertTrue(output.isEmpty());
            var failure = assertInstanceOf(AuthException.class, assertThrows(CompletionException.class,
                    () -> auth.login(PASSWORD).join()).getCause());
            assertEquals("AUTH_UNAVAILABLE", failure.code());
        }
    }

    @Test void invalidConfiguredHashCannotMakeAuthReadyAndNeverChangesConfig() throws Exception {
        Path file = directory.resolve("config.yml");
        String original = "password-hash: invalid-sensitive-fixture\n"; Files.writeString(file, original);
        for (String hash : java.util.List.of("invalid-sensitive-fixture", hash().substring(0, 4) + "31" + hash().substring(6))) {
            try (var auth = new AuthService(new MutableClock(), 4)) {
                var error = assertThrows(CompletionException.class, () -> auth.initialize(file, hash, ignored -> fail()).join());
                assertFalse(error.toString().contains(hash));
                var unavailable = assertInstanceOf(AuthException.class, assertThrows(CompletionException.class,
                        () -> auth.login(PASSWORD).join()).getCause());
                assertEquals(503, unavailable.status()); assertEquals(original, Files.readString(file));
            }
        }
    }

    @Test void bootstrapYamlFailureCannotLeakSourceAndNeverPrintsOrEnablesAuth() throws Exception {
        Path file = directory.resolve("config.yml");
        String secretMarker = "fixture-sensitive-marker";
        String original = "password-hash: ['" + secretMarker + "'\n"; Files.writeString(file, original);
        try (var auth = new AuthService(new MutableClock(), 4)) {
            var error = assertThrows(CompletionException.class, () -> auth.initialize(file, "", ignored -> fail()).join());
            var trace = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace));
            assertFalse(trace.toString().contains(secretMarker)); assertEquals(original, Files.readString(file));
            var unavailable = assertInstanceOf(AuthException.class, assertThrows(CompletionException.class,
                    () -> auth.login(PASSWORD).join()).getCause()); assertEquals(503, unavailable.status());
        }
    }

    @Test void productionBootstrapUsesCost12AndExistingHashIsByteForByteUnchangedOnRestart() throws Exception {
        Path file = directory.resolve("config.yml"); Files.writeString(file, "password-hash: ''\n");
        var printed = new AtomicReference<String>();
        try (var auth = new AuthService()) { auth.initialize(file, "", printed::set).join(); }
        byte[] before = Files.readAllBytes(file);
        String stored = (String) dialogcanvas.config.PluginConfig.parseConfigurationYaml(new String(before, java.nio.charset.StandardCharsets.UTF_8)).get("password-hash");
        assertEquals(12, BCrypt.Version.VERSION_2B.parser.parse(stored.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).cost);
        assertTrue(printed.get().matches("[A-Za-z0-9_-]{32}"));
        try (var auth = new AuthService()) {
            auth.initialize(file, stored, ignored -> fail()).join();
            auth.authenticate(auth.login(printed.get()).join().token());
            assertArrayEquals(before, Files.readAllBytes(file));
        }
    }

    @Test void sessionCapacityIsBoundedAndExpiredSessionsAreReclaimedWithoutSensitiveText() {
        var clock = new MutableClock();
        try (var auth = new AuthService(clock, 4)) {
            auth.initialize(directory.resolve("unused.yml"), hash(), ignored -> fail()).join();
            var first = auth.login(PASSWORD).join();
            assertFalse(first.toString().contains(first.token()));
            assertFalse(first.toString().contains(first.session().csrfToken()));
            for (int i = 1; i < 256; i++) auth.login(PASSWORD).join();
            var full = assertInstanceOf(AuthException.class, assertThrows(CompletionException.class,
                    () -> auth.login(PASSWORD).join()).getCause()); assertEquals(503, full.status());
            auth.logout(first.token()); auth.authenticate(auth.login(PASSWORD).join().token());
            clock.advance(Duration.ofHours(24));
            auth.authenticate(auth.login(PASSWORD).join().token());
            assertThrows(AuthException.class, () -> auth.authenticate(first.token()));
        }
    }

    @Test void canceledInFlightLoginCannotLeaveAnUndeliveredSession() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var once = new java.util.concurrent.atomic.AtomicBoolean(true);
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() {
                if (once.compareAndSet(true, false)) {
                    entered.countDown();
                    try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timeout"); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Test gate interrupted"); }
                }
                return Instant.parse("2026-10-15T00:00:00Z");
            }
        };
        try (var auth = new AuthService(clock, 4)) {
            auth.initialize(directory.resolve("unused.yml"), hash(), ignored -> fail()).join();
            var field = AuthService.class.getDeclaredField("workers"); field.setAccessible(true);
            var workers = (java.util.concurrent.ThreadPoolExecutor) field.get(auth);
            var sessionField = AuthService.class.getDeclaredField("sessions"); sessionField.setAccessible(true);
            try {
                var login = auth.login(PASSWORD);
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(login.cancel(true)); release.countDown();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (workers.getActiveCount() != 0 && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(0, workers.getActiveCount(), "Canceled verification must finish on its worker");
                assertTrue(((java.util.Map<?, ?>) sessionField.get(auth)).isEmpty(), "Canceled login must not retain a session");
                auth.authenticate(auth.login(PASSWORD).join().token());
            } finally { release.countDown(); }
        }
    }

    @Test void exactUtf8BoundaryAcceptsMultibyteAndSupplementaryCharactersWithoutTruncation() throws Exception {
        for (String next : java.util.List.of("我".repeat(24), "😀".repeat(18), "x".repeat(72))) {
            try (var auth = configured()) {
                var login = auth.login(PASSWORD).join();
                auth.changePassword(login.token(), PASSWORD, next, next).join();
                auth.authenticate(auth.login(next).join().token());
                var tooLong = assertInstanceOf(AuthException.class, assertThrows(CompletionException.class,
                        () -> auth.login(next + "x").join()).getCause()); assertEquals("INVALID_CREDENTIALS", tooLong.code());
            }
        }
    }

    @Test void isolatedSurrogatesMustNotAliasValidPasswordsOrBePersisted() throws Exception {
        // Reproduce the encoding ambiguity in the fixed library using test credentials only.
        String malformed = "fixture-" + '\uD800';
        String replacement = new String(malformed.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
        String aliased = BCrypt.withDefaults().hashToString(4, malformed.toCharArray());
        assertTrue(BCrypt.verifyer().verify(replacement.toCharArray(), aliased).verified);
        try (var auth = configured()) {
            var login = auth.login(PASSWORD).join(); String before = Files.readString(directory.resolve("config.yml"));
            for (String next : java.util.List.of(malformed, "fixture-" + '\uDC00')) {
                var invalid = assertInstanceOf(AuthException.class, assertThrows(CompletionException.class,
                        () -> auth.changePassword(login.token(), PASSWORD, next, next).join()).getCause());
                assertEquals("INVALID_NEW_PASSWORD", invalid.code());
            }
            assertEquals(before, Files.readString(directory.resolve("config.yml"))); auth.authenticate(login.token());
        }
        try (var auth = new AuthService(new MutableClock(), 4)) {
            auth.initialize(directory.resolve("unused.yml"), aliased, ignored -> fail()).join();
            var invalid = assertInstanceOf(AuthException.class, assertThrows(CompletionException.class,
                    () -> auth.login(malformed).join()).getCause()); assertEquals("INVALID_CREDENTIALS", invalid.code());
        }
    }

    @Test void expiresExactly24HoursAfterCreationWithoutSlidingExpiry() {
        var clock = new MutableClock();
        try (var auth = new AuthService(clock, 4)) {
            auth.initialize(directory.resolve("unused.yml"), hash(), ignored -> fail()).join();
            var login = auth.login(PASSWORD).join();
            assertEquals(clock.instant().plus(Duration.ofHours(24)), login.session().expiresAt());
            clock.advance(Duration.ofHours(24).minusSeconds(1));
            assertEquals(login.session(), auth.authenticate(login.token()));
            clock.advance(Duration.ofSeconds(1));
            assertEquals(401, assertThrows(AuthException.class, () -> auth.authenticate(login.token())).status());
        }
    }

    @Test void logoutAndCloseImmediatelyInvalidateTokensAndRestartDoesNotRestoreThem() {
        String hash = hash();
        var auth = new AuthService(new MutableClock(), 4);
        auth.initialize(directory.resolve("unused.yml"), hash, ignored -> fail()).join();
        var first = auth.login(PASSWORD).join();
        var second = auth.login(PASSWORD).join();
        assertNotEquals(first.token(), second.token());
        assertTrue(auth.validCsrf(first.session(), first.session().csrfToken()));
        assertFalse(auth.validCsrf(first.session(), second.session().csrfToken()));
        auth.logout(first.token());
        assertThrows(AuthException.class, () -> auth.authenticate(first.token()));
        auth.authenticate(second.token());
        auth.close();
        assertThrows(AuthException.class, () -> auth.authenticate(second.token()));
        try (var restart = new AuthService(new MutableClock(), 4)) {
            restart.initialize(directory.resolve("unused.yml"), hash, ignored -> fail()).join();
            assertThrows(AuthException.class, () -> restart.authenticate(second.token()));
            restart.authenticate(restart.login(PASSWORD).join().token());
        }
    }

    @Test void uniformFailuresAreRateLimitedAndResetAfterOneMinute() {
        var clock = new MutableClock();
        try (var auth = new AuthService(clock, 4)) {
            auth.initialize(directory.resolve("unused.yml"), hash(), ignored -> fail()).join();
            for (String password : java.util.List.of("wrong", "", "x".repeat(73), "我".repeat(25), "wrong-again")) {
                var cause = assertInstanceOf(AuthException.class,
                        assertThrows(CompletionException.class, () -> auth.login(password).join()).getCause());
                assertEquals(401, cause.status()); assertEquals("INVALID_CREDENTIALS", cause.code());
            }
            var blocked = assertInstanceOf(AuthException.class,
                    assertThrows(CompletionException.class, () -> auth.login(PASSWORD).join()).getCause());
            assertEquals(429, blocked.status());
            clock.advance(Duration.ofMinutes(1));
            auth.authenticate(auth.login(PASSWORD).join().token());
        }
    }

    @Test void parallelLoginsHaveUniqueTokensAndCanAllBeInvalidated() {
        try (var auth = new AuthService(new MutableClock(), 4)) {
            auth.initialize(directory.resolve("unused.yml"), hash(), ignored -> fail()).join();
            var attempts = java.util.stream.IntStream.range(0, 12).mapToObj(i -> auth.login(PASSWORD)).toList();
            var tokens = attempts.stream().map(future -> future.join().token()).toList();
            assertEquals(12, new java.util.HashSet<>(tokens).size());
            tokens.forEach(auth::authenticate);
            auth.close();
            tokens.forEach(token -> assertThrows(AuthException.class, () -> auth.authenticate(token)));
        }
    }

    @Test void shutdownCompletesQueuedRequestsAndCannotLeaveValidSessions() {
        var auth = new AuthService(new MutableClock(), 4);
        auth.initialize(directory.resolve("unused.yml"), hash(), ignored -> fail()).join();
        var attempts = java.util.stream.IntStream.range(0, 100).mapToObj(i -> auth.login(PASSWORD)).toList();
        auth.close();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            for (var attempt : attempts) {
                attempt.handle((login, failure) -> {
                    if (login != null) assertThrows(AuthException.class, () -> auth.authenticate(login.token()));
                    return null;
                }).join();
            }
        });
    }
}
