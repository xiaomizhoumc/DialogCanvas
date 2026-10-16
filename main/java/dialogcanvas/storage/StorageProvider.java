package dialogcanvas.storage;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Generic document storage; stage 2 typed repositories adapt this interface. */
public interface StorageProvider extends AutoCloseable {
    void initialize() throws IOException;

    /** Empty means not found; read or parse failures must be reported as errors. */
    Optional<JsonNode> load(StorageCategory category, String id) throws IOException;

    /** Serialize using the category's format; atomically persist before returning. */
    void save(StorageCategory category, String id, JsonNode document) throws IOException;

    /** True if deleted, false if not found. */
    boolean delete(StorageCategory category, String id) throws IOException;

    /** Return document IDs without file extensions. */
    List<String> listIds(StorageCategory category) throws IOException;

    boolean exists(StorageCategory category, String id) throws IOException;

    /** Release resources; the save contract does not require a buffered shutdown flush. */
    @Override
    void close();
}
