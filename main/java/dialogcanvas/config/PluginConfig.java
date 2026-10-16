package dialogcanvas.config;

import dialogcanvas.storage.AtomicFiles;
import dialogcanvas.engine.retention.RetentionConfig;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import at.favre.lib.crypto.bcrypt.BCrypt;
import at.favre.lib.crypto.bcrypt.IllegalBCryptFormatException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Immutable configuration loaded with a pure data parser on a file worker. */
public record PluginConfig(int port, String bindAddress, String passwordHash, Path dataDirectory, RetentionConfig retention) {

    public PluginConfig(int port, String bindAddress, String passwordHash, Path dataDirectory) {
        this(port, bindAddress, passwordHash, dataDirectory, RetentionConfig.defaults());
    }

    public PluginConfig {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (!"127.0.0.1".equals(bindAddress)) {
            throw new IllegalArgumentException("bind-address must be 127.0.0.1");
        }
        validatePasswordHash(passwordHash);
        if (dataDirectory == null) {
            throw new IllegalArgumentException("data-directory is required");
        }
        dataDirectory = dataDirectory.toAbsolutePath().normalize();
        java.util.Objects.requireNonNull(retention);
    }

    public static PluginConfig load(Path pluginDirectory)
            throws IOException {
        AtomicFiles.createDirectories(pluginDirectory);
        Path root = pluginDirectory.toRealPath();
        Path configFile = root.resolve("config.yml");
        AtomicFiles.requireTarget(configFile);
        if (!Files.exists(configFile)) {
            try (InputStream defaults = PluginConfig.class.getResourceAsStream("/config.yml")) {
                if (defaults == null) {
                    throw new IOException("Missing bundled config.yml");
                }
                AtomicFiles.writeIfUnchanged(configFile, defaults.readAllBytes(), null);
            }
        }

        Map<?, ?> yaml = parseConfigurationYaml(Files.readString(configFile, StandardCharsets.UTF_8));
        if (!(yaml.get("port") instanceof Integer port)) {
            throw new IllegalArgumentException("port must be an integer");
        }
        String directory = string(yaml, "data-directory", null);
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("data-directory must be a relative directory");
        }
        Path relative = Path.of(directory);
        Path dataDirectory = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !dataDirectory.startsWith(root)) {
            throw new IllegalArgumentException("data-directory must remain inside the plugin directory");
        }
        return new PluginConfig(port, string(yaml, "bind-address", null),
                string(yaml, "password-hash", ""), dataDirectory, RetentionConfig.parse(yaml.get("player-data-retention")));
    }
    /** Validate without running an expensive password verification or exposing the supplied hash. */
    public static void validatePasswordHash(String hash) {
        if (hash == null || (!hash.isEmpty()
                && !hash.matches("\\$2[aby]\\$(0[4-9]|1[0-6])\\$[./A-Za-z0-9]{53}"))) {
            throw new IllegalArgumentException("password-hash must be empty or a BCrypt hash with cost 4..16");
        }
        if (!hash.isEmpty()) {
            try { BCrypt.Version.VERSION_2B.parser.parse(hash.getBytes(StandardCharsets.US_ASCII)); }
            catch (IllegalBCryptFormatException failure) {
                // The parser's exception may include the supplied hash; do not chain it.
                throw new IllegalArgumentException("password-hash has an invalid BCrypt format");
            }
        }
    }

    /** Raw SnakeYAML exceptions include source snippets, which can contain password-hash. */
    public static Map<?, ?> parseConfigurationYaml(String content) {
        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
        Object loaded;
        try { loaded = new Yaml(new SafeConstructor(options)).load(content); }
        catch (YAMLException failure) {
            throw new IllegalArgumentException("config.yml contains invalid YAML; check syntax and duplicate keys");
        }
        if (!(loaded instanceof Map<?, ?> yaml)) throw new IllegalArgumentException("config.yml must be an object");
        return yaml;
    }

    @Override public String toString() {
        return "PluginConfig[port=" + port + ", bindAddress=" + bindAddress
                + ", passwordHash=<redacted>, dataDirectory=" + dataDirectory + "]";
    }
    private static String string(Map<?, ?> yaml, String key, String fallback) {
        Object value = yaml.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " must be a string");
        return text;
    }
}
