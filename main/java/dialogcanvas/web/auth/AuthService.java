package dialogcanvas.web.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
import dialogcanvas.storage.AtomicFiles;
import dialogcanvas.config.PluginConfig;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** No Bukkit calls. BCrypt and first-run persistence run only on bounded worker threads. */
public final class AuthService implements AutoCloseable {
    private dialogcanvas.audit.AuditLogger audit = dialogcanvas.audit.AuditLogger.disabled();
    public void auditLogger(dialogcanvas.audit.AuditLogger audit) { this.audit = java.util.Objects.requireNonNull(audit); }
    public static final Duration SESSION_LIFETIME = Duration.ofHours(24);
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(1);
    private static final int MAX_FAILURES = 5;
    private static final int MAX_SESSIONS = 256;
    private final Clock clock;
    private final int bcryptCost;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().daemon().name("DCMenu-auth-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("DCMenu-auth-expiry").factory());
    private volatile String passwordHash;
    private Path configFile;
    private volatile boolean closed;
    private boolean initializationStarted;
    private Instant failureWindowStart;
    private int failures;
    private int inFlight;

    public AuthService() { this(Clock.systemUTC(), 12); }

    /** Clock/cost injection permits deterministic expiry tests without waiting or expensive test hashes. */
    public AuthService(Clock clock, int bcryptCost) {
        this.clock = java.util.Objects.requireNonNull(clock);
        if (bcryptCost < 4 || bcryptCost > 16) throw new IllegalArgumentException("Invalid BCrypt cost");
        this.bcryptCost = bcryptCost;
        expiry.scheduleAtFixedRate(this::purgeExpired, 1, 1, TimeUnit.MINUTES);
    }

    public record Session(String csrfToken, Instant expiresAt) {
        @Override public String toString() { return "Session[csrfToken=<redacted>, expiresAt=" + expiresAt + "]"; }
    }
    public record Login(String token, Session session) {
        @Override public String toString() { return "Login[token=<redacted>, session=" + session + "]"; }
    }

    /** Persist before printing. The callback is invoked exactly once only when an empty hash is initialized. */
    public synchronized CompletableFuture<Void> initialize(Path configFile, String configuredHash,
                                                           Consumer<String> initializationConsole) {
        if (initializationStarted) throw new IllegalStateException("Authentication already initialized");
        initializationStarted = true;
        this.configFile = configFile.toAbsolutePath().normalize();
        return submit(() -> {
            PluginConfig.validatePasswordHash(configuredHash == null ? "" : configuredHash);
            if (configuredHash != null && !configuredHash.isEmpty()) {
                synchronized (this) { requireActive(); passwordHash = configuredHash; }
                return null;
            }
            char[] password = randomValue(24).toCharArray();
            try {
                String hash = BCrypt.withDefaults().hashToString(bcryptCost, password);
                AtomicFiles.requireTarget(configFile);
                byte[] previousConfig = Files.readAllBytes(configFile);
                Map<?, ?> source = PluginConfig.parseConfigurationYaml(new String(previousConfig, StandardCharsets.UTF_8));
                Map<String, Object> document = new LinkedHashMap<>();
                source.forEach((key, value) -> document.put(String.valueOf(key), value));
                Object existing = document.get("password-hash");
                if (existing != null && !"".equals(existing)) {
                    throw new IllegalStateException("Password configuration changed during initialization");
                }
                document.put("password-hash", hash);
                DumperOptions options = new DumperOptions();
                options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
                options.setAllowUnicode(true);
                byte[] yaml = new Yaml(options).dump(document).getBytes(StandardCharsets.UTF_8);
                synchronized (this) {
                    requireActive();
                    AtomicFiles.writeIfUnchanged(configFile, yaml, previousConfig);
                    passwordHash = hash;
                    initializationConsole.accept(new String(password));
                }
                return null;
            } catch (java.io.IOException failure) {
                // Do not include configuration contents or generated password in exceptions.
                throw new java.io.UncheckedIOException("Failed to persist authentication configuration", failure);
            } finally {
                Arrays.fill(password, '\0');
            }
        });
    }

    public CompletableFuture<Login> login(String password) {
        return submit(() -> {
            reserveAttempt();
            boolean verified = false;
            char[] chars = password == null ? new char[0] : password.toCharArray();
            try {
                String hash = passwordHash;
                if (hash == null) throw unavailable();
                // BCrypt's 72-byte limit must never silently truncate two distinct passwords.
                if (chars.length > 0 && validPasswordEncoding(password)) {
                    verified = BCrypt.verifyer().verify(chars, hash).verified;
                }
                if (!verified) throw new AuthException(401, "INVALID_CREDENTIALS", "密码错误");
                synchronized (this) {
                    requireActive();
                    if (!java.util.Objects.equals(hash, passwordHash)) throw unauthorized();
                    purgeExpired();
                    if (sessions.size() >= MAX_SESSIONS) throw unavailable();
                    Session session = new Session(randomValue(32), clock.instant().plus(SESSION_LIFETIME));
                    String token;
                    do { token = randomValue(32); } while (sessions.putIfAbsent(token, session) != null);
                    return new Login(token, session);
                }
            } finally {
                Arrays.fill(chars, '\0');
                finishAttempt(verified);
            }
        });
    }

    public Session authenticate(String token) {
        if (closed || token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unauthorized();
        Session session = sessions.get(token);
        if (session == null) throw unauthorized();
        if (!clock.instant().isBefore(session.expiresAt())) {
            sessions.remove(token, session);
            throw unauthorized();
        }
        return session;
    }

    public void logout(String token) { sessions.remove(token); }

    /** Authenticate again on the worker and at commit; old in-flight logins cannot recreate sessions. */
    public CompletableFuture<Void> changePassword(String token, String currentPassword, String newPassword, String confirmation) {
        var operation = audit.begin(dialogcanvas.audit.AuditLogger.Event.PASSWORD_CHANGE, dialogcanvas.audit.AuditLogger.Origin.web(), "administrator-password");
        var result = this.<Void>submit(() -> {
          try {
            authenticate(token);
            if (newPassword == null || newPassword.codePointCount(0, newPassword.length()) < 8 || !validPasswordEncoding(newPassword))
                throw new AuthException(422, "INVALID_NEW_PASSWORD", "新密码至少 8 个字符，须为有效 Unicode 文本，UTF-8 长度不能超过 72 字节");
            if (!newPassword.equals(confirmation)) throw new AuthException(422, "PASSWORD_MISMATCH", "两次新密码不一致");
            reserveAttempt();
            boolean verified = false;
            char[] oldChars = currentPassword == null ? new char[0] : currentPassword.toCharArray();
            char[] newChars = newPassword.toCharArray();
            try {
                String previousHash = passwordHash;
                if (previousHash == null) throw unavailable();
                verified = oldChars.length > 0 && validPasswordEncoding(currentPassword)
                        && BCrypt.verifyer().verify(oldChars, previousHash).verified;
                if (!verified) throw new AuthException(403, "CURRENT_PASSWORD_INCORRECT", "当前密码不正确");
                String nextHash = BCrypt.withDefaults().hashToString(bcryptCost, newChars);
                synchronized (this) {
                    requireActive(); authenticate(token);
                    if (!previousHash.equals(passwordHash)) throw unauthorized();
                    persistChangedHash(previousHash, nextHash);
                    passwordHash = nextHash;
                    sessions.clear();
                    operation.finish(dialogcanvas.audit.AuditLogger.Result.SUCCESS, "PASSWORD_CHANGED");
                }
                return null;
            } finally {
                Arrays.fill(oldChars, '\0'); Arrays.fill(newChars, '\0'); finishAttempt(verified);
            }
          } catch (RuntimeException failure) {
              operation.finish(dialogcanvas.audit.AuditLogger.Result.FAILED, failure instanceof AuthException known ? known.code() : "PASSWORD_CHANGE_FAILED"); throw failure;
          }
        });
        result.whenComplete((ignored, failure) -> { if (failure != null) operation.finish(dialogcanvas.audit.AuditLogger.Result.FAILED,
                failure instanceof AuthException known ? known.code() : "AUTH_UNAVAILABLE"); });
        return result.copy();
    }

    private void persistChangedHash(String expected, String next) {
        try {
            AtomicFiles.requireTarget(configFile);
            byte[] previousConfig = Files.readAllBytes(configFile);
            Map<?, ?> source = PluginConfig.parseConfigurationYaml(new String(previousConfig, StandardCharsets.UTF_8));
            if (!expected.equals(source.get("password-hash")))
                throw new AuthException(409, "PASSWORD_CONFIG_CHANGED", "配置中的密码哈希已被修改，请核查配置后重启服务");
            Map<String, Object> document = new LinkedHashMap<>();
            source.forEach((key, value) -> document.put(String.valueOf(key), value));
            document.put("password-hash", next);
            DumperOptions options = new DumperOptions(); options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); options.setAllowUnicode(true);
            AtomicFiles.writeIfUnchanged(configFile, new Yaml(options).dump(document).getBytes(StandardCharsets.UTF_8), previousConfig);
        } catch (AuthException failure) { throw failure; }
        catch (Exception failure) { throw new AuthException(503, "PASSWORD_SAVE_FAILED", "密码配置保存失败，密码未修改，请检查配置文件"); }
    }

    private static boolean validPasswordEncoding(String password) {
        // Java's default UTF-8 encoder replaces isolated surrogates, aliasing distinct passwords.
        return StandardCharsets.UTF_8.newEncoder().canEncode(password)
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    public boolean validCsrf(Session session, String supplied) {
        return supplied != null && supplied.length() == 43 && MessageDigest.isEqual(
                session.csrfToken().getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }

    private synchronized void reserveAttempt() {
        requireActive();
        Instant now = clock.instant();
        if (failureWindowStart == null || !now.isBefore(failureWindowStart.plus(FAILURE_WINDOW))) {
            failureWindowStart = now;
            failures = 0;
        }
        // Account for pending verifications so parallel requests cannot bypass the limit.
        if (failures + inFlight >= MAX_FAILURES) {
            throw new AuthException(429, "LOGIN_RATE_LIMITED", "登录失败次数过多，请稍后重试");
        }
        inFlight++;
    }

    private synchronized void finishAttempt(boolean success) {
        inFlight--;
        if (success) failures = 0; else failures++;
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    private String randomValue(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private <T> CompletableFuture<T> submit(Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        pending.add(result);
        result.whenComplete((value, failure) -> pending.remove(result));
        try {
            if (closed) throw new RejectedExecutionException();
            workers.execute(() -> {
                if (result.isDone()) return;
                try {
                    T value = task.get();
                    // Cancellation can race an in-progress verification. Do not retain an undelivered login.
                    if (!result.complete(value) && value instanceof Login login)
                        sessions.remove(login.token(), login.session());
                }
                catch (Exception failure) { result.completeExceptionally(failure); }
            });
        } catch (RejectedExecutionException failure) { result.completeExceptionally(unavailable()); }
        return result;
    }

    private void requireActive() { if (closed) throw unavailable(); }
    private static AuthException unavailable() { return new AuthException(503, "AUTH_UNAVAILABLE", "认证服务不可用"); }
    private static AuthException unauthorized() { return new AuthException(401, "AUTH_REQUIRED", "会话不存在或已过期，请登录"); }

    @Override public synchronized void close() {
        closed = true;
        sessions.clear();
        passwordHash = null;
        expiry.shutdownNow();
        workers.shutdownNow();
        pending.forEach(future -> future.completeExceptionally(unavailable()));
    }
}
