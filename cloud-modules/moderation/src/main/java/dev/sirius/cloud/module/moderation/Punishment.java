package dev.sirius.cloud.module.moderation;

import dev.sirius.cloud.module.common.Durations;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** One ban or mute, as stored. {@code expires} is epoch millis, or 0 for never. */
final class Punishment {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    String uuid;
    String name;
    String reason;
    String by;
    long created;
    long expires;

    static Punishment of(UUID player, String name, String reason, String by, Duration length) {
        Punishment punishment = new Punishment();
        punishment.uuid = player.toString();
        punishment.name = name;
        punishment.reason = reason;
        punishment.by = by;
        punishment.created = System.currentTimeMillis();
        punishment.expires = length == null || length.isZero() ? 0 : punishment.created + length.toMillis();
        return punishment;
    }

    boolean expired() {
        return expires > 0 && expires <= System.currentTimeMillis();
    }

    /** "never", or "in 3d 4h (2026-10-01 18:00)". */
    String describeExpiry() {
        if (expires == 0) {
            return "never";
        }
        return "in " + Durations.describe(Duration.ofMillis(expires - System.currentTimeMillis()))
                + " (" + DATE.format(Instant.ofEpochMilli(expires)) + ")";
    }
}
