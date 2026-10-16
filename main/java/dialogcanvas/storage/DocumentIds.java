package dialogcanvas.storage;

import java.util.Set;

public final class DocumentIds {
    private DocumentIds() {}
    private static final Set<String> RESERVED = Set.of("validate", "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
    public static boolean valid(String id) {
        return id != null && id.matches("[a-z0-9_-]{1,64}") && !RESERVED.contains(id);
    }
    public static void require(String id) { if (!valid(id)) throw new IllegalArgumentException("Invalid document ID"); }
}
