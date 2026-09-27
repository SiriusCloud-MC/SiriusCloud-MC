package dev.sirius.cloud.node.database;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * One JSON file per document, one directory per collection.
 *
 * <p>The default, because it needs nothing installed. Fine for a single node
 * and a few thousand players; beyond that, or with more than one node, use a
 * real database.
 *
 * <p>File names encode the key rather than using it directly, and the encoding
 * is chosen for Windows as much as for Linux. Keys routinely contain characters
 * one filesystem or the other forbids ({@code :} for a start), and Windows is
 * case-insensitive - so {@code Steve} and {@code steve} would silently be the
 * same file there and different files here. Upper-case letters are therefore
 * escaped too, which keeps two different keys two different files everywhere.
 */
final class JsonFileBackend implements DatabaseBackend {

    private final Path root;

    JsonFileBackend(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
    }

    @Override
    public String name() {
        return "json";
    }

    @Override
    public Optional<String> get(String collection, String key) throws IOException {
        Path file = file(collection, key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
    }

    @Override
    public void put(String collection, String key, String document) throws IOException {
        Path file = file(collection, key);
        Files.createDirectories(file.getParent());

        // A unique temporary name, so two writers of the same key cannot both
        // be halfway through the same temporary file; the move makes the
        // winner's document appear whole or not at all.
        Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Files.writeString(temporary, document, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public boolean delete(String collection, String key) throws IOException {
        return Files.deleteIfExists(file(collection, key));
    }

    @Override
    public Map<String, String> all(String collection) throws IOException {
        Path directory = root.resolve(collection);
        Map<String, String> documents = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) {
            return documents;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(this::isDocument).sorted().toList()) {
                String name = file.getFileName().toString();
                String key = decode(name.substring(0, name.length() - ".json".length()));
                documents.put(key, Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return documents;
    }

    @Override
    public long count(String collection) throws IOException {
        Path directory = root.resolve(collection);
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(this::isDocument).count();
        }
    }

    @Override
    public void close() {
        // Nothing held open between calls.
    }

    private boolean isDocument(Path file) {
        return Files.isRegularFile(file) && file.getFileName().toString().endsWith(".json");
    }

    private Path file(String collection, String key) {
        return root.resolve(collection).resolve(encode(key) + ".json");
    }

    /** Lower-case letters, digits, '-' and '_' pass through; every other byte becomes %XX. */
    static String encode(String key) {
        StringBuilder builder = new StringBuilder(key.length() + 8);
        for (byte raw : key.getBytes(StandardCharsets.UTF_8)) {
            int b = raw & 0xFF;
            boolean plain = (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9') || b == '-' || b == '_';
            if (plain) {
                builder.append((char) b);
            } else {
                builder.append('%').append(Character.toUpperCase(Character.forDigit(b >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return builder.toString();
    }

    static String decode(String encoded) {
        byte[] out = new byte[encoded.length()];
        int length = 0;
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%' && i + 2 < encoded.length()) {
                out[length++] = (byte) Integer.parseInt(encoded.substring(i + 1, i + 3), 16);
                i += 2;
            } else {
                out[length++] = (byte) c;
            }
        }
        return new String(out, 0, length, StandardCharsets.UTF_8);
    }
}
