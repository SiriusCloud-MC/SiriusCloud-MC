package dev.sirius.cloud.wrapper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.driver.config.JsonConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What this wrapper has learned about the node cluster, kept in
 * {@code wrapper/local/cluster.json}: every node's address, and the highest
 * leader term seen.
 *
 * <p>The addresses let a wrapper that restarts while its configured node is
 * down still find the leader. The term is what keeps it from ever taking
 * orders from a leader that has been replaced: such a node reports an older
 * term, and is refused. Deleting the file is safe and forgets both.
 */
final class ClusterMemory {

    private static final CloudLogger LOGGER = CloudLogger.of("Wrapper");

    private final Path file;
    private List<String> endpoints = new ArrayList<>();
    private long term;

    private ClusterMemory(Path file) {
        this.file = file;
    }

    static ClusterMemory load(Path file) {
        ClusterMemory memory = new ClusterMemory(file);
        if (Files.exists(file)) {
            try {
                JsonObject stored = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (stored.has("endpoints")) {
                    stored.getAsJsonArray("endpoints").forEach(entry -> memory.endpoints.add(entry.getAsString()));
                }
                memory.term = stored.has("term") ? stored.get("term").getAsLong() : 0;
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Ignoring unreadable {}: {}", file, exception.getMessage());
            }
        }
        return memory;
    }

    synchronized List<String> endpoints() {
        return List.copyOf(endpoints);
    }

    synchronized long term() {
        return term;
    }

    synchronized void endpoints(List<String> learned) {
        this.endpoints = new ArrayList<>(learned);
        save();
    }

    synchronized void term(long seen) {
        this.term = Math.max(term, seen);
        save();
    }

    private void save() {
        JsonObject stored = new JsonObject();
        JsonArray list = new JsonArray();
        endpoints.forEach(list::add);
        stored.add("endpoints", list);
        stored.addProperty("term", term);
        try {
            JsonConfig.save(file, stored);
        } catch (IOException exception) {
            LOGGER.warn("Could not save {}: {}", file, exception.getMessage());
        }
    }
}
