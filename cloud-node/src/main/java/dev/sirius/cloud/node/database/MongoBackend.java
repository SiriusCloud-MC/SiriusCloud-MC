package dev.sirius.cloud.node.database;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import dev.sirius.cloud.node.config.DatabaseSettings;
import org.bson.Document;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * MongoDB, as a collection per collection.
 *
 * <p>The document is stored as a JSON <em>string</em> rather than parsed into
 * BSON. Parsing it would look nicer in a database browser, but BSON has number
 * types JSON lacks, and a document read back through Mongo's own JSON writer
 * comes out with {@code {"$numberLong": ...}} wrappers where a plain number went
 * in. A module must get back exactly what it stored, on every backend.
 */
final class MongoBackend implements DatabaseBackend {

    private static final String FIELD = "json";

    private final MongoClient client;
    private final com.mongodb.client.MongoDatabase database;

    MongoBackend(DatabaseSettings settings) {
        String uri = settings.uri().isBlank() ? buildUri(settings) : settings.uri();
        this.client = MongoClients.create(uri);
        this.database = client.getDatabase(settings.database());

        // Fail at startup with the real reason, not on the first write.
        database.runCommand(new Document("ping", 1));
    }

    private static String buildUri(DatabaseSettings settings) {
        String credentials = settings.username().isBlank()
                ? ""
                : URLEncoder.encode(settings.username(), StandardCharsets.UTF_8) + ":"
                        + URLEncoder.encode(settings.password(), StandardCharsets.UTF_8) + "@";
        return "mongodb://" + credentials + settings.host() + ":" + settings.port()
                + (credentials.isEmpty() ? "" : "/?authSource=admin");
    }

    @Override
    public String name() {
        return "mongodb";
    }

    @Override
    public Optional<String> get(String collection, String key) {
        Document document = collection(collection).find(Filters.eq("_id", key)).first();
        return document == null ? Optional.empty() : Optional.ofNullable(document.getString(FIELD));
    }

    @Override
    public void put(String collection, String key, String document) {
        collection(collection).replaceOne(
                Filters.eq("_id", key),
                new Document("_id", key).append(FIELD, document).append("updatedAt", System.currentTimeMillis()),
                new ReplaceOptions().upsert(true));
    }

    @Override
    public boolean delete(String collection, String key) {
        return collection(collection).deleteOne(Filters.eq("_id", key)).getDeletedCount() > 0;
    }

    @Override
    public Map<String, String> all(String collection) {
        Map<String, String> documents = new LinkedHashMap<>();
        for (Document document : collection(collection).find().sort(new Document("_id", 1))) {
            documents.put(String.valueOf(document.get("_id")), document.getString(FIELD));
        }
        return documents;
    }

    @Override
    public long count(String collection) {
        return collection(collection).countDocuments();
    }

    @Override
    public void close() {
        client.close();
    }

    private MongoCollection<Document> collection(String name) {
        return database.getCollection("sirius_" + name);
    }
}
