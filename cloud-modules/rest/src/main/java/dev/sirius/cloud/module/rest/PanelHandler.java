package dev.sirius.cloud.module.rest;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves the built-in panel out of the module jar.
 *
 * <p>One self-contained page, read from the classpath rather than the
 * filesystem, so there is nothing to install beside the jar and nothing that
 * can get out of step with it.
 */
final class PanelHandler {

    private static final String RESOURCE = "/panel/index.html";

    void handle(HttpExchange exchange, String path) throws IOException {
        if (!path.equals("/") && !path.equals("/index.html")) {
            RestServer.respond(exchange, 404, Json.error("Not found"));
            return;
        }

        byte[] page;
        try (InputStream in = PanelHandler.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                RestServer.respond(exchange, 500, Json.error("The panel is missing from the module jar"));
                return;
            }
            page = in.readAllBytes();
        }

        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        // The page talks only to its own origin and loads nothing external, so
        // it can say so and have the browser enforce it.
        exchange.getResponseHeaders().add("Content-Security-Policy",
                "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; "
                        + "connect-src 'self'; img-src 'self' data:");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, page.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(page);
        }
    }

    static String utf8(String value) {
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
