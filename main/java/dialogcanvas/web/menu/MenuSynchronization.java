package dialogcanvas.web.menu;

import dialogcanvas.model.MenuJson;
import dialogcanvas.storage.*;
import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Optional;

/** Durable hashes and an export intent protect manual edits across interrupted exports. */
final class MenuSynchronization {
    private final MenuPersistence files;
    MenuSynchronization(MenuPersistence files) { this.files = files; }
    private JsonNode baseline(String id) throws IOException {
        Optional<byte[]> value = files.readAuxiliary(MenuPersistence.Auxiliary.SYNC, id);
        if (value.isEmpty()) return null;
        try {
            JsonNode node = MenuDocuments.decode(value.get());
            if (!hash(node.get("jsonHash"), false) || !hash(node.get("yamlHash"), true)
                    || (node.has("pending") && !hash(node.get("pending"), false))) throw new IllegalArgumentException();
            return node;
        } catch (RuntimeException failure) { throw new ApiException(409, "YAML_SYNC_CONFLICT", "同步基线损坏，拒绝覆盖文件"); }
    }
    private static boolean hash(JsonNode value, boolean allowMissing) {
        return value != null && value.isString() && (value.asString().matches("sha256:[a-f0-9]{64}")
                || (allowMissing && value.asString().equals("missing")));
    }
    String status(String id) {
        try {
            byte[] json = files.readMenuBytes(id).orElse(null);
            byte[] yaml = files.readAuxiliary(MenuPersistence.Auxiliary.YAML, id).orElse(null);
            JsonNode base = baseline(id);
            if (base == null) return yaml == null ? "missing" : "unknown";
            if (base.has("pending")) return "syncFailed";
            boolean j = json == null || !MenuRepository.revision(json).equals(base.get("jsonHash").asString());
            boolean y = yaml != null && !MenuRepository.revision(yaml).equals(base.get("yamlHash").asString());
            return j && y ? "conflict" : y ? "pendingImport" : j || yaml == null ? "syncFailed" : "synced";
        } catch (IOException | RuntimeException failure) { return "unknown"; }
    }
    void checkSave(String id) {
        String state = status(id);
        if (!state.equals("synced") && !state.equals("missing"))
            throw new ApiException(409, "YAML_SYNC_CONFLICT", "YAML 待导入或同步状态不明确，请核查文件后重载；状态：" + state);
    }
    void checkImport(String id) {
        try {
            JsonNode base = baseline(id);
            if (base == null || base.has("pending")) throw new ApiException(409, "YAML_SYNC_CONFLICT", "同步基线缺失或同步未完成，拒绝猜测覆盖");
            byte[] json = files.readMenuBytes(id).orElse(null);
            if (json == null || !MenuRepository.revision(json).equals(base.get("jsonHash").asString()))
                throw new ApiException(409, "YAML_SYNC_CONFLICT", "正式 JSON 已在基线之后修改，拒绝用 YAML 覆盖");
        } catch (IOException failure) { throw new ApiException(500, "YAML_SYNC_READ_FAILED", "同步基线读取失败"); }
    }
    byte[] yamlSnapshot(String id) {
        try { return files.readAuxiliary(MenuPersistence.Auxiliary.YAML, id).orElse(null); }
        catch (IOException failure) { throw new ApiException(500, "YAML_READ_FAILED", "YAML 文件读取失败"); }
    }
    void export(String id, byte[] json, byte[] yaml, byte[] currentYaml) throws IOException {
        var intent = MenuJson.createMapper().createObjectNode();
        intent.put("jsonHash", MenuRepository.revision(json));
        intent.put("yamlHash", currentYaml == null ? "missing" : MenuRepository.revision(currentYaml));
        intent.put("pending", MenuRepository.revision(yaml));
        files.writeAuxiliary(MenuPersistence.Auxiliary.SYNC, id, MenuDocuments.encode(intent));
        // Recheck the exact bytes after persisting the intent; manual edits are never overwritten silently.
        byte[] latest = files.readAuxiliary(MenuPersistence.Auxiliary.YAML, id).orElse(null);
        if (!java.util.Arrays.equals(currentYaml, latest)) throw new IOException("Manual YAML changed during export");
        files.writeAuxiliaryIfUnchanged(MenuPersistence.Auxiliary.YAML, id, yaml, currentYaml);
        intent.remove("pending"); intent.put("yamlHash", MenuRepository.revision(yaml));
        files.writeAuxiliary(MenuPersistence.Auxiliary.SYNC, id, MenuDocuments.encode(intent));
    }
    void recover(String id) throws IOException {
        JsonNode base = baseline(id);
        if (base == null || !base.has("pending")) return;
        byte[] json = files.readMenuBytes(id).orElse(null);
        byte[] yaml = files.readAuxiliary(MenuPersistence.Auxiliary.YAML, id).orElse(null);
        if (json == null || !MenuRepository.revision(json).equals(base.get("jsonHash").asString())) return;
        byte[] expected = MenuYaml.encode(MenuDocuments.decode(json));
        if (!MenuRepository.revision(expected).equals(base.get("pending").asString())) return;
        String y = yaml == null ? "missing" : MenuRepository.revision(yaml);
        if (y.equals(base.get("pending").asString()) || y.equals(base.get("yamlHash").asString())) export(id, json, expected, yaml);
    }
}
