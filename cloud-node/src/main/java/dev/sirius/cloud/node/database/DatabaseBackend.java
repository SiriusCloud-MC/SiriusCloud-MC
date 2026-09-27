package dev.sirius.cloud.node.database;

import java.util.Map;
import java.util.Optional;

/**
 * One storage engine, spoken to synchronously.
 *
 * <p>Blocking on purpose: every implementation here is a blocking client
 * underneath, and {@link NodeDatabase} runs calls on virtual threads so none of
 * them ever sits on a network thread. Pretending to be asynchronous at this
 * layer would only move the blocking somewhere harder to see.
 *
 * <p>Collection names reaching here are already validated as
 * {@code [a-z0-9_]}, so implementations may use them directly as table or
 * directory names.
 */
interface DatabaseBackend extends AutoCloseable {

    String name();

    Optional<String> get(String collection, String key) throws Exception;

    void put(String collection, String key, String document) throws Exception;

    boolean delete(String collection, String key) throws Exception;

    Map<String, String> all(String collection) throws Exception;

    long count(String collection) throws Exception;

    default boolean exists(String collection, String key) throws Exception {
        return get(collection, key).isPresent();
    }

    @Override
    void close();
}
