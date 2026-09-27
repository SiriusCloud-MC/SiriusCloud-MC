package dev.sirius.cloud.node.store;

import com.google.gson.reflect.TypeToken;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.store.KeyValueStore;
import dev.sirius.cloud.driver.config.JsonConfig;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The node's {@link KeyValueStore}: a map in memory, written to disk behind.
 *
 * <p>Every operation completes immediately - the node is the store, so there
 * is no round trip to wait for. Compound operations ({@code setIfAbsent},
 * {@code increment}) go through {@link ConcurrentHashMap#compute}, which is what
 * makes them atomic when two servers race for the same key.
 *
 * <p>Persistence is deliberately lazy: the file is rewritten at most every few
 * seconds when something changed, and on shutdown. A crash can lose the last
 * few seconds of writes, which is the trade this store makes against a
 * database - it is for state that can tolerate that.
 */
public final class NodeKeyValueStore implements KeyValueStore {

    private static final CloudLogger LOGGER = CloudLogger.of("Store");

    private final Path file;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();

    /** @param expiresAt epoch millis, or 0 for never */
    record Entry(String value, long expiresAt) {
        boolean expired(long now) {
            return expiresAt > 0 && expiresAt <= now;
        }
    }

    public NodeKeyValueStore(Path file) {
        this.file = file;
    }

    public void load() {
        if (Files.notExists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Entry> loaded = JsonConfig.gson().fromJson(reader,
                    new TypeToken<Map<String, Entry>>() { }.getType());
            if (loaded != null) {
                long now = System.currentTimeMillis();
                loaded.forEach((key, entry) -> {
                    if (entry != null && entry.value() != null && !entry.expired(now)) {
                        entries.put(key, entry);
                    }
                });
            }
            LOGGER.debug("Loaded {} stored key(s)", entries.size());
        } catch (Exception exception) {
            // A corrupt store is not worth refusing to start over: it holds
            // cooldowns and queue positions, not anything unrecoverable.
            LOGGER.warn("Could not read {} ({}); starting with an empty store", file, exception.getMessage());
        }
    }

    /** Writes to disk if anything changed. Called on a timer and at shutdown. */
    public void flush() {
        if (!dirty.getAndSet(false)) {
            return;
        }
        evictExpired();
        try {
            JsonConfig.save(file, new TreeMap<>(entries));
        } catch (IOException exception) {
            dirty.set(true);
            LOGGER.warn("Could not write {}: {}", file, exception.getMessage());
        }
    }

    /** Drops expired keys. They are also ignored on read, so this only reclaims memory. */
    public void evictExpired() {
        long now = System.currentTimeMillis();
        if (entries.entrySet().removeIf(entry -> entry.getValue().expired(now))) {
            dirty.set(true);
        }
    }

    public int size() {
        return entries.size();
    }

    @Override
    public CompletableFuture<Optional<String>> get(String key) {
        return CompletableFuture.completedFuture(Optional.ofNullable(live(key)).map(Entry::value));
    }

    @Override
    public CompletableFuture<Void> set(String key, String value) {
        return set(key, value, null);
    }

    @Override
    public CompletableFuture<Void> set(String key, String value, Duration ttl) {
        if (value == null) {
            return delete(key).thenApply(ignored -> null);
        }
        entries.put(checkKey(key), new Entry(value, expiry(ttl)));
        dirty.set(true);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Boolean> setIfAbsent(String key, String value, Duration ttl) {
        long now = System.currentTimeMillis();
        boolean[] set = {false};
        entries.compute(checkKey(key), (k, existing) -> {
            if (existing != null && !existing.expired(now)) {
                return existing;
            }
            set[0] = true;
            return new Entry(value, expiry(ttl));
        });
        if (set[0]) {
            dirty.set(true);
        }
        return CompletableFuture.completedFuture(set[0]);
    }

    @Override
    public CompletableFuture<Boolean> delete(String key) {
        boolean removed = entries.remove(checkKey(key)) != null;
        if (removed) {
            dirty.set(true);
        }
        return CompletableFuture.completedFuture(removed);
    }

    @Override
    public CompletableFuture<Long> increment(String key, long delta) {
        long now = System.currentTimeMillis();
        long[] result = {0};
        try {
            entries.compute(checkKey(key), (k, existing) -> {
                long current = 0;
                long expiresAt = 0;
                if (existing != null && !existing.expired(now)) {
                    current = Long.parseLong(existing.value().trim());
                    expiresAt = existing.expiresAt();
                }
                result[0] = current + delta;
                return new Entry(Long.toString(result[0]), expiresAt);
            });
        } catch (NumberFormatException exception) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("'" + key + "' does not hold a number"));
        }
        dirty.set(true);
        return CompletableFuture.completedFuture(result[0]);
    }

    @Override
    public CompletableFuture<Map<String, String>> scan(String prefix) {
        long now = System.currentTimeMillis();
        Map<String, String> result = new LinkedHashMap<>();
        new TreeMap<>(entries).forEach((key, entry) -> {
            if (key.startsWith(prefix == null ? "" : prefix) && !entry.expired(now)) {
                result.put(key, entry.value());
            }
        });
        return CompletableFuture.completedFuture(result);
    }

    private Entry live(String key) {
        Entry entry = entries.get(checkKey(key));
        if (entry == null) {
            return null;
        }
        if (entry.expired(System.currentTimeMillis())) {
            entries.remove(key, entry);
            dirty.set(true);
            return null;
        }
        return entry;
    }

    private static long expiry(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return 0;
        }
        return System.currentTimeMillis() + ttl.toMillis();
    }

    private static String checkKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("A key must not be empty");
        }
        return key;
    }
}
