package dev.sirius.cloud.node.database;

import dev.sirius.cloud.api.database.Database;
import dev.sirius.cloud.api.database.DatabaseCollection;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.node.config.DatabaseSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The node's {@link Database}: validation and asynchrony in front of whichever
 * backend is configured.
 *
 * <p>Calls run on virtual threads. The backends block, and they are reached
 * from packet handlers that run on Netty's event loop, which must never wait
 * on a database round trip.
 */
public final class NodeDatabase implements Database, AutoCloseable {

    private static final CloudLogger LOGGER = CloudLogger.of("Database");

    /** Becomes a table or a directory name, so it is kept to what is safe as both. */
    private static final Pattern COLLECTION = Pattern.compile("[a-z0-9_]{1,48}");

    /** The longest primary key every backend accepts; see SqlBackend. */
    private static final int MAX_KEY_LENGTH = 191;

    private final DatabaseBackend backend;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private NodeDatabase(DatabaseBackend backend) {
        this.backend = backend;
    }

    /**
     * Opens the configured backend.
     *
     * @throws IOException if it cannot be reached, with a message naming it
     */
    public static NodeDatabase open(DatabaseSettings settings, Path jsonRoot) throws IOException {
        String type = settings.type();
        try {
            DatabaseBackend backend = switch (type) {
                case "json" -> new JsonFileBackend(jsonRoot);
                case "mysql", "mariadb", "postgresql", "postgres" -> new SqlBackend(settings);
                case "mongodb", "mongo" -> new MongoBackend(settings);
                default -> throw new IOException("Unknown database type '" + type
                        + "'. Use json, mysql, mariadb, postgresql or mongodb.");
            };
            LOGGER.info("Persistent data is stored in {}{}", backend.name(),
                    type.equals("json") ? " (" + jsonRoot + ")"
                            : " at " + settings.host() + ":" + settings.port() + "/" + settings.database());
            return new NodeDatabase(backend);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            // Refusing to start is the point: running on without the database
            // this cloud was configured for would quietly drop every ban,
            // friendship and profile written until somebody noticed.
            throw new IOException("Could not open the " + type + " database at "
                    + settings.host() + ":" + settings.port() + ": " + rootMessage(exception)
                    + ". Fix 'database' in node/config.json, or set its type to json.", exception);
        }
    }

    @Override
    public String backend() {
        return backend.name();
    }

    @Override
    public DatabaseCollection collection(String name) {
        if (name == null || !COLLECTION.matcher(name).matches()) {
            throw new IllegalArgumentException("Collection names are 1-48 of a-z, 0-9 and _: '" + name + "'");
        }
        return new Collection(name);
    }

    @Override
    public void close() {
        executor.shutdown();
        backend.close();
    }

    private <T> CompletableFuture<T> run(Callable<T> call) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(call.call());
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    private static String checkKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("A key must not be empty");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Keys are at most " + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
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
            String checked = checkKey(key);
            return run(() -> backend.get(name, checked));
        }

        @Override
        public CompletableFuture<Void> put(String key, String document) {
            String checked = checkKey(key);
            if (document == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("A document must not be null"));
            }
            return run(() -> {
                backend.put(name, checked, document);
                return null;
            });
        }

        @Override
        public CompletableFuture<Boolean> delete(String key) {
            String checked = checkKey(key);
            return run(() -> backend.delete(name, checked));
        }

        @Override
        public CompletableFuture<Boolean> exists(String key) {
            String checked = checkKey(key);
            return run(() -> backend.exists(name, checked));
        }

        @Override
        public CompletableFuture<Map<String, String>> all() {
            return run(() -> backend.all(name));
        }

        @Override
        public CompletableFuture<Long> count() {
            return run(() -> backend.count(name));
        }
    }
}
