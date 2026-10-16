package dialogcanvas.storage;

import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** JSON is authoritative; YAML and durable synchronization records are separate artifacts. */
public final class FileStorage implements StorageProvider, MenuPersistence {
    private final Path directory, yamlDirectory;
    private final Map<Path, Path> realDirectories = new HashMap<>();
    private boolean initialized, closed;
    /** Standalone/test YAML stays inside its own root, never a shared temporary sibling. */
    public FileStorage(Path directory) { this(directory, directory.resolve("manual-menus")); }
    public FileStorage(Path directory, Path yamlDirectory) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.yamlDirectory = Objects.requireNonNull(yamlDirectory).toAbsolutePath().normalize();
        if (this.directory.resolve("menus").equals(this.yamlDirectory)) throw new IllegalArgumentException("JSON and YAML directories must differ");
    }
    public synchronized void initialize() throws IOException {
        if (closed || initialized) throw new IllegalStateException("Storage closed or initialized");
        registerDirectory(directory);
        for (StorageCategory category : StorageCategory.values()) registerDirectory(directory.resolve(category.directoryName()));
        registerDirectory(yamlDirectory);
        registerDirectory(directory.resolve("menu-sync"));
        registerDirectory(directory.resolve("menu-migration-receipts"));
        registerDirectory(directory.resolve("menu-migration-backup"));
        initialized = true;
    }
    private void registerDirectory(Path path) throws IOException {
        AtomicFiles.createDirectories(path);
        Path real = path.toRealPath();
        if (Files.isSymbolicLink(path) || !real.equals(path.getParent().toRealPath().resolve(path.getFileName()))) throw new IOException("Unsafe storage directory");
        realDirectories.put(path, real);
    }
    private Path root(Auxiliary kind) {
        return switch (kind) {
            case YAML -> yamlDirectory;
            case LEGACY_YAML -> directory.resolve("menus");
            case SYNC -> directory.resolve("menu-sync");
            case MIGRATION_RECEIPT -> directory.resolve("menu-migration-receipts");
            case MIGRATION_BACKUP -> directory.resolve("menu-migration-backup");
        };
    }
    private String extension(Auxiliary kind) { return kind == Auxiliary.YAML || kind == Auxiliary.LEGACY_YAML || kind == Auxiliary.MIGRATION_BACKUP ? ".yml" : ".json"; }
    private Path path(Path root, String id, String extension) throws IOException {
        requireOpen(); DocumentIds.require(id);
        if (!directory.toRealPath().equals(realDirectories.get(directory)) || Files.isSymbolicLink(root) || !root.toRealPath().equals(realDirectories.get(root))) throw new IOException("Storage directory changed");
        Path target = root.resolve(id + extension).normalize();
        AtomicFiles.requireTarget(target);
        if (!target.getParent().equals(root) || Files.isSymbolicLink(target) ||
                (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) throw new IOException("Unsafe document path");
        return target;
    }
    private Optional<byte[]> read(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (Files.size(path) > MenuYaml.MAX_BYTES) throw new IOException("Document too large");
        return Optional.of(Files.readAllBytes(path));
    }
    private void write(Path path, byte[] bytes) throws IOException {
        if (bytes.length > MenuYaml.MAX_BYTES) throw new IOException("Document too large");
        AtomicFiles.write(path, bytes);
    }
    private boolean remove(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        Path detached = path.getParent().resolve(".dcmenu-delete-" + UUID.randomUUID() + ".tmp");
        Files.move(path, detached, StandardCopyOption.ATOMIC_MOVE);
        try { Files.deleteIfExists(detached); }
        catch (IOException failure) { java.util.logging.Logger.getLogger("DialogCanvas").warning("Deferred temporary menu cleanup"); }
        return true;
    }
    private List<String> ids(Path root, String extension) throws IOException {
        path(root, "directory-check", extension);
        try (var entries = Files.list(root)) {
            var ids = new ArrayList<String>();
            for (Path entry : entries.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)).toList()) {
                String file = entry.getFileName().toString();
                if (!file.endsWith(extension)) throw new IOException("Document extension must be lowercase");
                String id = file.substring(0, file.length() - extension.length()); path(root, id, extension); ids.add(id);
            }
            return ids.stream().sorted().toList();
        }
    }
    public synchronized Optional<byte[]> readMenuBytes(String id) throws IOException {
        return read(path(directory.resolve("menus"), id, ".json"));
    }
    public synchronized void writeMenuBytes(String id, byte[] bytes) throws IOException {
        write(path(directory.resolve("menus"), id, ".json"), bytes);
    }
    public synchronized boolean removeMenu(String id) throws IOException {
        return remove(path(directory.resolve("menus"), id, ".json"));
    }
    public synchronized List<String> menuIds() throws IOException {
        return ids(directory.resolve("menus"), ".json");
    }
    public synchronized Optional<byte[]> readAuxiliary(Auxiliary kind, String id) throws IOException { return read(path(root(kind), id, extension(kind))); }
    public synchronized void writeAuxiliary(Auxiliary kind, String id, byte[] bytes) throws IOException { write(path(root(kind), id, extension(kind)), bytes); }
    public synchronized void writeAuxiliaryIfUnchanged(Auxiliary kind, String id, byte[] bytes, byte[] expected) throws IOException {
        if (bytes.length > MenuYaml.MAX_BYTES) throw new IOException("Document too large");
        AtomicFiles.writeIfUnchanged(path(root(kind), id, extension(kind)), bytes, expected);
    }
    public synchronized boolean removeAuxiliaryIfUnchanged(Auxiliary kind, String id, byte[] expected) throws IOException {
        Path target = path(root(kind), id, extension(kind)); AtomicFiles.requireUnchanged(target, expected); return remove(target);
    }
    public synchronized boolean removeAuxiliary(Auxiliary kind, String id) throws IOException { return remove(path(root(kind), id, extension(kind))); }
    public synchronized List<String> auxiliaryIds(Auxiliary kind) throws IOException { return ids(root(kind), extension(kind)); }
    public synchronized Optional<JsonNode> load(StorageCategory category, String id) throws IOException { menuOnly(category); return readMenuBytes(id).map(MenuDocuments::decode); }
    public synchronized void save(StorageCategory category, String id, JsonNode node) throws IOException { menuOnly(category); writeMenuBytes(id, MenuDocuments.encode(node)); }
    public synchronized boolean delete(StorageCategory category, String id) throws IOException { menuOnly(category); return removeMenu(id); }
    public synchronized List<String> listIds(StorageCategory category) throws IOException { menuOnly(category); return menuIds(); }
    public synchronized boolean exists(StorageCategory category, String id) throws IOException { menuOnly(category); return Files.exists(path(directory.resolve("menus"), id, ".json"), LinkOption.NOFOLLOW_LINKS); }
    private void menuOnly(StorageCategory category) { requireOpen(); if (category != StorageCategory.MENU) throw new UnsupportedOperationException("Only menus implemented"); }
    private void requireOpen() { if (closed || !initialized) throw new IllegalStateException("Storage is not open"); }
    public synchronized void close() { closed = true; initialized = false; }
}
