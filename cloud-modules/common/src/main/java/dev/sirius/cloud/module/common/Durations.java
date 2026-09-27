package dev.sirius.cloud.module.common;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human durations: {@code 30m}, {@code 1d12h}, {@code 2w}.
 *
 * <p>Parsing answers three ways - a duration, "permanent", or "that was not a
 * duration" - because a command like {@code /ban Steve 7d griefing} has to tell
 * whether its second word is a length or the start of the reason.
 */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+)([smhdwy])");
    private static final Pattern WHOLE = Pattern.compile("(\\d+[smhdwy])+");

    /** What {@link #parse} returns for "perm", "permanent" and "forever". */
    public static final Duration PERMANENT = Duration.ZERO;

    private Durations() {
    }

    /** @return the duration, {@link #PERMANENT}, or empty if the text is not a duration at all */
    public static Optional<Duration> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.equals("perm") || value.equals("permanent") || value.equals("forever")) {
            return Optional.of(PERMANENT);
        }
        if (!WHOLE.matcher(value).matches()) {
            return Optional.empty();
        }
        Duration total = Duration.ZERO;
        Matcher matcher = PART.matcher(value);
        while (matcher.find()) {
            long amount = Long.parseLong(matcher.group(1));
            total = total.plus(switch (matcher.group(2)) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                case "w" -> Duration.ofDays(amount * 7);
                default -> Duration.ofDays(amount * 365);
            });
        }
        return total.isZero() ? Optional.empty() : Optional.of(total);
    }

    /** {@code 1d 4h}, {@code 12m}, {@code 30s} - the two largest units, which is all anyone reads. */
    public static String describe(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        if (days > 0) {
            return days + "d" + (hours > 0 ? " " + hours + "h" : "");
        }
        if (hours > 0) {
            return hours + "h" + (minutes > 0 ? " " + minutes + "m" : "");
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return seconds + "s";
    }

    /** "5m ago", "3d ago". */
    public static String ago(long epochMillis) {
        return describe(Duration.ofMillis(System.currentTimeMillis() - epochMillis)) + " ago";
    }
}
