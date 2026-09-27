package dev.sirius.cloud.node.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.sirius.cloud.node.config.DatabaseSettings;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MySQL, MariaDB and PostgreSQL, as a table per collection.
 *
 * <p>Each table is a key, a document and a timestamp. That is a thin use of a
 * relational database, and deliberately so: the same schema on every engine
 * means a module never has to know which one it is talking to.
 *
 * <p>The two dialects differ in exactly two statements - the table definition
 * and the upsert - and both are kept side by side here so that difference
 * stays visible rather than spreading.
 */
final class SqlBackend implements DatabaseBackend {

    private final String name;
    private final boolean postgres;
    private final HikariDataSource dataSource;

    /** Tables already created this run, so {@code CREATE TABLE} runs once each. */
    private final Set<String> tables = ConcurrentHashMap.newKeySet();

    SqlBackend(DatabaseSettings settings) {
        this.name = settings.type().equals("postgres") ? "postgresql" : settings.type();
        this.postgres = name.equals("postgresql");

        HikariConfig config = new HikariConfig();
        config.setPoolName("siriuscloud");
        config.setMaximumPoolSize(settings.poolSize());
        config.setUsername(settings.username());
        config.setPassword(settings.password());

        switch (name) {
            case "mysql" -> {
                config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                config.setJdbcUrl("jdbc:mysql://" + settings.host() + ":" + settings.port() + "/"
                        + settings.database() + "?useUnicode=true&characterEncoding=utf8"
                        + "&allowPublicKeyRetrieval=true&useSSL=false");
            }
            case "mariadb" -> {
                config.setDriverClassName("org.mariadb.jdbc.Driver");
                config.setJdbcUrl("jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/"
                        + settings.database());
            }
            case "postgresql" -> {
                config.setDriverClassName("org.postgresql.Driver");
                config.setJdbcUrl("jdbc:postgresql://" + settings.host() + ":" + settings.port() + "/"
                        + settings.database());
            }
            default -> throw new IllegalArgumentException("Not an SQL backend: " + name);
        }

        // Fail at startup with the real reason, rather than on the first write
        // an hour later.
        config.setInitializationFailTimeout(10_000);
        this.dataSource = new HikariDataSource(config);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Optional<String> get(String collection, String key) throws SQLException {
        String table = table(collection);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT data FROM " + table + " WHERE id = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
            }
        }
    }

    @Override
    public void put(String collection, String key, String document) throws SQLException {
        String table = table(collection);
        long now = System.currentTimeMillis();
        String sql = postgres
                ? "INSERT INTO " + table + " (id, data, updated_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, updated_at = EXCLUDED.updated_at"
                : "INSERT INTO " + table + " (id, data, updated_at) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE data = ?, updated_at = ?";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, document);
            statement.setLong(3, now);
            if (!postgres) {
                statement.setString(4, document);
                statement.setLong(5, now);
            }
            statement.executeUpdate();
        }
    }

    @Override
    public boolean delete(String collection, String key) throws SQLException {
        String table = table(collection);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM " + table + " WHERE id = ?")) {
            statement.setString(1, key);
            return statement.executeUpdate() > 0;
        }
    }

    @Override
    public Map<String, String> all(String collection) throws SQLException {
        String table = table(collection);
        Map<String, String> documents = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT id, data FROM " + table + " ORDER BY id")) {
            while (result.next()) {
                documents.put(result.getString(1), result.getString(2));
            }
        }
        return documents;
    }

    @Override
    public long count(String collection) throws SQLException {
        String table = table(collection);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getLong(1) : 0;
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    /**
     * The table for a collection, created on first use.
     *
     * <p>The name is interpolated into SQL, which is safe only because
     * collection names are validated as {@code [a-z0-9_]} before they get here.
     * 191 characters for the key is the longest a utf8mb4 primary key can be on
     * older MySQL, and keys are capped to match on every backend.
     */
    private String table(String collection) throws SQLException {
        String table = "sirius_" + collection;
        if (tables.contains(table)) {
            return table;
        }
        String ddl = postgres
                ? "CREATE TABLE IF NOT EXISTS " + table
                        + " (id VARCHAR(191) PRIMARY KEY, data TEXT NOT NULL, updated_at BIGINT NOT NULL)"
                : "CREATE TABLE IF NOT EXISTS " + table
                        + " (id VARCHAR(191) NOT NULL, data LONGTEXT NOT NULL, updated_at BIGINT NOT NULL,"
                        + " PRIMARY KEY (id)) DEFAULT CHARSET=utf8mb4";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
        tables.add(table);
        return table;
    }
}
