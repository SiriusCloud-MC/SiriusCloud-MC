package dev.sirius.cloud.api.database;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** One collection of JSON documents, keyed by string. */
public interface DatabaseCollection {

    String name();

    CompletableFuture<Optional<String>> get(String key);

    /** Inserts or replaces a document. */
    CompletableFuture<Void> put(String key, String document);

    /** @return whether a document was removed */
    CompletableFuture<Boolean> delete(String key);

    CompletableFuture<Boolean> exists(String key);

    /**
     * Every document in the collection.
     *
     * <p>Loads the whole collection, so it suits small ones - ban lists, party
     * settings - and not a table with a row per player who ever joined.
     */
    CompletableFuture<Map<String, String>> all();

    CompletableFuture<Long> count();
}
