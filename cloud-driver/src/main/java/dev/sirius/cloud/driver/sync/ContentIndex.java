package dev.sirius.cloud.driver.sync;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A set of files under one directory and their content hashes, for keeping
 * copies of it identical elsewhere: the cluster's replicated data, and
 * templates shared between wrappers.
 *
 * <p>Paths are relative, with forward slashes on every platform. Hashes are
 * cached by size and modification time, so a scan of thousands of unchanged
 * files costs a {@code stat} each rather than a read. Writes go through a
 * {@code .part} file and a rename, so nobody ever reads half a file.
 */
public final class ContentIndex {

    private record Cached(long size, long modified, String hash) {
    }

    private final Path root;
    private final List<String> roots;
    private final Predicate<String> accept;
    private final Map<String, Cached> cache = new HashMap<>();

    /**
     * @param roots  directories and files under {@code root} to index; empty for all of it
     * @param accept which relative paths belong to the set; the only ones written or deleted
     */
    public ContentIndex(Path root, List<String> roots, Predicate<String> accept) {
        this.root = root.toAbsolutePath().normalize();
        this.roots = roots;
        this.accept = accept;
    }

    public Path root() {
        return root;
    }

    public boolean accepts(String relative) {
        return !relative.contains("..") && !relative.startsWith("/") && !relative.endsWith(".part")
                && accept.test(relative);
    }

    /** Every file in the set and its hash, sorted by path. */
    public synchronized Map<String, String> manifest() throws IOException {
        Map<String, String> manifest = new TreeMap<>();
        for (String prefix : roots.isEmpty() ? List.of("") : roots) {
            Path start = prefix.isEmpty() ? root : root.resolve(prefix);
            if (Files.notExists(start)) {
                continue;
            }
            if (Files.isRegularFile(start)) {
                add(manifest, start);
                continue;
            }
            try (Stream<Path> walk = Files.walk(start)) {
                for (Path file : (Iterable<Path>) walk::iterator) {
                    if (Files.isRegularFile(file)) {
                        add(manifest, file);
                    }
                }
            } catch (UncheckedIOException exception) {
                // A file removed while walking; the next scan sees the new state.
                if (!(exception.getCause() instanceof NoSuchFileException)) {
                    throw exception.getCause();
                }
            }
        }
        cache.keySet().retainAll(manifest.keySet());
        return manifest;
    }

    /** The hash of one file, or null if it is not there. */
    public synchronized String hash(String relative) throws IOException {
        Path file = resolve(relative);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Map<String, String> one = new TreeMap<>();
        add(one, file);
        return one.get(relative);
    }

    private void add(Map<String, String> manifest, Path file) throws IOException {
        String relative = relative(file);
        if (!accepts(relative)) {
            return;
        }
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException gone) {
            return;
        }
        Cached cached = cache.get(relative);
        long modified = attributes.lastModifiedTime().toMillis();
        if (cached == null || cached.size() != attributes.size() || cached.modified() != modified) {
            String hash;
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), sha256())) {
                in.transferTo(OutputStreamNull.INSTANCE);
                hash = HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest(), 0, 16);
            } catch (NoSuchFileException gone) {
                return;
            }
            cached = new Cached(attributes.size(), modified, hash);
            cache.put(relative, cached);
        }
        manifest.put(relative, cached.hash());
    }

    public byte[] read(String relative) throws IOException {
        return Files.readAllBytes(resolve(relative));
    }

    /** Writes through a temporary file, so a reader never sees half a file. */
    public void write(String relative, byte[] content) throws IOException {
        append(relative, content, true);
        finish(relative);
    }

    /** Streams a large file in, piece by piece; {@link #finish} puts it in place. */
    public void append(String relative, byte[] chunk, boolean first) throws IOException {
        Path target = resolve(relative);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        if (first) {
            Files.write(temporary, chunk);
        } else {
            Files.write(temporary, chunk, StandardOpenOption.APPEND);
        }
    }

    public void finish(String relative) throws IOException {
        Path target = resolve(relative);
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void delete(String relative) throws IOException {
        Files.deleteIfExists(resolve(relative));
    }

    private Path resolve(String relative) {
        if (!accepts(relative)) {
            throw new IllegalArgumentException("Not part of this set: " + relative);
        }
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Outside " + root + ": " + relative);
        }
        return file;
    }

    private String relative(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public static String hash(byte[] content) {
        return HexFormat.of().formatHex(sha256().digest(content), 0, 16);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Discards what it is given; the digest is what reading is for. */
    private static final class OutputStreamNull extends java.io.OutputStream {
        static final OutputStreamNull INSTANCE = new OutputStreamNull();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
