package dev.sirius.cloud.driver;

import dev.sirius.cloud.api.database.Database;
import dev.sirius.cloud.api.database.DatabaseCollection;
import dev.sirius.cloud.api.store.KeyValueStore;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.connection.NetworkClient;
import dev.sirius.cloud.protocol.packet.impl.DataRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.DataRequestPacket.Operation;
import dev.sirius.cloud.protocol.packet.impl.DataResponsePacket;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The key-value store and the database, for everything outside the node.
 *
 * <p>Both are one query each way, so they share one sender. Neither caches:
 * the store exists to be shared, and a cached read of shared state is exactly
 * the stale answer a lock or a counter must never give.
 */
final class RemoteData {

    private final NetworkClient client;
    private final Store store = new Store();

    /** Name reported by {@link Database#backend()}; learned lazily, never needed on the hot path. */
    private final Database database = new Database() {
        @Override
        public String backend() {
            return "remote";
        }

        @Override
        public DatabaseCollection collection(String name) {
            return new Collection(name);
        }
    };

    RemoteData(NetworkClient client) {
        this.client = client;
    }

    KeyValueStore store() {
        return store;
    }

    Database database() {
        return database;
    }

    private CompletableFuture<DataResponsePacket> request(Operation operation, String collection,
                                                         String key, String value, long ttlMillis, long delta) {
        Optional<NetworkChannel> channel = client.channel();
        if (channel.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected to the node"));
        }
        return channel.get()
                .query(new DataRequestPacket(operation, collection, key, value, ttlMillis, delta))
                .thenApply(packet -> {
                    DataResponsePacket response = (DataResponsePacket) packet;
                    if (!response.ok()) {
                        throw new IllegalStateException(response.error());
                    }
                    return response;
                });
    }

    private static long millis(Duration ttl) {
        return ttl == null || ttl.isNegative() ? 0 : ttl.toMillis();
    }

    private final class Store implements KeyValueStore {

        @Override
        public CompletableFuture<Optional<String>> get(String key) {
            return request(Operation.STORE_GET, null, key, null, 0, 0)
                    .thenApply(response -> Optional.ofNullable(response.value()));
        }

        @Override
        public CompletableFuture<Void> set(String key, String value) {
            return set(key, value, null);
        }

        @Override
        public CompletableFuture<Void> set(String key, String value, Duration ttl) {
            return request(Operation.STORE_SET, null, key, value, millis(ttl), 0).thenApply(response -> null);
        }

        @Override
        public CompletableFuture<Boolean> setIfAbsent(String key, String value, Duration ttl) {
            return request(Operation.STORE_SET_IF_ABSENT, null, key, value, millis(ttl), 0)
                    .thenApply(DataResponsePacket::flag);
        }

        @Override
        public CompletableFuture<Boolean> delete(String key) {
            return request(Operation.STORE_DELETE, null, key, null, 0, 0).thenApply(DataResponsePacket::flag);
        }

        @Override
        public CompletableFuture<Long> increment(String key, long delta) {
            return request(Operation.STORE_INCREMENT, null, key, null, 0, delta)
                    .thenApply(DataResponsePacket::number);
        }

        @Override
        public CompletableFuture<Map<String, String>> scan(String prefix) {
            return request(Operation.STORE_SCAN, null, prefix == null ? "" : prefix, null, 0, 0)
                    .thenApply(DataResponsePacket::entries);
        }
    }

    private final class Collection implements DatabaseCollection {

        private final String name;

        private Collection(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public CompletableFuture<Optional<String>> get(String key) {
            return request(Operation.DB_GET, name, key, null, 0, 0)
                    .thenApply(response -> Optional.ofNullable(response.value()));
        }

        @Override
        public CompletableFuture<Void> put(String key, String document) {
            return request(Operation.DB_PUT, name, key, document, 0, 0).thenApply(response -> null);
        }

        @Override
        public CompletableFuture<Boolean> delete(String key) {
            return request(Operation.DB_DELETE, name, key, null, 0, 0).thenApply(DataResponsePacket::flag);
        }

        @Override
        public CompletableFuture<Boolean> exists(String key) {
            return request(Operation.DB_EXISTS, name, key, null, 0, 0).thenApply(DataResponsePacket::flag);
        }

        @Override
        public CompletableFuture<Map<String, String>> all() {
            return request(Operation.DB_ALL, name, "", null, 0, 0).thenApply(DataResponsePacket::entries);
        }

        @Override
        public CompletableFuture<Long> count() {
            return request(Operation.DB_COUNT, name, "", null, 0, 0).thenApply(DataResponsePacket::number);
        }
    }
}
