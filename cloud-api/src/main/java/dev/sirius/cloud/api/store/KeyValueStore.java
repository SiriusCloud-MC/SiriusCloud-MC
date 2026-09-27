package dev.sirius.cloud.api.store;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Small shared state held on the node, reachable from anywhere in the cloud.
 *
 * <p>For things that do not warrant a database: who is in which queue, a
 * cooldown, a lock so only one lobby runs an event, a counter. Keys may expire,
 * and the store survives a node restart, but it is one node's memory rather
 * than a replicated database - anything that must never be lost belongs in
 * {@link dev.sirius.cloud.api.database.Database}.
 *
 * <p>By convention keys are namespaced with a colon, {@code "myplugin:thing"},
 * so two plugins cannot trample each other by picking the same word.
 */
public interface KeyValueStore {

    CompletableFuture<Optional<String>> get(String key);

    /** Sets a value that never expires. */
    CompletableFuture<Void> set(String key, String value);

    /** Sets a value that disappears after {@code ttl}. A null or zero ttl never expires. */
    CompletableFuture<Void> set(String key, String value, Duration ttl);

    /**
     * Sets a value only if the key is absent, atomically.
     *
     * <p>The basis of a cloud-wide lock: two servers racing to claim the same
     * key cannot both succeed, because the node applies them one at a time.
     *
     * @return whether this call set it
     */
    CompletableFuture<Boolean> setIfAbsent(String key, String value, Duration ttl);

    /** @return whether a value was removed */
    CompletableFuture<Boolean> delete(String key);

    /**
     * Adds to a numeric value atomically, treating an absent key as zero.
     *
     * @return the value after adding
     */
    CompletableFuture<Long> increment(String key, long delta);

    /** Every live key starting with {@code prefix}, and its value. */
    CompletableFuture<Map<String, String>> scan(String prefix);
}
