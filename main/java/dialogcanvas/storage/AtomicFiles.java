package dialogcanvas.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Writes in the target directory and refuses a non-atomic replacement. */
public final class AtomicFiles {
    @FunctionalInterface
    public interface Replacement { void write(Path target, byte[] content) throws IOException; }
    private AtomicFiles() {
    }

    public static void write(Path target, byte[] content) throws IOException {
        write(target, content, (source, destination) -> Files.move(source, destination,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    @FunctionalInterface
    interface AtomicMove { void replace(Path source, Path target) throws IOException; }

    /** Move fault injection; production never falls back to a non-atomic replacement. */
    static void write(Path target, byte[] content, AtomicMove move) throws IOException {
        write(target, content, move, () -> {});
    }

    /** Recheck manual YAML after the complete temporary file has been forced, just before replacement. */
    public static void writeIfUnchanged(Path target, byte[] content, byte[] expected) throws IOException {
        write(target, content, (source, destination) -> Files.move(source, destination,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING), () -> requireUnchanged(target, expected));
    }

    @FunctionalInterface
    private interface Check { void run() throws IOException; }

    private static void write(Path target, byte[] content, AtomicMove move, Check check) throws IOException {
        Path absoluteTarget = target.toAbsolutePath().normalize();
        requireTarget(absoluteTarget);
        Path temporary = Files.createTempFile(absoluteTarget.getParent(), ".dcmenu-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            requireTarget(absoluteTarget);
            check.run();
            move.replace(temporary, absoluteTarget);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public static void requireUnchanged(Path target, byte[] expected) throws IOException {
        requireTarget(target);
        boolean present = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (expected == null) {
            if (present) throw new IOException("Manual document changed during commit");
        } else {
            if (!present || Files.size(target) != expected.length) throw new IOException("Manual document changed during commit");
            try (var stream = Files.newInputStream(target)) {
                if (!Arrays.equals(expected, stream.readNBytes(expected.length + 1)))
                    throw new IOException("Manual document changed during commit");
            }
        }
    }

    /** Check ancestors before creating directories, including Windows junction redirection. */
    public static void createDirectories(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        Path existing = absolute;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            if (existing == null) throw new IOException("Storage has no accessible filesystem root");
        }
        requireDirectory(existing);
        Files.createDirectories(absolute);
        requireDirectory(absolute);
    }

    public static void requireDirectory(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
                || !absolute.toRealPath().equals(absolute)) throw new IOException("Unsafe storage directory");
    }

    /** Reads need the same boundary checks as atomic replacement. */
    public static void requireTarget(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        requireDirectory(absolute.getParent());
        if (Files.isSymbolicLink(absolute) || (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS))) throw new IOException("Unsafe storage file");
    }
}
