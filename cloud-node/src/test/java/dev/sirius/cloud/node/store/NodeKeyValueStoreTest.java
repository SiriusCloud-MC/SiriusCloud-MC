package dev.sirius.cloud.node.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeKeyValueStoreTest {

    @TempDir
    Path directory;

    @Test
    void setIfAbsentLetsExactlyOneRacerWin() {
        NodeKeyValueStore store = new NodeKeyValueStore(directory.resolve("store.json"));

        List<Boolean> results = IntStream.range(0, 64).parallel()
                .mapToObj(i -> store.setIfAbsent("event:lock", "server-" + i, null).join())
                .toList();

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
    }

    @Test
    void incrementIsAtomicUnderContention() {
        NodeKeyValueStore store = new NodeKeyValueStore(directory.resolve("store.json"));

        List<CompletableFuture<Long>> futures = IntStream.range(0, 1000).parallel()
                .mapToObj(i -> store.increment("counter", 1))
                .toList();
        futures.forEach(CompletableFuture::join);

        assertEquals("1000", store.get("counter").join().orElseThrow());
    }

    @Test
    void expiredKeysAreGoneAndFreeAgain() throws InterruptedException {
        NodeKeyValueStore store = new NodeKeyValueStore(directory.resolve("store.json"));

        store.set("cooldown", "x", Duration.ofMillis(30)).join();
        assertTrue(store.get("cooldown").join().isPresent());

        Thread.sleep(60);
        assertTrue(store.get("cooldown").join().isEmpty());
        assertTrue(store.setIfAbsent("cooldown", "y", null).join());
    }

    @Test
    void survivesARestartExceptForWhatExpired() throws InterruptedException {
        Path file = directory.resolve("store.json");
        NodeKeyValueStore first = new NodeKeyValueStore(file);
        first.set("keep", "1").join();
        first.set("gone", "2", Duration.ofMillis(20)).join();
        first.flush();

        Thread.sleep(40);
        NodeKeyValueStore second = new NodeKeyValueStore(file);
        second.load();

        assertEquals("1", second.get("keep").join().orElseThrow());
        assertFalse(second.get("gone").join().isPresent());
    }

    @Test
    void scanReturnsOnlyThePrefixInKeyOrder() {
        NodeKeyValueStore store = new NodeKeyValueStore(directory.resolve("store.json"));
        store.set("queue:b", "2").join();
        store.set("queue:a", "1").join();
        store.set("other", "3").join();

        assertEquals(List.of("queue:a", "queue:b"), List.copyOf(store.scan("queue:").join().keySet()));
        assertEquals(Map.of("queue:a", "1", "queue:b", "2"), store.scan("queue:").join());
    }
}
