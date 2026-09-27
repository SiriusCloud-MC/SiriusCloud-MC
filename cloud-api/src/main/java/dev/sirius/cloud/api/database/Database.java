package dev.sirius.cloud.api.database;

/**
 * Persistent storage, backed by whatever the node is configured with.
 *
 * <p>A plain document store: collections of JSON documents by string key. That
 * is deliberately the lowest common denominator of the backends it runs on -
 * JSON files, MySQL, MariaDB, PostgreSQL, MongoDB - so a module written against
 * it works on all of them, and moving a cloud from files to a real database is
 * a config change rather than a rewrite of every feature that stores anything.
 *
 * <p>Documents are opaque JSON strings. The database never interprets them,
 * which is also why it cannot query inside them: a lookup by something other
 * than the key needs an index collection of its own, the way player names are
 * mapped to UUIDs.
 */
public interface Database {

    /** Which backend is configured: {@code json}, {@code mysql}, {@code mariadb}, {@code postgresql} or {@code mongodb}. */
    String backend();

    /**
     * A collection by name, created on first use.
     *
     * <p>Names are lowercase letters, digits and underscores; anything else is
     * rejected, since the name becomes a table or a directory.
     */
    DatabaseCollection collection(String name);
}
