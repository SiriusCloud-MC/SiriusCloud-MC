package dev.sirius.cloud.node.cluster;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.sirius.cloud.api.logging.CloudLogger;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Keeps every follower's copy of the network's data the same as the leader's.
 *
 * <p>Two ways, one cheap and one thorough:
 * <ul>
 *   <li><b>Deltas.</b> The leader scans its files every second. When something
 *       changed it becomes a new revision, and followers that were exactly on
 *       the previous one are sent just what changed.</li>
 *   <li><b>Manifests.</b> Anyone else - a follower that just connected, one
 *       that missed a delta, every follower of a newly elected leader - gets
 *       the full list of paths and hashes, deletes what the leader does not
 *       have and fetches what differs. Whatever state it was in before, it
 *       ends up with exactly the leader's files.</li>
 * </ul>
 * A follower records the revision it has reached; elections use it, so a
 * node that missed changes cannot become leader over one that has them.
 *
 * <p>Writes are not held until followers confirm them, so a leader that
 * dies can take its last second or so of changes with it. For groups, bans
 * and profiles that is a reasonable price for never waiting on the network.
 *
 * <p>Not thread-safe: the cluster member calls it from one thread.
 */
public final class Replicator {

    /** Sends one message to a peer. */
    public interface Link {
        void send(String peer, String kind, JsonObject body, byte[] data);
    }

    private static final CloudLogger LOGGER = CloudLogger.of("Cluster");

    /** The shared node settings travel as a file that does not exist on disk. */
    static final String SHARED = "@shared-settings";

    static final int CHUNK = 1024 * 1024;
    private static final long IN_FLIGHT_TIMEOUT_MILLIS = 30_000;

    private final FileIndex files;
    private final Link link;
    private final Supplier<byte[]> sharedOut;
    private final Consumer<byte[]> sharedIn;
    private final LongConsumer persistRevision;

    private volatile long revision;

    // Leader side.
    private Map<String, String> last;
    private final Map<String, Follower> followers = new HashMap<>();

    /** What the leader knows about one follower. */
    private static final class Follower {
        long synced = -1;
        /** Has completed a manifest sync with this leader, so its files are known to match. */
        boolean verified;
        long inFlightSince = -1;
    }

    // Follower side.
    private long pendingRevision = -1;

    public Replicator(FileIndex files, Link link, Supplier<byte[]> sharedOut, Consumer<byte[]> sharedIn,
                      long revision, LongConsumer persistRevision) {
        this.files = files;
        this.link = link;
        this.sharedOut = sharedOut;
        this.sharedIn = sharedIn;
        this.revision = revision;
        this.persistRevision = persistRevision;
    }

    public long revision() {
        return revision;
    }

    private void revision(long value) {
        revision = value;
        persistRevision.accept(value);
    }

    private Map<String, String> fullManifest() throws IOException {
        Map<String, String> manifest = new java.util.TreeMap<>(files.manifest());
        manifest.put(SHARED, FileIndex.hash(sharedOut.get()));
        return manifest;
    }

    // ================================================================ leader

    /** A new term: nothing is known about any follower until it has been checked again. */
    public void becameLeader() {
        followers.clear();
        last = null;
    }

    /** Where each follower is, for {@code cluster status}. Leader only. */
    public Map<String, Long> followerRevisions() {
        Map<String, Long> view = new HashMap<>();
        followers.forEach((peer, follower) -> view.put(peer, follower.verified ? follower.synced : -1));
        return view;
    }

    /** Once a second on the leader: notice changes, and bring every follower up to date. */
    public void leaderTick(Collection<String> peers, long now) throws IOException {
        Map<String, String> manifest = fullManifest();
        if (last == null) {
            last = manifest;
        } else if (!manifest.equals(last)) {
            List<String> changed = new ArrayList<>();
            manifest.forEach((path, hash) -> {
                if (!hash.equals(last.get(path))) {
                    changed.add(path);
                }
            });
            List<String> deleted = last.keySet().stream().filter(path -> !manifest.containsKey(path)).toList();
            long base = revision;
            revision(base + 1);
            for (Map.Entry<String, Follower> entry : followers.entrySet()) {
                Follower follower = entry.getValue();
                if (follower.verified && follower.synced == base && follower.inFlightSince < 0
                        && peers.contains(entry.getKey())) {
                    sendDelta(entry.getKey(), base, revision, changed, deleted);
                    follower.inFlightSince = now;
                }
            }
            last = manifest;
        }

        followers.keySet().retainAll(peers);
        for (String peer : peers) {
            Follower follower = followers.computeIfAbsent(peer, key -> new Follower());
            if (follower.inFlightSince >= 0 && now - follower.inFlightSince > IN_FLIGHT_TIMEOUT_MILLIS) {
                follower.inFlightSince = -1;
            }
            if (follower.inFlightSince < 0 && (!follower.verified || follower.synced < revision)) {
                sendManifest(peer, manifest);
                follower.inFlightSince = now;
            }
        }
    }

    /** A peer's connection dropped: whatever was in flight to it is lost. */
    public void peerDisconnected(String peer) {
        followers.remove(peer);
    }

    private void sendManifest(String peer, Map<String, String> manifest) {
        JsonObject entries = new JsonObject();
        manifest.forEach(entries::addProperty);
        JsonObject body = new JsonObject();
        body.addProperty("revision", revision);
        body.add("entries", entries);
        link.send(peer, "manifest", body, new byte[0]);
    }

    private void sendDelta(String peer, long base, long target, List<String> changed, List<String> deleted) {
        JsonObject body = new JsonObject();
        body.addProperty("base", base);
        body.addProperty("revision", target);
        JsonArray gone = new JsonArray();
        deleted.forEach(gone::add);
        body.add("deleted", gone);
        link.send(peer, "delta", body, new byte[0]);
        changed.forEach(path -> sendFile(peer, path));
        link.send(peer, "delta-done", body, new byte[0]);
    }

    /** A follower asks for the files that differ. */
    public void onFetch(String peer, JsonObject body) {
        for (JsonElement path : body.getAsJsonArray("paths")) {
            sendFile(peer, path.getAsString());
        }
        JsonObject done = new JsonObject();
        done.addProperty("revision", body.get("revision").getAsLong());
        link.send(peer, "fetch-done", done, new byte[0]);
    }

    private void sendFile(String peer, String path) {
        byte[] content;
        try {
            content = SHARED.equals(path) ? sharedOut.get() : files.read(path);
        } catch (NoSuchFileException gone) {
            JsonObject body = new JsonObject();
            body.addProperty("path", path);
            link.send(peer, "gone", body, new byte[0]);
            return;
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.warn("Could not read {} to replicate it: {}", path, exception.getMessage());
            return;
        }
        int offset = 0;
        do {
            int length = Math.min(CHUNK, content.length - offset);
            byte[] chunk = java.util.Arrays.copyOfRange(content, offset, offset + length);
            JsonObject body = new JsonObject();
            body.addProperty("path", path);
            body.addProperty("first", offset == 0);
            body.addProperty("last", offset + length >= content.length);
            link.send(peer, "file", body, chunk);
            offset += length;
        } while (offset < content.length);
    }

    /** A follower reports the revision it now holds. */
    public void onSynced(String peer, JsonObject body) {
        Follower follower = followers.get(peer);
        if (follower == null) {
            return;
        }
        follower.synced = body.get("revision").getAsLong();
        if (body.has("verified") && body.get("verified").getAsBoolean()) {
            follower.verified = true;
        }
        follower.inFlightSince = -1;
    }

    // ============================================================== follower

    /** The leader's full list. Deletes what it does not have, fetches what differs. */
    public void onManifest(String leader, JsonObject body) throws IOException {
        long target = body.get("revision").getAsLong();
        JsonObject entries = body.getAsJsonObject("entries");
        Map<String, String> local = fullManifest();

        for (String path : local.keySet()) {
            if (!SHARED.equals(path) && !entries.has(path)) {
                files.delete(path);
            }
        }
        JsonArray need = new JsonArray();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            String path = entry.getKey();
            if ((SHARED.equals(path) || FileIndex.replicated(path))
                    && !entry.getValue().getAsString().equals(local.get(path))) {
                need.add(path);
            }
        }

        pendingRevision = target;
        if (need.isEmpty()) {
            completeSync(leader, target);
            return;
        }
        JsonObject fetch = new JsonObject();
        fetch.addProperty("revision", target);
        fetch.add("paths", need);
        link.send(leader, "fetch", fetch, new byte[0]);
    }

    public void onFile(JsonObject body, byte[] data) throws IOException {
        String path = body.get("path").getAsString();
        boolean first = body.get("first").getAsBoolean();
        boolean last = body.get("last").getAsBoolean();
        if (SHARED.equals(path)) {
            if (first && last) {
                sharedIn.accept(data);
            }
            return;
        }
        if (!FileIndex.replicated(path)) {
            return;
        }
        files.append(path, data, first);
        if (last) {
            files.finish(path);
        }
    }

    public void onGone(JsonObject body) throws IOException {
        String path = body.get("path").getAsString();
        if (FileIndex.replicated(path)) {
            files.delete(path);
        }
    }

    public void onFetchDone(String leader, JsonObject body) {
        long target = body.get("revision").getAsLong();
        if (target == pendingRevision) {
            completeSync(leader, target);
        }
    }

    private void completeSync(String leader, long target) {
        pendingRevision = -1;
        revision(target);
        JsonObject synced = new JsonObject();
        synced.addProperty("revision", target);
        synced.addProperty("verified", true);
        link.send(leader, "synced", synced, new byte[0]);
    }

    public void onDelta(JsonObject body) throws IOException {
        for (JsonElement path : body.getAsJsonArray("deleted")) {
            if (!SHARED.equals(path.getAsString()) && FileIndex.replicated(path.getAsString())) {
                files.delete(path.getAsString());
            }
        }
    }

    public void onDeltaDone(String leader, JsonObject body) {
        long base = body.get("base").getAsLong();
        long target = body.get("revision").getAsLong();
        JsonObject synced = new JsonObject();
        if (revision == base) {
            revision(target);
            synced.addProperty("revision", target);
            synced.addProperty("verified", true);
        } else {
            // Not where the leader thought: a full manifest will sort it out.
            synced.addProperty("revision", revision);
            synced.addProperty("verified", false);
        }
        link.send(leader, "synced", synced, new byte[0]);
    }
}
