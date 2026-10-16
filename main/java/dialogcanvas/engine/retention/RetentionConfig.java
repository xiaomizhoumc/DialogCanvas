package dialogcanvas.engine.retention;

import java.util.Map;

/** Expiration grace, not player inactivity. Existing configurations remain disabled. */
public record RetentionConfig(boolean enabled, boolean onStartup, boolean daily,
                              boolean archive, int expiredDays, int batchSize) {
    public static RetentionConfig defaults() { return new RetentionConfig(false, true, true, false, 30, 20); }
    public RetentionConfig {
        if (expiredDays < 0 || expiredDays > 36500 || batchSize < 1 || batchSize > 200)
            throw new IllegalArgumentException("Invalid player-data-retention interval or batch size");
    }
    public static RetentionConfig parse(Object value) {
        if (value == null) return defaults();
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("player-data-retention must be an object");
        String mode = map.containsKey("mode") ? String.valueOf(map.get("mode")) : "dry-run";
        if (!mode.equals("dry-run") && !mode.equals("archive")) throw new IllegalArgumentException("Invalid retention mode");
        return new RetentionConfig(bool(map, "enabled", false), bool(map, "on-startup", true), bool(map, "daily", true),
                mode.equals("archive"), integer(map, "expired-days", 30), integer(map, "batch-size", 20));
    }
    private static boolean bool(Map<?, ?> map, String key, boolean fallback) {
        if (!map.containsKey(key)) return fallback;
        if (!(map.get(key) instanceof Boolean value)) throw new IllegalArgumentException("Invalid retention boolean: " + key);
        return value;
    }
    private static int integer(Map<?, ?> map, String key, int fallback) {
        if (!map.containsKey(key)) return fallback;
        if (!(map.get(key) instanceof Integer value)) throw new IllegalArgumentException("Invalid retention integer: " + key);
        return value;
    }
}
