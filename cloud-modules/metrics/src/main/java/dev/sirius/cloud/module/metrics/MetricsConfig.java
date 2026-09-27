package dev.sirius.cloud.module.metrics;

/** {@code node/modules/metrics/config.json}. */
public final class MetricsConfig {

    /**
     * Loopback by default: metrics name every service and how busy it is, and
     * a scraper on the same machine - or behind a reverse proxy - is the usual
     * setup. Set to {@code 0.0.0.0} to scrape from elsewhere, ideally with a token.
     */
    private String host = "127.0.0.1";

    private int port = 9225;

    /** If set, scrapes must send {@code Authorization: Bearer <token>}. */
    private String token = "";

    public String host() {
        return host == null || host.isBlank() ? "127.0.0.1" : host;
    }

    public int port() {
        return port;
    }

    public String token() {
        return token == null ? "" : token;
    }
}
