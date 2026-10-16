package dialogcanvas.engine.retention;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Pure model inspection. Unknown/legacy data is never inferred from filesystem timestamps. */
public final class LimitRetentionPlan {
    public static final int MAX_BYTES = 512 * 1024;
    private static final Set<String> FIELDS = Set.of("count", "resetMode", "resetAt", "cooldownUntil", "lastCommittedAt");
    public record Entry(String key, String reason, boolean candidate) {}
    public record Plan(byte[] after, List<Entry> entries) {
        public int candidates() { return (int) entries.stream().filter(Entry::candidate).count(); }
    }
    private LimitRetentionPlan() {}
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(byte[] content) {
        if (content.length > MAX_BYTES) throw new IllegalArgumentException("FILE_TOO_LARGE");
        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0); options.setNestingDepthLimit(32); options.setCodePointLimit(MAX_BYTES);
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content)).toString(); }
        catch (java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("INVALID_UTF8"); }
        Object value = new Yaml(new SafeConstructor(options)).load(text);
        if (!(value instanceof Map<?, ?> root) || root.keySet().stream().anyMatch(k -> !(k instanceof String)))
            throw new IllegalArgumentException("UNKNOWN_DOCUMENT");
        return (Map<String, Object>) root;
    }
    public static Plan inspect(byte[] before, Instant now, int expiredDays) {
        var root = parse(before);
        if (!(root.get("limits") instanceof Map<?, ?> limits)) return skipped(before, "UNKNOWN_LIMITS");
        // A reservation anywhere in this file may belong to an interrupted multi-rule purchase.
        if (limits.values().stream().anyMatch(v -> v instanceof Map<?, ?> state && (state.containsKey("pendingQuantity") || state.containsKey("pendingSince"))))
            return skipped(before, "PENDING_RESERVATION");
        var retained = new LinkedHashMap<Object, Object>(limits);
        List<Entry> entries = new ArrayList<>();
        Instant cutoff = now.minus(Duration.ofDays(expiredDays));
        for (var entry : limits.entrySet()) {
            boolean knownKey = entry.getKey() instanceof String name && name.matches("[A-Za-z0-9_.:/-]{1,200}");
            String key = knownKey ? (String) entry.getKey() : "<unknown-key>";
            String reason = knownKey ? eligibility(entry.getValue(), now, cutoff) : "UNKNOWN_ENTRY";
            boolean eligible = reason.equals("EXPIRED");
            entries.add(new Entry(key, reason, eligible));
            if (eligible) retained.remove(entry.getKey());
        }
        if (entries.isEmpty()) entries.add(new Entry("", "EMPTY_LIMITS", false));
        boolean changes = retained.size() != limits.size();
        if (changes) root.put("limits", retained);
        return new Plan(changes ? new Yaml().dump(root).getBytes(StandardCharsets.UTF_8) : before, List.copyOf(entries));
    }
    private static Plan skipped(byte[] before, String reason) { return new Plan(before, List.of(new Entry("", reason, false))); }
    private static String eligibility(Object value, Instant now, Instant cutoff) {
        if (!(value instanceof Map<?, ?> state)) return "UNKNOWN_ENTRY";
        if ("NEVER".equals(state.get("resetMode"))) return "PERMANENT";
        if (!FIELDS.containsAll(state.keySet())) return "UNKNOWN_FIELDS";
        if (!(state.get("resetMode") instanceof String mode) || !Set.of("DAILY", "WEEKLY", "MONTHLY", "TIMED").contains(mode)) return "UNKNOWN_RESET_MODE";
        Object count = state.get("count");
        if (!(count instanceof Number) || !count.toString().matches("[0-9]+")) return "INVALID_COUNT";
        try { Long.parseLong(count.toString()); } catch (NumberFormatException error) { return "INVALID_COUNT"; }
        try {
            Instant reset = time(state, "resetAt"), cooldown = time(state, "cooldownUntil"), committed = time(state, "lastCommittedAt");
            if (committed.isAfter(now) || !reset.isAfter(committed) || cooldown.isBefore(committed)) return "UNRELIABLE_TIME";
            if (reset.isAfter(now)) return "ACTIVE_COUNT";
            if (cooldown.isAfter(now)) return "ACTIVE_COOLDOWN";
            Instant expires = reset.isAfter(cooldown) ? reset : cooldown;
            return expires.isAfter(cutoff) ? "RETENTION_GRACE" : "EXPIRED";
        } catch (RuntimeException error) { return "MISSING_OR_INVALID_TIME"; }
    }
    private static Instant time(Map<?, ?> state, String key) {
        if (!(state.get(key) instanceof String text)) throw new IllegalArgumentException();
        return Instant.parse(text);
    }
}
