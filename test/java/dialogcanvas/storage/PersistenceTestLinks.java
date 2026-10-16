package dialogcanvas.storage;

import java.io.IOException;
import java.nio.file.*;

/** Both ends stay in a disposable fixture; Windows junctions need no symlink privilege. */
public final class PersistenceTestLinks {
    private PersistenceTestLinks() {}
    public static void redirectDirectory(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    link.toAbsolutePath().toString(), target.toAbsolutePath().toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) throw new IOException("Fixture junction creation failed: " + output);
        } else Files.createSymbolicLink(link, target);
    }
}
