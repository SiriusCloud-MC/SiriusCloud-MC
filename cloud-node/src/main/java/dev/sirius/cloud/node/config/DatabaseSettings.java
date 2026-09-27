package dev.sirius.cloud.node.config;

import java.util.Locale;

/**
 * Where the cloud keeps persistent data. Part of {@code node/config.json}.
 *
 * <p>{@code json} needs nothing installed and is the default. The others need a
 * reachable server; the node refuses to start if one is configured and cannot
 * be reached, because running without the database a cloud was set up for
 * would quietly lose bans and friendships.
 */
public final class DatabaseSettings {

    private String type = "json";
    private String host = "127.0.0.1";

    /** Zero means the backend's standard port. */
    private int port = 0;

    private String database = "siriuscloud";
    private String username = "";
    private String password = "";

    /** MongoDB only: a full connection string, overriding host/port/credentials when set. */
    private String uri = "";

    private int poolSize = 4;

    public String type() {
        return type == null || type.isBlank() ? "json" : type.trim().toLowerCase(Locale.ROOT);
    }

    public void type(String type) {
        this.type = type;
    }

    public String host() {
        return host == null || host.isBlank() ? "127.0.0.1" : host;
    }

    public void host(String host) {
        this.host = host;
    }

    public int port() {
        if (port > 0) {
            return port;
        }
        return switch (type()) {
            case "mysql", "mariadb" -> 3306;
            case "postgresql", "postgres" -> 5432;
            case "mongodb", "mongo" -> 27017;
            default -> 0;
        };
    }

    public void port(int port) {
        this.port = port;
    }

    public String database() {
        return database == null || database.isBlank() ? "siriuscloud" : database;
    }

    public void database(String database) {
        this.database = database;
    }

    public String username() {
        return username == null ? "" : username;
    }

    public void username(String username) {
        this.username = username;
    }

    public String password() {
        return password == null ? "" : password;
    }

    public void password(String password) {
        this.password = password;
    }

    public String uri() {
        return uri == null ? "" : uri;
    }

    public int poolSize() {
        return poolSize < 1 ? 4 : poolSize;
    }
}
