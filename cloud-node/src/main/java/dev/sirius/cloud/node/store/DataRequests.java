package dev.sirius.cloud.node.store;

import dev.sirius.cloud.api.database.Database;
import dev.sirius.cloud.api.database.DatabaseCollection;
import dev.sirius.cloud.api.store.KeyValueStore;
import dev.sirius.cloud.protocol.packet.impl.DataRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.DataResponsePacket;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Answers a remote store or database request against the node's own. */
public final class DataRequests {

    private DataRequests() {
    }

    public static CompletableFuture<DataResponsePacket> handle(KeyValueStore store, Database database,
                                                               DataRequestPacket request) {
        try {
            Duration ttl = request.ttlMillis() > 0 ? Duration.ofMillis(request.ttlMillis()) : null;
            String key = request.key();

            CompletableFuture<DataResponsePacket> result = switch (request.operation()) {
                case STORE_GET -> store.get(key)
                        .thenApply(value -> DataResponsePacket.success().value(value.orElse(null)));
                case STORE_SET -> store.set(key, request.value(), ttl)
                        .thenApply(ignored -> DataResponsePacket.success());
                case STORE_SET_IF_ABSENT -> store.setIfAbsent(key, request.value(), ttl)
                        .thenApply(set -> DataResponsePacket.success().flag(set));
                case STORE_DELETE -> store.delete(key)
                        .thenApply(removed -> DataResponsePacket.success().flag(removed));
                case STORE_INCREMENT -> store.increment(key, request.delta())
                        .thenApply(value -> DataResponsePacket.success().number(value));
                case STORE_SCAN -> store.scan(key)
                        .thenApply(entries -> DataResponsePacket.success().entries(entries));
                case DB_GET -> collection(database, request).get(key)
                        .thenApply(value -> DataResponsePacket.success().value(value.orElse(null)));
                case DB_PUT -> collection(database, request).put(key, request.value())
                        .thenApply(ignored -> DataResponsePacket.success());
                case DB_DELETE -> collection(database, request).delete(key)
                        .thenApply(removed -> DataResponsePacket.success().flag(removed));
                case DB_EXISTS -> collection(database, request).exists(key)
                        .thenApply(exists -> DataResponsePacket.success().flag(exists));
                case DB_ALL -> collection(database, request).all()
                        .thenApply(entries -> DataResponsePacket.success().entries(entries));
                case DB_COUNT -> collection(database, request).count()
                        .thenApply(count -> DataResponsePacket.success().number(count));
            };
            return result.exceptionally(error -> DataResponsePacket.failure(rootMessage(error)));
        } catch (RuntimeException exception) {
            // Validation failures - a bad collection name, an empty key - are
            // thrown synchronously and must still become an answer.
            return CompletableFuture.completedFuture(DataResponsePacket.failure(rootMessage(exception)));
        }
    }

    private static DatabaseCollection collection(Database database, DataRequestPacket request) {
        return database.collection(request.collection());
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
