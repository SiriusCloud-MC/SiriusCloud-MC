package dev.sirius.cloud.module.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.logging.CloudLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The HTTP front door.
 *
 * <p>{@code com.sun.net.httpserver} rather than a framework: this serves a
 * dozen endpoints and a single page, and a servlet container would be more
 * dependency than the entire rest of the cloud carries.
 */
final class RestServer {

    private static final CloudLogger LOGGER = CloudLogger.of("RestApi");

    private static final String API_PREFIX = "/api/v1/";

    private final RestConfig config;
    private final ApiHandler api;
    private final PanelHandler panel;

    private HttpServer server;
    private ExecutorService executor;

    RestServer(RestConfig config, CloudDriver driver) {
        this.config = config;
        this.api = new ApiHandler(driver);
        this.panel = new PanelHandler();
    }

    void start() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(config.bindAddress(), config.port()), 0);

        // Virtual threads: a request spends its life waiting on the cloud's
        // futures, and a fixed pool would be sized by guesswork.
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        server.createContext("/", this::dispatch);
        server.start();
    }

    void stop() {
        if (server != null) {
            // Zero delay: everything here is a short request, and a module being
            // disabled should not hold the node's shutdown open.
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void dispatch(HttpExchange exchange) {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();

            // Browsers preflight any request carrying an Authorization header.
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                respondEmpty(exchange, 204);
                return;
            }

            if (path.startsWith(API_PREFIX)) {
                if (!authorised(exchange)) {
                    // The header names the scheme so a client knows what to
                    // send, but never whether the token was merely wrong.
                    exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
                    respond(exchange, 401, Json.error("A valid bearer token is required"));
                    return;
                }
                api.handle(exchange, path.substring(API_PREFIX.length()));
                return;
            }

            if (config.panel()) {
                panel.handle(exchange, path);
                return;
            }

            respond(exchange, 404, Json.error("Not found"));

        } catch (Exception exception) {
            LOGGER.debug("Request failed: {}", exception.toString());
            try {
                respond(exchange, 500, Json.error("Internal error"));
            } catch (IOException ignored) {
                // Client already gone; nothing useful left to do.
            }
        }
    }

    /**
     * Checks the bearer token in constant time.
     *
     * <p>{@link MessageDigest#isEqual} rather than {@code String.equals}: the
     * latter returns on the first differing character, which leaks the token
     * a byte at a time to anyone willing to measure. Cheap to do correctly.
     */
    private boolean authorised(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        byte[] presented = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        byte[] expected = config.token().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, expected);
    }

    // --------------------------------------------------------------- shared

    static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        addCommonHeaders(exchange);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static void respondEmpty(HttpExchange exchange, int status) throws IOException {
        addCommonHeaders(exchange);
        exchange.sendResponseHeaders(status, -1);
    }

    private static void addCommonHeaders(HttpExchange exchange) {
        // The panel is served from this same origin, so no cross-origin access
        // is granted: an API that can stop servers has no business being
        // callable from any page the operator happens to have open.
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
    }

    /** Reads a JSON request body, tolerating an empty one. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return Map.of();
            }
            Map<String, Object> parsed = Json.GSON.fromJson(body, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException exception) {
            throw new IOException("Body is not valid JSON");
        }
    }
}
