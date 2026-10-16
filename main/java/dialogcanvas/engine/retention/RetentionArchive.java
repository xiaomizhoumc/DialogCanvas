package dialogcanvas.engine.retention;

import dialogcanvas.model.MenuJson;
import dialogcanvas.storage.AtomicFiles;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Immutable, self-contained before/after evidence. No paths are accepted from backup content. */
final class RetentionArchive {
    record Backup(UUID id, UUID player, byte[] before, byte[] after) {}
    private final Path root;
    private final AtomicFiles.Replacement writer;
    RetentionArchive(Path data, AtomicFiles.Replacement writer) {
        root = data.toAbsolutePath().normalize().resolve("retention-backups"); this.writer = writer;
    }
    Backup create(UUID player, byte[] before, byte[] after, Instant now, String kind) throws Exception {
        LimitRetentionPlan.parse(before); LimitRetentionPlan.parse(after);
        AtomicFiles.createDirectories(root);
        UUID id = UUID.randomUUID(); Path directory = root.resolve(id.toString());
        // Exclusive directory creation is intentional: even atomic REPLACE must never touch an older backup.
        Files.createDirectory(directory); AtomicFiles.requireDirectory(directory);
        var json = MenuJson.createMapper(); var record = json.createObjectNode();
        record.put("version", 1).put("backupId", id.toString()).put("playerId", player.toString())
                .put("created", now.toString()).put("kind", kind)
                .put("before", Base64.getEncoder().encodeToString(before)).put("beforeSha256", hash(before))
                .put("after", Base64.getEncoder().encodeToString(after)).put("afterSha256", hash(after));
        Path file = directory.resolve("record.json"); AtomicFiles.requireUnchanged(file, null);
        writer.write(file, json.writeValueAsBytes(record));
        Backup checked = read(id);
        if (!checked.player.equals(player) || !Arrays.equals(before, checked.before) || !Arrays.equals(after, checked.after))
            throw new IOException("BACKUP_VERIFICATION_FAILED");
        return checked;
    }
    Backup read(UUID id) throws Exception {
        Path directory = root.resolve(id.toString()); AtomicFiles.requireDirectory(root); AtomicFiles.requireDirectory(directory);
        Path file = directory.resolve("record.json"); AtomicFiles.requireTarget(file);
        if (Files.size(file) > 2 * 1024 * 1024) throw new IOException("BACKUP_TOO_LARGE");
        var value = MenuJson.createMapper().readTree(Files.readAllBytes(file));
        var fields = new HashSet<String>(); value.properties().forEach(e -> fields.add(e.getKey()));
        if (!value.isObject() || !fields.equals(Set.of("version", "backupId", "playerId", "created", "kind", "before", "beforeSha256", "after", "afterSha256"))
                || !value.path("version").isIntegralNumber() || value.path("version").asInt() != 1
                || !id.toString().equals(value.path("backupId").asString())
                || !Set.of("cleanup", "restore").contains(value.path("kind").asString())) throw new IOException("INVALID_BACKUP");
        String owner = value.path("playerId").asString(); UUID player = UUID.fromString(owner);
        if (!player.toString().equals(owner)) throw new IOException("INVALID_BACKUP_PLAYER");
        Instant.parse(value.path("created").asString());
        byte[] before = Base64.getDecoder().decode(value.path("before").asString()), after = Base64.getDecoder().decode(value.path("after").asString());
        if (!hash(before).equals(value.path("beforeSha256").asString()) || !hash(after).equals(value.path("afterSha256").asString())) throw new IOException("BACKUP_DIGEST_MISMATCH");
        LimitRetentionPlan.parse(before); LimitRetentionPlan.parse(after);
        return new Backup(id, player, before, after);
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
