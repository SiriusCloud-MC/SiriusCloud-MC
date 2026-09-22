package dev.sirius.cloud.driver.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

/** Pretty-printed JSON persistence for node state. */
public final class JsonConfig {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private JsonConfig() {
    }

    public static Gson gson() {
        return GSON;
    }

    /** Loads the file, or writes and returns the supplied defaults if it is absent. */
    public static <T> T loadOrCreate(Path path, Class<T> type, Supplier<T> defaults) throws IOException {
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                T loaded = GSON.fromJson(reader, type);
                if (loaded != null) {
                    return loaded;
                }
            }
        }
        T value = defaults.get();
        save(path, value);
        return value;
    }

    public static <T> T load(Path path, Class<T> type) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, type);
        }
    }

    /**
     * Writes via a temporary file so a crash mid-write cannot leave truncated
     * JSON behind.
     *
     * <p>Atomic replacement is attempted first and falls back to a plain
     * replace: NTFS supports it, but network shares and some Windows setups
     * throw {@link AtomicMoveNotSupportedException}, and a config write is not
     * worth failing a startup over.
     */
    public static void save(Path path, Object value) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(value, writer);
        }

        try {
            Files.move(temporary, path,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
