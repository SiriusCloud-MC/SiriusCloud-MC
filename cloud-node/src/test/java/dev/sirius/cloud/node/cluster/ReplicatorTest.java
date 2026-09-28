package dev.sirius.cloud.node.cluster;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicatorTest {

    @TempDir
    Path leaderDir;

    @TempDir
    Path followerDir;

    private final Queue<Runnable> wire = new ArrayDeque<>();
    private final AtomicReference<String> leaderShared = new AtomicReference<>("{\"secret\":\"a\"}");
    private final AtomicReference<String> followerShared = new AtomicReference<>("{}");
    private Replicator leader;
    private Replicator follower;
    private long now;

    private void setUp() {
        leader = new Replicator(new FileIndex(leaderDir), link("leader"),
                () -> leaderShared.get().getBytes(StandardCharsets.UTF_8),
                bytes -> leaderShared.set(new String(bytes, StandardCharsets.UTF_8)), 0, revision -> {
                });
        follower = new Replicator(new FileIndex(followerDir), link("follower"),
                () -> followerShared.get().getBytes(StandardCharsets.UTF_8),
                bytes -> followerShared.set(new String(bytes, StandardCharsets.UTF_8)), 0, revision -> {
                });
        leader.becameLeader();
    }

    private Replicator.Link link(String self) {
        return (peer, kind, body, data) -> wire.add(() -> {
            try {
                deliver(self, peer, kind, body, data);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private void deliver(String from, String to, String kind, JsonObject body, byte[] data) throws IOException {
        if (to.equals("follower")) {
            switch (kind) {
                case "manifest" -> follower.onManifest(from, body);
                case "file" -> follower.onFile(body, data);
                case "gone" -> follower.onGone(body);
                case "fetch-done" -> follower.onFetchDone(from, body);
                case "delta" -> follower.onDelta(body);
                case "delta-done" -> follower.onDeltaDone(from, body);
                default -> throw new IllegalStateException(kind);
            }
        } else {
            switch (kind) {
                case "fetch" -> leader.onFetch(from, body);
                case "synced" -> leader.onSynced(from, body);
                default -> throw new IllegalStateException(kind);
            }
        }
    }

    private void tick() throws IOException {
        now += 1000;
        leader.leaderTick(List.of("follower"), now);
        while (!wire.isEmpty()) {
            wire.poll().run();
        }
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        // Distinct timestamps, so the index notices rewrites of the same size.
        file.toFile().setLastModified(System.currentTimeMillis() + (long) (Math.random() * 100_000));
    }

    private String read(String relative) throws IOException {
        return Files.readString(followerDir.resolve(relative));
    }

    @Test
    void aNewFollowerReceivesEverything() throws IOException {
        write(leaderDir, "groups/Lobby.json", "{\"name\":\"Lobby\"}");
        write(leaderDir, "modules/permissions/groups.json", "[]");
        write(leaderDir, "local/store.json", "{}");
        setUp();

        tick();
        assertEquals("{\"name\":\"Lobby\"}", read("groups/Lobby.json"));
        assertEquals("[]", read("modules/permissions/groups.json"));
        assertEquals("{}", read("local/store.json"));
        assertEquals("{\"secret\":\"a\"}", followerShared.get());
        assertEquals(leader.revision(), follower.revision());
    }

    @Test
    void changesFollowAsDeltas() throws IOException {
        write(leaderDir, "groups/Lobby.json", "one");
        setUp();
        tick();

        write(leaderDir, "groups/Lobby.json", "two");
        write(leaderDir, "groups/BedWars.json", "new");
        tick();
        assertEquals("two", read("groups/Lobby.json"));
        assertEquals("new", read("groups/BedWars.json"));
        assertEquals(1, leader.revision());
        assertEquals(1, follower.revision());

        Files.delete(leaderDir.resolve("groups/BedWars.json"));
        leaderShared.set("{\"secret\":\"b\"}");
        tick();
        assertFalse(Files.exists(followerDir.resolve("groups/BedWars.json")));
        assertEquals("{\"secret\":\"b\"}", followerShared.get());
        assertEquals(2, follower.revision());
    }

    @Test
    void aFollowerEndsUpWithExactlyTheLeadersFiles() throws IOException {
        write(leaderDir, "groups/Lobby.json", "leader");
        write(followerDir, "groups/Lobby.json", "stale");
        write(followerDir, "groups/Removed.json", "gone on the leader");
        write(followerDir, "local/console_history", "this node's own");
        setUp();

        tick();
        assertEquals("leader", read("groups/Lobby.json"));
        assertFalse(Files.exists(followerDir.resolve("groups/Removed.json")));
        assertTrue(Files.exists(followerDir.resolve("local/console_history")), "not replicated, not touched");
    }

    @Test
    void jarsAreNeverReplicated() throws IOException {
        write(leaderDir, "modules/cloud-module-rest.jar", "leader's build");
        write(followerDir, "modules/cloud-module-rest.jar", "follower's build");
        setUp();

        tick();
        assertEquals("follower's build", read("modules/cloud-module-rest.jar"));
    }

    @Test
    void templatesTravelWithTheirPlugins() throws IOException {
        write(leaderDir, "templates/Lobby/default/plugins/cloud-plugin-lobby.jar", "the lobby plugin");
        write(leaderDir, "templates/global/server/server.properties", "motd=hi");
        setUp();

        tick();
        assertEquals("the lobby plugin", read("templates/Lobby/default/plugins/cloud-plugin-lobby.jar"));
        assertEquals("motd=hi", read("templates/global/server/server.properties"));
    }

    @Test
    void largeFilesArriveInPieces() throws IOException {
        String big = "x".repeat(Replicator.CHUNK * 2 + 17);
        write(leaderDir, "local/database/players/abc.json", big);
        setUp();

        tick();
        assertEquals(big, read("local/database/players/abc.json"));
    }

    @Test
    void aFollowerThatMissedADeltaGetsAManifest() throws IOException {
        write(leaderDir, "groups/Lobby.json", "one");
        setUp();
        tick();

        // A delta lost on the way: the leader sent it, the follower never saw it.
        write(leaderDir, "groups/Lobby.json", "two");
        now += 1000;
        leader.leaderTick(List.of("follower"), now);
        wire.clear();
        assertEquals("one", read("groups/Lobby.json"));

        // The in-flight delta times out, the next change cannot apply as a
        // delta on top of a revision the follower does not have, and a full
        // manifest brings it back in line.
        now += 31_000;
        write(leaderDir, "groups/Other.json", "x");
        for (int round = 0; round < 3; round++) {
            tick();
        }
        assertEquals("two", read("groups/Lobby.json"));
        assertEquals("x", read("groups/Other.json"));
        assertEquals(leader.revision(), follower.revision());
    }

    @Test
    void pathsOutsideTheReplicatedSetAreRefused() {
        assertFalse(FileIndex.replicated("config.json"));
        assertFalse(FileIndex.replicated("local/cluster/state.json"));
        assertFalse(FileIndex.replicated("groups/../config.json"));
        assertFalse(FileIndex.replicated("groups/Lobby.json.tmp"));
        assertTrue(FileIndex.replicated("groups/Lobby.json"));
        assertTrue(FileIndex.replicated("templates/Lobby/default/plugins/x.jar"));
        assertFalse(FileIndex.replicated("modules/cloud-module-rest.jar"));
        assertTrue(FileIndex.replicated("local/store.json"));
    }
}
