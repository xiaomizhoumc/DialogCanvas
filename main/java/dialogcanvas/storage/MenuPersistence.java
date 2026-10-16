package dialogcanvas.storage;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Byte-level persistence enables revision checks and exact compensation without changing StorageProvider. */
public interface MenuPersistence extends AutoCloseable {
    enum Auxiliary { YAML, SYNC, LEGACY_YAML, MIGRATION_RECEIPT, MIGRATION_BACKUP }
    void initialize() throws IOException;
    Optional<byte[]> readMenuBytes(String id) throws IOException;
    void writeMenuBytes(String id, byte[] bytes) throws IOException;
    boolean removeMenu(String id) throws IOException;
    List<String> menuIds() throws IOException;
    default Optional<byte[]> readAuxiliary(Auxiliary kind, String id) throws IOException {
        throw new UnsupportedOperationException("Auxiliary menu documents are not implemented by this provider");
    }
    default void writeAuxiliary(Auxiliary kind, String id, byte[] bytes) throws IOException {
        throw new UnsupportedOperationException("Auxiliary menu documents are not implemented by this provider");
    }
    default void writeAuxiliaryIfUnchanged(Auxiliary kind, String id, byte[] bytes, byte[] expected) throws IOException {
        if (!java.util.Arrays.equals(readAuxiliary(kind, id).orElse(null), expected))
            throw new IOException("Manual document changed during commit");
        writeAuxiliary(kind, id, bytes);
    }
    default boolean removeAuxiliaryIfUnchanged(Auxiliary kind, String id, byte[] expected) throws IOException {
        if (!java.util.Arrays.equals(readAuxiliary(kind, id).orElse(null), expected))
            throw new IOException("Manual document changed during deletion");
        return removeAuxiliary(kind, id);
    }
    default boolean removeAuxiliary(Auxiliary kind, String id) throws IOException {
        throw new UnsupportedOperationException("Auxiliary menu documents are not implemented by this provider");
    }
    default List<String> auxiliaryIds(Auxiliary kind) throws IOException {
        throw new UnsupportedOperationException("Auxiliary menu documents are not implemented by this provider");
    }
    void close();
}
