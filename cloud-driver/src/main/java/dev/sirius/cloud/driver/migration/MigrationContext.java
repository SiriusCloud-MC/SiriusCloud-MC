package dev.sirius.cloud.driver.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.sirius.cloud.driver.config.JsonConfig;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a migration may touch, and the record of what it did.
 *
 * <p>Paths are relative to the install's directory ({@code node/} or
 * {@code wrapper/}). Every file is copied to this migration's own backup
 * folder before its first change, so a migration that fails part way can be
 * rolled back without undoing the ones that already succeeded.
 */
public final class MigrationContext {

    private final Path root;
    private final Path backup;
    private final Set<String> backedUp = new LinkedHashSet<>();
    private final Set<String> created = new LinkedHashSet<>();
    private final List<String> changes = new ArrayList<>();

    MigrationContext(Path root, Path backup) {
        this.root = root;
        this.backup = backup;
    }

    /** The install's directory, for migrations that need to look around. */
    public Path root() {
        return root;
    }

    public boolean exists(String relative) {
        return Files.exists(resolve(relative));
    }

    /** The file as JSON, or empty if it does not exist. */
    public Optional<JsonElement> readJson(String relative) throws IOException {
        Path file = resolve(relative);
        if (Files.notExists(file)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return Optional.of(JsonParser.parseReader(reader));
        } catch (JsonParseException exception) {
            throw new IOException(relative + " is not valid JSON: " + exception.getMessage(), exception);
        }
    }

    /** Replaces the file, backing up what was there first. */
    public void writeJson(String relative, JsonElement value) throws IOException {
        Path file = resolve(relative);
        backUp(relative, file);
        JsonConfig.save(file, value);
    }

    /** Records one human-readable change, shown in the log and kept in the history. */
    public void note(String change) {
        changes.add(change);
    }

    List<String> changes() {
        return List.copyOf(changes);
    }

    /** Puts every file this migration touched back the way it was. */
    void rollBack() throws IOException {
        for (String relative : backedUp) {
            Files.copy(backup.resolve(relative), resolve(relative), StandardCopyOption.REPLACE_EXISTING);
        }
        for (String relative : created) {
            Files.deleteIfExists(resolve(relative));
        }
    }

    private void backUp(String relative, Path file) throws IOException {
        if (backedUp.contains(relative) || created.contains(relative)) {
            return;
        }
        if (Files.notExists(file)) {
            created.add(relative);
            return;
        }
        Path copy = backup.resolve(relative);
        Files.createDirectories(copy.getParent());
        Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
        backedUp.add(relative);
    }

    private Path resolve(String relative) {
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException(relative + " is outside " + root);
        }
        return file;
    }
}
