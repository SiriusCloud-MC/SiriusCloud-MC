package dev.sirius.cloud.module.notify;

/**
 * What travels on the {@code siriuscloud:notify} channel, as JSON.
 *
 * <p>Carries a {@code kind} rather than colour codes. Formatting belongs to
 * whatever displays it: Paper and Velocity theme it their own way, and a future
 * consumer (a Discord relay, the web panel) wants the meaning rather than a
 * string full of section signs it has to strip.
 *
 * @param kind       INFO, GOOD, WARN or BAD
 * @param message    the text, already readable on its own
 * @param permission what a player must hold to be shown it
 */
public record Notification(String kind, String message, String permission) {

    public static final String CHANNEL = "siriuscloud:notify";

    public static final String INFO = "INFO";
    public static final String GOOD = "GOOD";
    public static final String WARN = "WARN";
    public static final String BAD = "BAD";
}
