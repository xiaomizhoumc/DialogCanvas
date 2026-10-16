package dialogcanvas.storage;

/** Defines each document category's directory and serialization format. */
public enum StorageCategory {
    MENU("menus", ".json"),
    TEMPLATE("templates", ".yml"),
    HISTORY("history", ".json"),
    PLAYER_DATA("players", ".json");

    private final String directoryName;
    private final String extension;

    StorageCategory(String directoryName, String extension) {
        this.directoryName = directoryName;
        this.extension = extension;
    }

    public String directoryName() {
        return directoryName;
    }

    public String extension() {
        return extension;
    }
}
