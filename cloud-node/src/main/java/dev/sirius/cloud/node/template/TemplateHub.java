package dev.sirius.cloud.node.template;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.driver.sync.ContentIndex;
import dev.sirius.cloud.node.wrapper.ConnectedWrapper;
import dev.sirius.cloud.node.wrapper.WrapperRegistry;
import dev.sirius.cloud.protocol.packet.impl.TemplateSyncPacket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The network's copy of every template, in {@code node/templates/}, and the
 * place wrappers keep theirs in step with.
 *
 * <p>A wrapper that sees one of its template files change uploads it here;
 * this stores it and passes it on to every other wrapper. So a template can
 * be edited on whichever machine is handy and ends up on all of them. In a
 * cluster the folder is replicated to every node like the rest of the
 * network's data, so the next leader has the same templates.
 *
 * <p>Files can also be put into {@code node/templates/} directly. A scan every
 * few seconds notices that - and changes that arrived from the cluster - and
 * passes them on the same way.
 *
 * <p>Everything runs on one thread, so uploads, scans and broadcasts never
 * interleave.
 */
public final class TemplateHub {

    private static final CloudLogger LOGGER = CloudLogger.of("Templates");
    static final int CHUNK = 1024 * 1024;

    private final ContentIndex index;
    private final WrapperRegistry wrappers;
    private final Consumer<String> groupChanged;
    private final ScheduledExecutorService lane = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sirius-templates");
        thread.setDaemon(true);
        return thread;
    });

    /** What wrappers were last told, to notice what changed on disk since. */
    private Map<String, String> announced = Map.of();

    /**
     * @param groupChanged called for groups whose templates changed on disk here - by
     *                     hand or through the cluster - so they can be rolled over.
     *                     Uploads are reported by the wrapper that made the change.
     */
    public TemplateHub(Path templates, WrapperRegistry wrappers, Consumer<String> groupChanged) {
        this.index = new ContentIndex(templates, List.of(), TemplateHub::shared);
        this.wrappers = wrappers;
        this.groupChanged = groupChanged;
    }

    /** Template files, minus the README the wrapper writes into new template folders. */
    public static boolean shared(String relative) {
        return !relative.endsWith("README.txt");
    }

    public void start() {
        lane.execute(() -> {
            try {
                Files.createDirectories(index.root());
                announced = index.manifest();
            } catch (IOException exception) {
                LOGGER.warn("Cannot read node/templates: {}", exception.getMessage());
            }
        });
        lane.scheduleWithFixedDelay(this::scan, 5, 5, TimeUnit.SECONDS);
    }

    public void close() {
        lane.shutdownNow();
    }

    public void onPacket(ConnectedWrapper from, TemplateSyncPacket packet) {
        lane.execute(() -> {
            try {
                handle(from, packet);
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Template sync with {} failed: {}", from.name(), exception.getMessage());
            }
        });
    }

    private void handle(ConnectedWrapper from, TemplateSyncPacket packet) throws IOException {
        JsonObject body = JsonParser.parseString(packet.body()).getAsJsonObject();
        switch (packet.kind()) {
            case "hello" -> {
                JsonObject entries = new JsonObject();
                index.manifest().forEach(entries::addProperty);
                JsonObject manifest = new JsonObject();
                manifest.add("entries", entries);
                from.send(new TemplateSyncPacket("manifest", manifest.toString()));
            }
            case "fetch" -> {
                for (JsonElement path : body.getAsJsonArray("paths")) {
                    sendFile(from, path.getAsString());
                }
                from.send(new TemplateSyncPacket("fetch-done", "{}"));
            }
            case "upload" -> {
                String path = body.get("path").getAsString();
                if (!index.accepts(path)) {
                    return;
                }
                index.append(path, packet.data(), body.get("first").getAsBoolean());
                if (body.get("last").getAsBoolean()) {
                    index.finish(path);
                    String hash = index.hash(path);
                    LOGGER.info("{} changed {}", from.name(), path);
                    remember(path, hash);
                    JsonObject entries = new JsonObject();
                    entries.addProperty(path, hash);
                    JsonObject changed = new JsonObject();
                    changed.add("entries", entries);
                    broadcast(from, new TemplateSyncPacket("changed", changed.toString()));
                }
            }
            case "delete" -> {
                JsonArray deleted = new JsonArray();
                for (JsonElement path : body.getAsJsonArray("paths")) {
                    if (index.accepts(path.getAsString())) {
                        index.delete(path.getAsString());
                        remember(path.getAsString(), null);
                        deleted.add(path);
                        LOGGER.info("{} deleted {}", from.name(), path.getAsString());
                    }
                }
                JsonObject message = new JsonObject();
                message.add("paths", deleted);
                broadcast(from, new TemplateSyncPacket("deleted", message.toString()));
            }
            default -> LOGGER.debug("Unknown template message '{}' from {}", packet.kind(), from.name());
        }
    }

    private void remember(String path, String hash) {
        Map<String, String> updated = new HashMap<>(announced);
        if (hash == null) {
            updated.remove(path);
        } else {
            updated.put(path, hash);
        }
        announced = updated;
    }

    /** Changes on disk that no wrapper made: edits in node/templates, or ones the cluster replicated here. */
    private void scan() {
        Map<String, String> now;
        try {
            now = index.manifest();
        } catch (IOException exception) {
            LOGGER.debug("Template scan failed: {}", exception.getMessage());
            return;
        }
        if (now.equals(announced)) {
            return;
        }
        JsonObject changed = new JsonObject();
        JsonArray deleted = new JsonArray();
        Set<String> groups = new LinkedHashSet<>();
        now.forEach((path, hash) -> {
            if (!hash.equals(announced.get(path))) {
                changed.addProperty(path, hash);
                groups.add(group(path));
            }
        });
        for (String path : announced.keySet()) {
            if (!now.containsKey(path)) {
                deleted.add(path);
                groups.add(group(path));
            }
        }
        announced = now;
        if (!changed.isEmpty()) {
            JsonObject message = new JsonObject();
            message.add("entries", changed);
            broadcast(null, new TemplateSyncPacket("changed", message.toString()));
        }
        if (!deleted.isEmpty()) {
            JsonObject message = new JsonObject();
            message.add("paths", deleted);
            broadcast(null, new TemplateSyncPacket("deleted", message.toString()));
        }
        groups.forEach(group -> {
            LOGGER.info("Template of {} changed on the node", group);
            groupChanged.accept(group);
        });
    }

    private void broadcast(ConnectedWrapper except, TemplateSyncPacket packet) {
        for (ConnectedWrapper wrapper : wrappers.all()) {
            if (wrapper != except) {
                wrapper.send(packet);
            }
        }
    }

    private void sendFile(ConnectedWrapper to, String path) {
        byte[] content;
        try {
            content = index.read(path);
        } catch (NoSuchFileException | IllegalArgumentException gone) {
            JsonObject body = new JsonObject();
            body.addProperty("path", path);
            to.send(new TemplateSyncPacket("gone", body.toString()));
            return;
        } catch (IOException exception) {
            LOGGER.warn("Cannot read template file {}: {}", path, exception.getMessage());
            return;
        }
        for (Chunk chunk : chunks(path, content)) {
            to.send(new TemplateSyncPacket("file", chunk.header().toString(), chunk.data()));
        }
    }

    /** A file in pieces small enough for one packet each; an empty file is one empty piece. */
    public record Chunk(JsonObject header, byte[] data) {
    }

    public static List<Chunk> chunks(String path, byte[] content) {
        List<Chunk> chunks = new ArrayList<>();
        int offset = 0;
        do {
            int length = Math.min(CHUNK, content.length - offset);
            JsonObject header = new JsonObject();
            header.addProperty("path", path);
            header.addProperty("first", offset == 0);
            header.addProperty("last", offset + length >= content.length);
            chunks.add(new Chunk(header, Arrays.copyOfRange(content, offset, offset + length)));
            offset += length;
        } while (offset < content.length);
        return chunks;
    }

    /** {@code Lobby/default/plugins/x.jar} belongs to {@code Lobby}; {@code global/...} to every group. */
    static String group(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }
}
