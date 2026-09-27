package dev.sirius.cloud.module.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sirius.cloud.api.logging.CloudLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Posts embeds to one Discord webhook, in order, from its own thread.
 *
 * <p>Events arrive on the node's network threads and must never wait on
 * Discord, so they are queued. The queue is bounded: when Discord is down or
 * rate limiting hard, the oldest alerts go first, since a crash loop's
 * hundredth report says nothing the first did not.
 */
final class Webhook {

    private static final CloudLogger LOGGER = CloudLogger.of("Discord");
    private static final int QUEUE_LIMIT = 100;
    private static final int DESCRIPTION_LIMIT = 4000;

    static final int RED = 0xE74C3C;
    static final int ORANGE = 0xE67E22;
    static final int GREEN = 0x2ECC71;
    static final int GREY = 0x95A5A6;

    private record Message(String title, String description, int color) {
    }

    private final URI target;
    private final String username;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final BlockingQueue<Message> queue = new LinkedBlockingQueue<>(QUEUE_LIMIT);
    private final Thread sender;

    Webhook(String url, String username) {
        this.target = URI.create(url);
        this.username = username;
        this.sender = Thread.ofPlatform().daemon().name("discord-webhook").start(this::run);
    }

    void post(String title, String description, int color) {
        Message message = new Message(title, truncate(description), color);
        while (!queue.offer(message)) {
            queue.poll();
        }
    }

    void close() {
        sender.interrupt();
    }

    private void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Message message = queue.take();
                deliver(message);
            }
        } catch (InterruptedException ignored) {
            // Shutting down; whatever is queued is dropped.
        }
    }

    private void deliver(Message message) throws InterruptedException {
        for (int attempt = 0; attempt < 5; attempt++) {
            HttpResponse<String> response;
            try {
                response = http.send(HttpRequest.newBuilder(target)
                                .timeout(Duration.ofSeconds(15))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body(message)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (IOException exception) {
                LOGGER.warn("Could not reach Discord: {}", exception.getMessage());
                TimeUnit.SECONDS.sleep(5);
                continue;
            }

            int status = response.statusCode();
            if (status / 100 == 2) {
                return;
            }
            if (status == 429) {
                TimeUnit.MILLISECONDS.sleep(retryAfterMillis(response));
                continue;
            }
            if (status >= 500) {
                TimeUnit.SECONDS.sleep(5);
                continue;
            }
            // 4xx other than 429 will not get better by retrying: a deleted
            // webhook, or a message Discord refuses.
            LOGGER.warn("Discord rejected an alert ({}): {}", status, response.body());
            return;
        }
        LOGGER.warn("Gave up on a Discord alert after 5 attempts: {}", message.title());
    }

    /** Discord's {@code retry_after} is in seconds, possibly fractional; the header is the fallback. */
    private static long retryAfterMillis(HttpResponse<String> response) {
        try {
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            if (body.has("retry_after")) {
                return Math.max(250, (long) (body.get("retry_after").getAsDouble() * 1000));
            }
        } catch (RuntimeException ignored) {
            // Not JSON; try the header.
        }
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return (long) (Double.parseDouble(value) * 1000);
                    } catch (NumberFormatException exception) {
                        return 2000L;
                    }
                })
                .orElse(2000L);
    }

    private String body(Message message) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", message.title());
        embed.addProperty("description", message.description());
        embed.addProperty("color", message.color());
        embed.addProperty("timestamp", Instant.now().toString());
        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.add("embeds", embeds);
        // Nobody gets pinged by a log line that happens to contain @everyone.
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        body.add("allowed_mentions", mentions);
        return body.toString();
    }

    private static String truncate(String text) {
        return text.length() <= DESCRIPTION_LIMIT ? text : text.substring(0, DESCRIPTION_LIMIT - 1) + "…";
    }
}
