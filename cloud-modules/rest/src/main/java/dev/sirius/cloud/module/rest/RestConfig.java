package dev.sirius.cloud.module.rest;

import java.util.UUID;

/** {@code node/modules/rest/config.json}. */
public final class RestConfig {

    /**
     * Interface the API listens on.
     *
     * <p>Loopback by default, and that default matters: this endpoint can start
     * and stop servers, move players and run console commands. Anything that
     * can reach it can run the cloud. Widening it is a deliberate act, and
     * should be paired with a reverse proxy doing TLS — the token travels in a
     * header and plain HTTP puts it on the wire in clear.
     */
    private String bindAddress = "127.0.0.1";

    private int port = 8080;

    /**
     * Bearer token for every {@code /api/} call.
     *
     * <p>Separate from the node's wrapper secret on purpose, following the same
     * reasoning that separates the forwarding secret: this one ends up in a
     * browser's local storage and in whatever scripts people write against the
     * API, while the cloud secret never leaves a wrapper. Leaking one must not
     * compromise the other.
     */
    private String token = UUID.randomUUID().toString();

    /** Whether to serve the built-in panel at {@code /}. */
    private boolean panel = true;

    public String bindAddress() {
        return bindAddress == null || bindAddress.isBlank() ? "127.0.0.1" : bindAddress;
    }

    public int port() {
        return port < 1 || port > 65535 ? 8080 : port;
    }

    public String token() {
        return token;
    }

    public void token(String token) {
        this.token = token;
    }

    public boolean panel() {
        return panel;
    }

    /** Whether the API is reachable from off this machine. */
    public boolean isExposed() {
        String address = bindAddress();
        return !address.equals("127.0.0.1") && !address.equals("localhost") && !address.equals("::1");
    }
}
