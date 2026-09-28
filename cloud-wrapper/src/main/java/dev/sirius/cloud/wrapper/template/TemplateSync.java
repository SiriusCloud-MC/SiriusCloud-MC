package dev.sirius.cloud.wrapper.template;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.driver.config.JsonConfig;
import dev.sirius.cloud.driver.sync.ContentIndex;
import dev.sirius.cloud.protocol.packet.Packet;
import dev.sirius.cloud.protocol.packet.impl.TemplateSyncPacket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Keeps this wrapper's templates the same as every other wrapper's, through
 * the node.
 *
 * <p>Edit a template on any machine: this wrapper uploads the change, the node
 * keeps it and passes it on to the others. Changes made elsewhere arrive here
 * the same way.
 *
 * <p>Deciding which way a file goes needs three versions of it - this
 * wrapper's, the node's, and the one both had the last time they agreed,
 * which is remembered in {@code local/template-sync.json}:
 * <ul>
 *   <li>only this side changed it since: upload (or delete it on the node);</li>
 *   <li>only the node's side changed: download (or delete it here);</li>
 *   <li>both changed it: the node's copy wins, and the conflict is logged.</li>
 * </ul>
 * That is what lets a wrapper that was offline tell its own edits from other
 * machines' when it comes back, rather than one side overwriting the other.
 *
 * <p>Everything runs on one thread. Services wait for downloads in progress
 * before they start ({@link #awaitQuiet}), so a server started right after a
 * change never gets half the old template.
 */
public final class TemplateSync {

    private static final CloudLogger LOGGER = CloudLogger.of("Templates");
    private static final int CHUNK = 1024 * 1024;

    private final ContentIndex local;
    private final Path stateFile;
    private final Consumer<Packet> send;
    private final Consumer<String> groupChanged;
    private final ExecutorService lane = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sirius-template-sync");
        thread.setDaemon(true);
        return thread;
    });

    /** The last version both sides agreed on. Lane only. */
    private final Map<String, String> synced = new HashMap<>();
    /** The node's versions, as last heard. Lane only. */
    private final Map<String, String> remote = new HashMap<>();
    private boolean haveRemote;

    /** Downloads asked for and not yet complete. Guarded by itself. */
    private final Set<String> fetching = new LinkedHashSet<>();

    /**
     * @param groupChanged called for each group this wrapper changed, so the node rolls it over
     */
    public TemplateSync(Path templates, Path stateFile, Consumer<Packet> send, Consumer<String> groupChanged) {
        this.local = new ContentIndex(templates, List.of(), path -> !path.endsWith("README.txt"));
        this.stateFile = stateFile;
        this.send = send;
        this.groupChanged = groupChanged;
        loadState();
    }

    // ------------------------------------------------------------- lifecycle

    /** Authenticated with the node: ask for its list, and reconcile when it comes. */
    public void connected() {
        lane.execute(() -> send.accept(new TemplateSyncPacket("hello", "{}")));
    }

    public void disconnected() {
        lane.execute(() -> {
            haveRemote = false;
            synchronized (fetching) {
                fetching.clear();
                fetching.notifyAll();
            }
        });
    }

    public void close() {
        lane.shutdownNow();
    }

    /** The template watcher saw files change here. */
    public void localChanged() {
        lane.execute(() -> guard(() -> {
            if (haveRemote) {
                reconcile(false);
            }
        }));
    }

    /**
     * Waits, up to a limit, until no download is in progress. Called before a
     * service is started from the templates.
     */
    public void awaitQuiet(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (fetching) {
            while (!fetching.isEmpty()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    LOGGER.warn("Starting anyway; {} template file(s) are still downloading", fetching.size());
                    return;
                }
                try {
                    fetching.wait(left);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public void onPacket(TemplateSyncPacket packet) {
        lane.execute(() -> guard(() -> handle(packet)));
    }

    // -------------------------------------------------------------- messages

    private void handle(TemplateSyncPacket packet) throws IOException {
        JsonObject body = JsonParser.parseString(packet.body()).getAsJsonObject();
        switch (packet.kind()) {
            case "manifest" -> {
                remote.clear();
                body.getAsJsonObject("entries").entrySet()
                        .forEach(entry -> remote.put(entry.getKey(), entry.getValue().getAsString()));
                haveRemote = true;
                reconcile(true);
            }
            case "changed" -> {
                List<String> wanted = new ArrayList<>();
                for (Map.Entry<String, JsonElement> entry : body.getAsJsonObject("entries").entrySet()) {
                    String path = entry.getKey();
                    String hash = entry.getValue().getAsString();
                    remote.put(path, hash);
                    String mine = local.hash(path);
                    if (hash.equals(mine)) {
                        synced.put(path, hash);
                        continue;
                    }
                    if (mine != null && !Objects.equals(mine, synced.get(path))) {
                        keepConflict(path);
                    }
                    wanted.add(path);
                }
                fetch(wanted);
                saveState();
            }
            case "deleted" -> {
                for (JsonElement element : body.getAsJsonArray("paths")) {
                    String path = element.getAsString();
                    remote.remove(path);
                    synced.remove(path);
                    if (local.accepts(path)) {
                        local.delete(path);
                        LOGGER.info("Deleted {} (deleted elsewhere)", path);
                    }
                }
                saveState();
            }
            case "file" -> {
                String path = body.get("path").getAsString();
                if (!local.accepts(path)) {
                    return;
                }
                local.append(path, packet.data(), body.get("first").getAsBoolean());
                if (body.get("last").getAsBoolean()) {
                    local.finish(path);
                    synced.put(path, local.hash(path));
                    saveState();
                    done(path);
                    LOGGER.info("Updated {}", path);
                }
            }
            case "gone" -> done(body.get("path").getAsString());
            case "fetch-done" -> {
                // Anything asked for and not sent no longer exists on the node.
                synchronized (fetching) {
                    fetching.clear();
                    fetching.notifyAll();
                }
            }
            default -> LOGGER.debug("Unknown template message '{}'", packet.kind());
        }
    }

    /**
     * Brings both sides together, file by file, by the three-version rule.
     *
     * @param initial right after connecting: uploads then only fill the node in,
     *                and do not roll groups over - nothing changed for this
     *                wrapper's services
     */
    private void reconcile(boolean initial) throws IOException {
        Map<String, String> mine = local.manifest();
        Set<String> paths = new TreeSet<>();
        paths.addAll(mine.keySet());
        paths.addAll(remote.keySet());
        paths.addAll(synced.keySet());

        List<String> uploads = new ArrayList<>();
        List<String> remoteDeletes = new ArrayList<>();
        List<String> downloads = new ArrayList<>();
        List<String> localDeletes = new ArrayList<>();

        for (String path : paths) {
            String here = mine.get(path);
            String there = remote.get(path);
            String agreed = synced.get(path);
            if (Objects.equals(here, there)) {
                if (here == null) {
                    synced.remove(path);
                } else {
                    synced.put(path, here);
                }
                continue;
            }
            boolean changedHere = !Objects.equals(here, agreed);
            boolean changedThere = !Objects.equals(there, agreed);
            if (changedHere && !changedThere) {
                (here == null ? remoteDeletes : uploads).add(path);
            } else {
                // Both changed it since they last agreed. On a first sync
                // there was no agreement yet, so that is not a conflict.
                if (changedHere && here != null && (agreed != null || !initial)) {
                    keepConflict(path);
                }
                (there == null ? localDeletes : downloads).add(path);
            }
        }

        for (String path : uploads) {
            upload(path);
            String hash = mine.get(path);
            remote.put(path, hash);
            synced.put(path, hash);
        }
        if (!remoteDeletes.isEmpty()) {
            JsonArray list = new JsonArray();
            remoteDeletes.forEach(list::add);
            JsonObject body = new JsonObject();
            body.add("paths", list);
            send.accept(new TemplateSyncPacket("delete", body.toString()));
            remoteDeletes.forEach(path -> {
                remote.remove(path);
                synced.remove(path);
            });
        }
        for (String path : localDeletes) {
            local.delete(path);
            synced.remove(path);
        }
        fetch(downloads);
        saveState();

        if (!uploads.isEmpty() || !remoteDeletes.isEmpty() || !downloads.isEmpty() || !localDeletes.isEmpty()) {
            LOGGER.info("Templates: {} sent, {} deleted on the network, {} received, {} deleted here",
                    uploads.size(), remoteDeletes.size(), downloads.size(), localDeletes.size());
        }
        if (!initial) {
            Set<String> groups = new LinkedHashSet<>();
            uploads.forEach(path -> groups.add(group(path)));
            remoteDeletes.forEach(path -> groups.add(group(path)));
            groups.forEach(groupChanged);
        }
    }

    /**
     * This side's copy of a file both sides changed is about to be replaced
     * by the network's. It is kept, outside the templates, rather than lost.
     */
    private void keepConflict(String path) {
        Path copy = stateFile.resolveSibling("template-conflicts").resolve(path + "."
                + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        try {
            Files.createDirectories(copy.getParent());
            Files.write(copy, local.read(path));
            LOGGER.warn("{} was changed here and elsewhere; the network's copy wins. This machine's copy is kept"
                    + " in {}", path, copy);
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.warn("{} was changed here and elsewhere; the network's copy wins", path);
        }
    }

    private void upload(String path) throws IOException {
        byte[] content = local.read(path);
        int offset = 0;
        do {
            int length = Math.min(CHUNK, content.length - offset);
            JsonObject header = new JsonObject();
            header.addProperty("path", path);
            header.addProperty("first", offset == 0);
            header.addProperty("last", offset + length >= content.length);
            send.accept(new TemplateSyncPacket("upload", header.toString(),
                    Arrays.copyOfRange(content, offset, offset + length)));
            offset += length;
        } while (offset < content.length);
    }

    private void fetch(List<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        synchronized (fetching) {
            fetching.addAll(paths);
        }
        JsonArray list = new JsonArray();
        paths.forEach(list::add);
        JsonObject body = new JsonObject();
        body.add("paths", list);
        send.accept(new TemplateSyncPacket("fetch", body.toString()));
    }

    private void done(String path) {
        synchronized (fetching) {
            fetching.remove(path);
            fetching.notifyAll();
        }
    }

    static String group(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    // ----------------------------------------------------------------- state

    private void loadState() {
        if (Files.notExists(stateFile)) {
            return;
        }
        try {
            JsonObject state = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
            state.entrySet().forEach(entry -> synced.put(entry.getKey(), entry.getValue().getAsString()));
        } catch (IOException | RuntimeException exception) {
            // Without it, the first reconcile lets the network's copy win every difference.
            LOGGER.warn("Ignoring unreadable {}: {}", stateFile, exception.getMessage());
        }
    }

    private void saveState() {
        JsonObject state = new JsonObject();
        synced.forEach(state::addProperty);
        try {
            JsonConfig.save(stateFile, state);
        } catch (IOException exception) {
            LOGGER.warn("Could not save {}: {}", stateFile, exception.getMessage());
        }
    }

    private static void guard(IoTask task) {
        try {
            task.run();
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Template sync failed: {}", exception.getMessage());
        }
    }

    @FunctionalInterface
    private interface IoTask {
        void run() throws IOException;
    }
}
