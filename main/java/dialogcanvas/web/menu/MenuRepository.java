package dialogcanvas.web.menu;

import dialogcanvas.model.*;
import dialogcanvas.storage.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** All collection operations are serial and asynchronous, including cross-menu reference checks. */
public final class MenuRepository implements AutoCloseable {
    private dialogcanvas.audit.AuditLogger audit = dialogcanvas.audit.AuditLogger.disabled();
    public void auditLogger(dialogcanvas.audit.AuditLogger audit) { this.audit = Objects.requireNonNull(audit); }
    private final MenuPersistence files;
    private final MenuValidator validator;
    private final MenuRegistry registry;
    private final Clock clock;
    private final JsonMapper json = MenuJson.createMapper();
    private final MenuSynchronization synchronization;
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), Thread.ofPlatform().daemon().name("DCMenu-menus").factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean ready;
    private volatile boolean closed;
    public MenuRepository(MenuPersistence files, MenuValidator validator) { this(files, validator, new MenuRegistry(), Clock.systemUTC()); }
    public MenuRepository(MenuPersistence files, MenuValidator validator, MenuRegistry registry, Clock clock) {
        this.files = files; this.validator = validator; this.registry = registry; this.clock = clock;
        this.synchronization = new MenuSynchronization(files);
    }
    public boolean ready() { return ready && !closed; }
    /** Nonblocking, thread-safe command completion from the committed registry only. */
    public List<String> completionIds() { return ready() ? registry.snapshot().keySet().stream().sorted().toList() : List.of(); }
    public MenuValidator validator() { return validator; }
    /** Final retention work shares the menu coordinator: policy edits cannot race qualification and replacement. */
    public CompletableFuture<Void> protectLimitRetention(Map<String, String> expected,
            dialogcanvas.engine.retention.PlayerDataRetention.CheckedWork operation) {
        return submit(true, () -> {
            Map<String, String> current = new HashMap<>();
            for (var entry : registry.snapshot().values()) {
                MenuDefinition menu = json.readValue(entry.json(), MenuDefinition.class);
                List<ActionDefinition> buttons = new ArrayList<>();
                if (menu.getActions() != null) buttons.addAll(menu.getActions());
                for (ActionDefinition button : Arrays.asList(menu.getNoticeAction(), menu.getYesButton(), menu.getNoButton(), menu.getExitAction())) if (button != null) buttons.add(button);
                for (var button : buttons) {
                    var caps = button.getLimit() == null ? Map.<String, LimitDefinition>of() : button.getLimit();
                    Set<String> keys = new HashSet<>(caps.keySet()); if (button.getCooldown() != null) keys.addAll(button.getCooldown().keySet());
                    for (String key : keys) {
                        var cap = caps.get(key); String mode = cap == null ? "NEVER" : Objects.requireNonNullElse(cap.getReset(), "UNKNOWN");
                        String identity = dialogcanvas.engine.production.ProductionActionEngine.limitKey(menu.getId(), button.getId(), key, cap);
                        current.merge(identity, mode, (a, b) -> a.equals(b) ? a : "AMBIGUOUS");
                    }
                }
            }
            if (expected.entrySet().stream().anyMatch(entry -> !entry.getValue().equals(current.get(entry.getKey()))))
                throw new dialogcanvas.engine.production.ProductionStore.MaintenanceDenied("CURRENT_RULE_NOT_CONFIRMED");
            try { operation.run(); } catch (Exception failure) { throw new CompletionException(failure); }
            return null;
        });
    }
    /** Same immutable publication as the menu collection; no file I/O on the game thread. */
    public String mainMenuId() { return ready() ? registry.mainMenuId() : null; }

    /** Nonblocking callback lease from the immutable committed registry, not from disk or drafts.
     * Entry identity also invalidates deletion/recreation and successful reloads. */
    public java.util.function.BooleanSupplier callbackVersion(MenuDefinition definition) {
        String menuId = definition.getId();
        MenuRegistry.Entry captured = registry.snapshot().get(menuId);
        if (!ready() || captured == null || !json.valueToTree(definition).equals(
                json.valueToTree(json.readValue(captured.json(), MenuDefinition.class)))) return () -> false;
        return () -> ready() && registry.snapshot().get(menuId) == captured;
    }

    public CompletableFuture<Void> initialize() {
        return submit(false, () -> {
            try {
                files.initialize(); migrateLegacyYaml(); Set<String> ids = Set.copyOf(files.menuIds());
                Map<String, MenuRegistry.Entry> loaded = new HashMap<>();
                for (String id : ids) {
                    byte[] bytes = files.readMenuBytes(id).orElseThrow();
                    var prepared = validator.prepare(MenuDocuments.decode(bytes), ids);
                    if (!id.equals(prepared.menu().getId())) throw new IllegalArgumentException("Menu filename/ID mismatch");
                    loaded.put(id, entry(prepared, bytes));
                }
                registry.publish(Map.copyOf(loaded)); ready = true;
                for (String id : ids) {
                    try { synchronization.recover(id); } catch (IOException | RuntimeException failure) { /* expose status; keep formal snapshot */ }
                    String state = synchronization.status(id);
                    if (!state.equals("synced")) java.util.logging.Logger.getLogger("DialogCanvas").warning("Menu " + id + " YAML status: " + state + "; manual file preserved");
                }
                return null;
            } catch (IOException | RuntimeException failure) {
                ready = false;
                java.util.logging.Logger.getLogger("DialogCanvas").severe("菜单加载失败：" + failure.getMessage());
                throw new ApiException(503, "MENU_SERVICE_UNAVAILABLE", "菜单加载失败，请停服检查菜单配置及文件访问权限");
            }
        });
    }

    public CompletableFuture<JsonNode> list() {
        return submit(true, () -> {
            var items = json.createArrayNode();
            registry.snapshot().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                JsonNode document = json.readTree(entry.getValue().json());
                var item = items.addObject(); item.put("id", entry.getKey()); item.set("title", document.get("title"));
                item.set("dialogType", document.get("dialogType")); item.put("revision", entry.getValue().revision());
                JsonNode metadata = document.get("metadata");
                if (metadata != null && metadata.has("updated")) item.set("updated", metadata.get("updated")); else item.putNull("updated");
                item.set("warnings", json.valueToTree(entry.getValue().warnings()));
                item.put("syncStatus", synchronization.status(entry.getKey()));
                item.put("shortcutEnabled", entry.getValue().shortcutEnabled());
            });
            return json.createObjectNode().set("items", items);
        });
    }

    private void migrateLegacyYaml() throws IOException {
        List<String> legacy = files.auxiliaryIds(MenuPersistence.Auxiliary.LEGACY_YAML);
        Set<String> candidates = new HashSet<>(files.menuIds()); candidates.addAll(legacy);
        Map<String, byte[]> planned = new LinkedHashMap<>();
        Map<String, byte[]> originals = new HashMap<>();
        for (String id : legacy) {
            byte[] original = files.readAuxiliary(MenuPersistence.Auxiliary.LEGACY_YAML, id).orElseThrow();
            Optional<byte[]> receipt = files.readAuxiliary(MenuPersistence.Auxiliary.MIGRATION_RECEIPT, id);
            if (receipt.isPresent()) {
                JsonNode proof = MenuDocuments.decode(receipt.get());
                Optional<byte[]> backup = files.readAuxiliary(MenuPersistence.Auxiliary.MIGRATION_BACKUP, id);
                if (!proof.path("jsonHash").asString("").matches("sha256:[a-f0-9]{64}")
                        || backup.isEmpty()
                        || (proof.has("legacyHash") && !revision(backup.get()).equals(proof.path("legacyHash").asString())))
                    throw new IOException("Incomplete or damaged legacy migration receipt: " + id);
                // Formal JSON may have been deleted through the API after migration. Never resurrect it.
                continue;
            }
            var prepared = validator.prepare(MenuYaml.decode(original), candidates);
            if (!id.equals(prepared.menu().getId())) throw new IOException("Legacy filename mismatch");
            byte[] target = MenuDocuments.encode(json.valueToTree(prepared.menu()));
            Optional<byte[]> existing = files.readMenuBytes(id);
            Optional<byte[]> backup = files.readAuxiliary(MenuPersistence.Auxiliary.MIGRATION_BACKUP, id);
            if (backup.isPresent() && !Arrays.equals(original, backup.get()))
                throw new IOException("Legacy YAML changed after migration backup: " + id);
            if (existing.isPresent() && (backup.isEmpty() || !Arrays.equals(original, backup.get()) || !MenuDocuments.decode(existing.get()).equals(MenuDocuments.decode(target))))
                throw new IOException("Legacy JSON/YAML collision");
            planned.put(id, target);
            originals.put(id, original);
        }
        // Check the combined collection before any migration writes, including shortcut ownership.
        var shortcutCandidates = new ArrayList<String>();
        var committedCandidates = new HashSet<>(files.menuIds()); committedCandidates.addAll(planned.keySet());
        for (String id : committedCandidates) {
            byte[] bytes = planned.containsKey(id) ? planned.get(id) : files.readMenuBytes(id).orElseThrow();
            var candidate = validator.prepare(MenuDocuments.decode(bytes), committedCandidates);
            if (Boolean.TRUE.equals(candidate.menu().getShortcutEnabled())) shortcutCandidates.add(id);
        }
        if (shortcutCandidates.size() > 1) throw new IllegalArgumentException("快捷主菜单重复设置：" + String.join("、", shortcutCandidates.stream().sorted().toList()));
        // Validate every candidate and reference before any migration writes; backups survive partial progress.
        for (String id : planned.keySet()) {
            byte[] original = originals.get(id);
            if (!Arrays.equals(original, files.readAuxiliary(MenuPersistence.Auxiliary.LEGACY_YAML, id).orElse(null)))
                throw new IOException("Legacy YAML changed during migration: " + id);
            if (files.readAuxiliary(MenuPersistence.Auxiliary.MIGRATION_BACKUP, id).isEmpty())
                files.writeAuxiliaryIfUnchanged(MenuPersistence.Auxiliary.MIGRATION_BACKUP, id, original, null);
        }
        for (var entry : planned.entrySet()) {
            String id = entry.getKey(); byte[] bytes = entry.getValue();
            if (!Arrays.equals(originals.get(id), files.readAuxiliary(MenuPersistence.Auxiliary.LEGACY_YAML, id).orElse(null)))
                throw new IOException("Legacy YAML changed during migration: " + id);
            files.writeMenuBytes(id, bytes);
            if (files.readAuxiliary(MenuPersistence.Auxiliary.YAML, id).isEmpty()) synchronization.export(id, bytes, MenuYaml.encode(MenuDocuments.decode(bytes)), null);
            files.writeAuxiliary(MenuPersistence.Auxiliary.MIGRATION_RECEIPT, id,
                    MenuDocuments.encode(json.createObjectNode().put("jsonHash", revision(bytes))
                            .put("legacyHash", revision(files.readAuxiliary(MenuPersistence.Auxiliary.MIGRATION_BACKUP, id).orElseThrow()))));
        }
    }
    public CompletableFuture<JsonNode> get(String id) {
        return submit(true, () -> { id(id); return envelope(requireEntry(id)); });
    }
    /** Integration entry: returns a fresh POJO, never exposes a mutable registry entry. */
    public CompletableFuture<MenuDefinition> definition(String id) {
        return submit(true, () -> { id(id); return json.readValue(requireEntry(id).json(), MenuDefinition.class); });
    }
    public CompletableFuture<JsonNode> validate(JsonNode menu) {
        JsonNode copy = menu.deepCopy();
        return submit(true, () -> {
            var prepared = validator.prepare(copy, registry.snapshot().keySet());
            checkShortcut(prepared.menu(), registry.snapshot());
            return json.valueToTree(Map.of("valid", true, "warnings", prepared.warnings()));
        });
    }
    /** Browser conversion only: no writes, publishing, metadata changes or sync recovery. */
    public CompletableFuture<JsonNode> importYaml(byte[] upload) {
        if (upload.length > MenuYaml.MAX_BYTES) return CompletableFuture.failedFuture(new ApiException(413, "HTTP_413", "YAML 文件超过 1 MiB"));
        byte[] copy = upload.clone();
        return submit(true, () -> {
            JsonNode document;
            try { document = MenuYaml.decode(copy); }
            catch (RuntimeException failure) { throw ApiException.field("/menu", "INVALID_YAML", "YAML 无法安全解析；请检查缩进、重复键、标签及编码"); }
            if (!document.has("schemaVersion")) throw ApiException.field("/menu/schemaVersion", "UNSUPPORTED_MENU_FORMAT", "仅支持 DCMenu 当前菜单根文档（schemaVersion: 1），不支持其他插件格式或 API 响应封装");
            var snapshot = registry.snapshot();
            var prepared = validator.prepare(document, snapshot.keySet());
            checkShortcut(prepared.menu(), snapshot);
            var result = json.createObjectNode();
            result.put("valid", true); result.set("menu", document);
            result.set("warnings", json.valueToTree(prepared.warnings()));
            var existing = snapshot.get(prepared.menu().getId());
            if (existing == null) result.putNull("existing"); else result.set("existing", envelope(existing));
            return result;
        });
    }
    public CompletableFuture<JsonNode> exportYaml(JsonNode menu) {
        JsonNode copy = menu.deepCopy();
        return submit(true, () -> {
            var prepared = validator.prepare(copy, registry.snapshot().keySet());
            checkShortcut(prepared.menu(), registry.snapshot());
            byte[] yaml;
            try { yaml = MenuYaml.encode(copy); }
            catch (RuntimeException failure) { throw ApiException.field("/menu", "DOCUMENT_TOO_LARGE", "菜单无法导出为 1 MiB 以内的 YAML"); }
            var result = json.createObjectNode(); result.put("source", "draft");
            result.put("fileName", prepared.menu().getId() + "-draft.yml");
            result.put("yaml", new String(yaml, java.nio.charset.StandardCharsets.UTF_8));
            result.set("warnings", json.valueToTree(prepared.warnings()));
            return result;
        });
    }
    public CompletableFuture<JsonNode> create(JsonNode menu) { return create(menu, dialogcanvas.audit.AuditLogger.Origin.system()); }
    public CompletableFuture<JsonNode> create(JsonNode menu, dialogcanvas.audit.AuditLogger.Origin origin) { return save(null, null, menu, origin); }
    public CompletableFuture<JsonNode> update(String id, String revision, JsonNode menu) { return update(id, revision, menu, dialogcanvas.audit.AuditLogger.Origin.system()); }
    public CompletableFuture<JsonNode> update(String id, String revision, JsonNode menu, dialogcanvas.audit.AuditLogger.Origin origin) { return save(id, revision, menu, origin); }

    private CompletableFuture<JsonNode> save(String pathId, String revision, JsonNode menu, dialogcanvas.audit.AuditLogger.Origin origin) {
        JsonNode copy = menu.deepCopy();
        return auditedSubmit(pathId == null ? dialogcanvas.audit.AuditLogger.Event.MENU_CREATE : dialogcanvas.audit.AuditLogger.Event.MENU_UPDATE,
                origin, pathId == null ? copy.path("id").asString("") : pathId, () -> {
            Map<String, MenuRegistry.Entry> old = registry.snapshot();
            if (pathId != null) { id(pathId); requireRevision(revision); requireEntry(pathId); }
            var prepared = validator.prepare(copy, old.keySet()); String id = prepared.menu().getId();
            checkShortcut(prepared.menu(), old);
            if (pathId != null && !pathId.equals(id)) throw ApiException.field("/menu/id", "ID_MISMATCH", "路径 ID 必须与 menu.id 一致");
            byte[] yamlBefore = synchronization.yamlSnapshot(id);
            synchronization.checkSave(id);
            byte[] before = read(id);
            if (pathId == null && (old.containsKey(id) || before != null)) throw new ApiException(409, "MENU_ALREADY_EXISTS", "菜单 ID 已存在");
            if (pathId != null) compareRevision(revision, before, old.get(id));
            applyMetadata(prepared.menu(), pathId == null ? null : json.readValue(old.get(id).json(), MenuDefinition.class));
            // Freeze, serialize and verify the exact on-disk round-trip before any mutation.
            JsonNode normalized = json.valueToTree(prepared.menu()); byte[] bytes;
            try { bytes = MenuDocuments.encode(normalized); }
            catch (RuntimeException failure) { throw ApiException.field("/menu", "DOCUMENT_TOO_LARGE", "菜单无法安全序列化或超过文件大小限制"); }
            var verified = validator.prepare(MenuDocuments.decode(bytes), old.keySet());
            var next = new HashMap<>(old); next.put(id, entry(verified, bytes)); Map<String, MenuRegistry.Entry> snapshot = Map.copyOf(next);
            byte[] yamlBytes = MenuYaml.encode(normalized);
            write(id, bytes);
            publishOrRestore(id, before, old, snapshot);
            try { synchronization.export(id, bytes, yamlBytes, yamlBefore); }
            catch (IOException | RuntimeException yamlFailure) {
                var response = envelope(snapshot.get(id));
                ((tools.jackson.databind.node.ArrayNode) response.get("warnings")).addObject()
                        .put("path", "/").put("code", "YAML_SYNC_FAILED").put("message", "正式 JSON 已保存，但 YAML 同步失败");
                return response;
            }
            return envelope(snapshot.get(id));
        });
    }

    /** Imports one manually edited YAML document and republishes only after validation and JSON persistence. */
    public CompletableFuture<JsonNode> reloadYaml(String id) {
        return reloadYaml(id, dialogcanvas.audit.AuditLogger.Origin.system());
    }
    public CompletableFuture<JsonNode> reloadYaml(String id, dialogcanvas.audit.AuditLogger.Origin origin) {
        return auditedSubmit(dialogcanvas.audit.AuditLogger.Event.YAML_IMPORT, origin, id, () -> reloadYamlNow(id));
    }

    public CompletableFuture<JsonNode> reloadAllYaml() {
        return reloadAllYaml(dialogcanvas.audit.AuditLogger.Origin.system());
    }
    public CompletableFuture<JsonNode> reloadAllYaml(dialogcanvas.audit.AuditLogger.Origin origin) {
        return auditedSubmit(dialogcanvas.audit.AuditLogger.Event.YAML_IMPORT_BATCH, origin, "*", () -> {
            var result = json.createObjectNode(); var items = result.putArray("items");
            try {
                for (String id : files.auxiliaryIds(MenuPersistence.Auxiliary.YAML)) {
                    try { var imported = auditWork(audit.begin(dialogcanvas.audit.AuditLogger.Event.YAML_IMPORT, origin, id), id, () -> reloadYamlNow(id)); var item = items.addObject().put("id", id).put("success", true); item.set("warnings", imported.get("warnings")); }
                    catch (RuntimeException failure) {
                        var item = items.addObject().put("id", id).put("success", false);
                        if (failure instanceof ApiException api) { item.put("code", api.code()).put("error", api.getMessage()); item.set("details", json.valueToTree(api.details())); }
                        else item.put("code", "YAML_IMPORT_FAILED").put("error", "YAML 配置无法解析");
                    }
                }
            } catch (IOException failure) { throw new ApiException(500, "YAML_LIST_FAILED", "手动 YAML 列表读取失败"); }
            return result;
        });
    }

    private JsonNode reloadYamlNow(String id) {
        id(id);
        byte[] yaml;
        try { yaml = files.readAuxiliary(MenuPersistence.Auxiliary.YAML, id).orElseThrow(() -> new ApiException(404, "YAML_NOT_FOUND", "手动 YAML 不存在")); }
        catch (IOException failure) { throw new ApiException(500, "YAML_READ_FAILED", "手动 YAML 读取失败"); }
        var old = registry.snapshot(); var current = old.get(id);
        if (current != null) synchronization.checkImport(id);
        else {
            try {
                if (files.readAuxiliary(MenuPersistence.Auxiliary.SYNC, id).isPresent())
                    throw new ApiException(409, "YAML_SYNC_CONFLICT", "正式菜单缺失但存在同步基线，请核查中断的删除操作，拒绝自动重新创建");
            } catch (IOException failure) { throw new ApiException(500, "YAML_SYNC_READ_FAILED", "同步基线读取失败"); }
        }
        var candidateIds = new HashSet<>(old.keySet()); candidateIds.add(id);
        var prepared = validator.prepare(MenuYaml.decode(yaml), candidateIds);
        checkShortcut(prepared.menu(), old);
        if (!id.equals(prepared.menu().getId())) throw ApiException.field("/menu/id", "ID_MISMATCH", "YAML 文件名必须与 menu.id 一致");
        byte[] before = read(id);
        if (current != null) compareRevision(current.revision(), before, current);
        else if (before != null) throw new ApiException(409, "REVISION_CONFLICT", "正式文件已存在但未加载");
        // Imported metadata is server maintained; normalize it against the current formal snapshot.
        prepared.menu().setMetadata(current == null ? null : json.readValue(current.json(), MenuDefinition.class).getMetadata());
        applyMetadata(prepared.menu(), current == null ? null : json.readValue(current.json(), MenuDefinition.class));
        prepared = validator.prepare(json.valueToTree(prepared.menu()), candidateIds);
        byte[] jsonBytes = MenuDocuments.encode(json.valueToTree(prepared.menu()));
        byte[] normalizedYaml = MenuYaml.encode(json.valueToTree(prepared.menu()));
        write(id, jsonBytes);
        var next = new HashMap<>(old); next.put(id, entry(prepared, jsonBytes));
        publishOrRestore(id, before, old, Map.copyOf(next));
        try { synchronization.export(id, jsonBytes, normalizedYaml, yaml); }
        catch (IOException | RuntimeException failure) {
            var result = envelope(next.get(id));
            ((tools.jackson.databind.node.ArrayNode) result.get("warnings")).addObject().put("code", "YAML_SYNC_FAILED").put("message", "正式 JSON 已导入，但 YAML 同步失败");
            return result;
        }
        return envelope(next.get(id));
    }


    public CompletableFuture<Void> delete(String id, String revision) {
        return delete(id, revision, dialogcanvas.audit.AuditLogger.Origin.system());
    }
    public CompletableFuture<Void> delete(String id, String revision, dialogcanvas.audit.AuditLogger.Origin origin) {
        return auditedSubmit(dialogcanvas.audit.AuditLogger.Event.MENU_DELETE, origin, id, () -> {
            id(id); requireRevision(revision); var current = requireEntry(id); var old = registry.snapshot();
            synchronization.checkSave(id);
            byte[] before = read(id); compareRevision(revision, before, current);
            List<ApiException.Detail> references = new ArrayList<>();
            old.forEach((source, entry) -> {
                if (!source.equals(id) && entry.references().contains(id)) {
                    for (String path : MenuValidator.referencePaths(json.readTree(entry.json()), "/menus/" + source, id))
                        references.add(new ApiException.Detail(path, "MENU_REFERENCE", "该菜单仍被引用"));
                }
            });
            if (!references.isEmpty()) throw new ApiException(409, "MENU_REFERENCED", "请先移除其他菜单对该菜单的引用", references);
            var next = new HashMap<>(old); next.remove(id); Map<String, MenuRegistry.Entry> snapshot = Map.copyOf(next);
            byte[] yamlBefore;
            byte[] syncBefore;
            try {
                yamlBefore = files.readAuxiliary(MenuPersistence.Auxiliary.YAML, id).orElse(null);
                syncBefore = files.readAuxiliary(MenuPersistence.Auxiliary.SYNC, id).orElse(null);
            } catch (IOException failure) { throw new ApiException(500, "STORAGE_READ_FAILED", "删除同步文件前读取失败"); }
            synchronization.checkSave(id);
            try {
                remove(id);
                // A manual editor can change YAML while the formal JSON is being detached.
                if (!Arrays.equals(yamlBefore, synchronization.yamlSnapshot(id)))
                    throw new ApiException(409, "YAML_SYNC_CONFLICT", "删除期间 YAML 已被手动修改，已取消删除并保留修改");
                files.removeAuxiliaryIfUnchanged(MenuPersistence.Auxiliary.YAML, id, yamlBefore);
                files.removeAuxiliary(MenuPersistence.Auxiliary.SYNC, id);
                registry.publish(snapshot);
            } catch (IOException | RuntimeException failure) {
                try {
                    byte[] latest = files.readMenuBytes(id).orElse(null);
                    if (latest == null) files.writeMenuBytes(id, before);
                    else if (!Arrays.equals(before, latest)) throw new IOException("Formal JSON changed during compensation");
                    restoreDeletedAuxiliary(id, MenuPersistence.Auxiliary.YAML, yamlBefore);
                    restoreDeletedAuxiliary(id, MenuPersistence.Auxiliary.SYNC, syncBefore);
                    registry.publish(old);
                } catch (IOException | RuntimeException recoveryFailure) {
                    ready = false; throw new ApiException(503, "MENU_STATE_UNCERTAIN", "删除补偿失败，请停服核查");
                }
                if (failure instanceof ApiException api && api.code().equals("YAML_SYNC_CONFLICT")) throw api;
                throw new ApiException(500, "STORAGE_DELETE_FAILED", "删除失败，已恢复正式菜单及注册表；期间的手动 YAML 修改已保留");
            }
            return null;
        });
    }

    private void restoreDeletedAuxiliary(String id, MenuPersistence.Auxiliary kind, byte[] before) throws IOException {
        byte[] latest = files.readAuxiliary(kind, id).orElse(null);
        if (latest == null && before != null) files.writeAuxiliaryIfUnchanged(kind, id, before, null);
        else if (!Arrays.equals(before, latest) && kind != MenuPersistence.Auxiliary.YAML)
            throw new IOException("Synchronization state changed during compensation");
        // Never overwrite a YAML newly created/changed by the administrator during rollback.
    }

    private void publishOrRestore(String id, byte[] before, Map<String, MenuRegistry.Entry> old, Map<String, MenuRegistry.Entry> next) {
        try { registry.publish(next); }
        catch (RuntimeException publishFailure) {
            try {
                byte[] latest = files.readMenuBytes(id).orElse(null);
                if (latest == null || !revision(latest).equals(next.get(id).revision()))
                    throw new IOException("Formal JSON changed during publication compensation");
                if (before == null) files.removeMenu(id); else files.writeMenuBytes(id, before);
                registry.publish(old);
            } catch (IOException | RuntimeException recoveryFailure) {
                ready = false;
                throw new ApiException(503, "MENU_STATE_UNCERTAIN", "文件与注册表恢复失败，菜单服务已停止，请停服核查后重启");
            }
            throw new ApiException(500, "REGISTRY_PUBLISH_FAILED", "菜单发布失败，已恢复原文件和注册表");
        }
    }
    private void applyMetadata(MenuDefinition incoming, MenuDefinition previous) {
        MenuMetadata existing = previous == null ? null : previous.getMetadata();
        if (incoming.getMetadata() != null && (existing == null || !json.valueToTree(existing).equals(json.valueToTree(incoming.getMetadata())))) {
            throw ApiException.field("/menu/metadata", "READ_ONLY", "metadata 由服务端维护，不能修改");
        }
        MenuMetadata metadata = new MenuMetadata(); String now = clock.instant().toString();
        metadata.setCreated(existing != null && existing.getCreated() != null ? existing.getCreated() : now);
        metadata.setUpdated(now); metadata.setAuthor("server_owner");
        int version = existing == null || existing.getVersion() == null ? 0 : existing.getVersion();
        if (version < 0 || version == Integer.MAX_VALUE) throw ApiException.field("/menu/metadata/version", "OUT_OF_RANGE", "菜单版本号无法递增");
        metadata.setVersion(version + 1); incoming.setMetadata(metadata);
    }
    private MenuRegistry.Entry entry(MenuValidator.Prepared prepared, byte[] bytes) {
        return new MenuRegistry.Entry(prepared.json(), revision(bytes), prepared.warnings(), prepared.references(), Boolean.TRUE.equals(prepared.menu().getShortcutEnabled()));
    }
    private void checkShortcut(MenuDefinition candidate, Map<String, MenuRegistry.Entry> current) {
        if (!Boolean.TRUE.equals(candidate.getShortcutEnabled())) return;
        String owner = current.entrySet().stream().filter(entry -> entry.getValue().shortcutEnabled() && !entry.getKey().equals(candidate.getId()))
                .map(Map.Entry::getKey).sorted().findFirst().orElse(null);
        if (owner != null) throw new ApiException(409, "SHORTCUT_MENU_CONFLICT", "快捷打开已由菜单“" + owner + "”启用；请先取消该菜单的快捷打开并保存。",
                List.of(new ApiException.Detail("/menu/shortcutEnabled", "SHORTCUT_MENU_CONFLICT", "当前快捷主菜单：" + owner)));
    }
    private JsonNode envelope(MenuRegistry.Entry entry) {
        var result = json.createObjectNode(); result.put("revision", entry.revision()); result.set("menu", json.readTree(entry.json()));
        result.put("syncStatus", synchronization.status(json.readTree(entry.json()).get("id").asString()));
        result.set("warnings", json.valueToTree(entry.warnings())); return result;
    }
    private MenuRegistry.Entry requireEntry(String id) {
        var entry = registry.snapshot().get(id); if (entry == null) throw new ApiException(404, "MENU_NOT_FOUND", "菜单不存在"); return entry;
    }
    private byte[] read(String id) {
        try { return files.readMenuBytes(id).orElse(null); }
        catch (IOException | RuntimeException failure) { throw new ApiException(500, "STORAGE_READ_FAILED", "菜单文件读取失败"); }
    }
    private void write(String id, byte[] bytes) {
        try { files.writeMenuBytes(id, bytes); }
        catch (IOException | RuntimeException failure) { throw new ApiException(500, "STORAGE_WRITE_FAILED", "菜单文件写入失败，未发布新注册表"); }
    }
    private void remove(String id) {
        try { if (!files.removeMenu(id)) throw new IOException("Missing menu during deletion"); }
        catch (IOException | RuntimeException failure) { throw new ApiException(500, "STORAGE_DELETE_FAILED", "菜单文件删除失败，注册表未更新"); }
    }
    private static void compareRevision(String expected, byte[] bytes, MenuRegistry.Entry current) {
        if (bytes == null || !expected.equals(revision(bytes)) || !expected.equals(current.revision())) {
            throw new ApiException(409, "REVISION_CONFLICT", "正式菜单已被修改，请重新读取核查并合并草稿");
        }
    }
    private static void requireRevision(String revision) {
        if (revision == null) throw new ApiException(428, "REVISION_REQUIRED", "必须提交当前 revision");
        if (!revision.matches("sha256:[a-f0-9]{64}")) throw new ApiException(400, "INVALID_REVISION", "revision 格式错误");
    }
    private static void id(String id) { if (!DocumentIds.valid(id)) throw new ApiException(400, "INVALID_MENU_ID", "菜单 ID 不合法"); }
    public static String revision(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private <T> CompletableFuture<T> auditedSubmit(dialogcanvas.audit.AuditLogger.Event event, dialogcanvas.audit.AuditLogger.Origin origin, String target, Supplier<T> task) {
        var operation = audit.begin(event, origin, target);
        var result = submit(true, () -> auditWork(operation, target, task));
        result.whenComplete((value, failure) -> { if (failure != null) operation.finish(dialogcanvas.audit.AuditLogger.Result.FAILED,
                failure instanceof ApiException api ? api.code() : "MENU_OPERATION_FAILED"); });
        return result.copy(); // Caller cancellation must not suppress actual-result audit.
    }
    private <T> T auditWork(dialogcanvas.audit.AuditLogger.Operation operation, String target, Supplier<T> task) {
        var beforeEntry = registry.snapshot().get(target); String before = beforeEntry == null ? null : beforeEntry.revision();
        try {
            T result = task.get(); String code = "OK"; var outcome = dialogcanvas.audit.AuditLogger.Result.SUCCESS;
            if (result instanceof JsonNode node) {
                for (var warning : node.path("warnings")) if (warning.path("code").asString("").equals("YAML_SYNC_FAILED")) { code = "YAML_SYNC_FAILED"; outcome = dialogcanvas.audit.AuditLogger.Result.PARTIAL; }
                for (var item : node.path("items")) {
                    if (!item.path("success").asBoolean(true)) { code = "BATCH_PARTIAL"; outcome = dialogcanvas.audit.AuditLogger.Result.PARTIAL; }
                    for (var warning : item.path("warnings")) if (warning.path("code").asString("").equals("YAML_SYNC_FAILED")) { code = "BATCH_PARTIAL"; outcome = dialogcanvas.audit.AuditLogger.Result.PARTIAL; }
                }
            }
            var afterEntry = registry.snapshot().get(target);
            operation.finish(outcome, code, before, afterEntry == null ? null : afterEntry.revision()); return result;
        } catch (RuntimeException failure) {
            String code = failure instanceof ApiException api ? api.code() : "MENU_OPERATION_FAILED";
            operation.finish(code.equals("MENU_STATE_UNCERTAIN") ? dialogcanvas.audit.AuditLogger.Result.UNCERTAIN : dialogcanvas.audit.AuditLogger.Result.FAILED, code, before, null);
            throw failure;
        }
    }
    private <T> CompletableFuture<T> submit(boolean requireReady, Supplier<T> operation) {
        var future = new CompletableFuture<T>();
        try {
            if (closed) throw new RejectedExecutionException();
            worker.execute(() -> {
                try {
                    if (closed || (requireReady && !ready)) throw new ApiException(503, "MENU_SERVICE_UNAVAILABLE", "菜单服务不可用");
                    future.complete(operation.get());
                } catch (Exception failure) { future.completeExceptionally(failure); }
            });
        } catch (RejectedExecutionException failure) { future.completeExceptionally(new ApiException(503, "MENU_SERVICE_UNAVAILABLE", "菜单服务忙或已停止")); }
        return future;
    }
    private CompletableFuture<Void> closing;
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) return closing.copy();
        closing = new CompletableFuture<>();
        closed = true; worker.shutdown();
        // An in-progress commit must finish; never block the Minecraft thread or close its file store mid-write.
        Thread.ofVirtual().name("DCMenu-menu-shutdown").start(() -> {
            try { while (!worker.awaitTermination(1, TimeUnit.SECONDS)) { /* drain accepted operations */ } }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); closing.completeExceptionally(failure); }
            finally {
                try { files.close(); closing.complete(null); }
                catch (Exception failure) { closing.completeExceptionally(failure); }
            }
        });
        return closing.copy();
    }
    @Override public void close() { closeAsync(); }
}
