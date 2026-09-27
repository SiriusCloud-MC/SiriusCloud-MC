package dev.sirius.cloud.module.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Loads a module's JSON config, writing it back so new fields appear after an upgrade. */
public final class ModuleConfigs {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ModuleConfigs() {
    }

    /**
     * Reads the file, or the defaults if it is missing, and writes the result
     * back - so a setting added in a newer version shows up in the file with
     * its default, rather than being invisible until somebody reads the source.
     */
    public static <T> T load(Path file, Class<T> type, Supplier<T> defaults) throws IOException {
        T config = null;
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                config = GSON.fromJson(reader, type);
            }
        }
        if (config == null) {
            config = defaults.get();
        }
        save(file, config);
        return config;
    }

    public static void save(Path file, Object config) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        }
    }
}
