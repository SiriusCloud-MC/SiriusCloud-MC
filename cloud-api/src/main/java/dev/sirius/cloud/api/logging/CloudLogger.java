package dev.sirius.cloud.api.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Minimal logging facade.
 *
 * <p>The node replaces the sink with one that routes through JLine so that log
 * lines print <em>above</em> the prompt instead of shredding whatever the user
 * is currently typing. Everything else falls back to stdout.
 */
public final class CloudLogger {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static volatile Consumer<String> sink = System.out::println;
    private static volatile boolean debug = Boolean.getBoolean("sirius.debug");

    private final String name;

    private CloudLogger(String name) {
        this.name = name;
    }

    public static CloudLogger of(Class<?> type) {
        return new CloudLogger(type.getSimpleName());
    }

    public static CloudLogger of(String name) {
        return new CloudLogger(name);
    }

    /** Redirects all logging output. Used by the node console. */
    public static void sink(Consumer<String> sink) {
        CloudLogger.sink = sink;
    }

    public static void debugEnabled(boolean enabled) {
        debug = enabled;
    }

    public void info(String message, Object... args) {
        log("INFO ", format(message, args));
    }

    public void warn(String message, Object... args) {
        log("WARN ", format(message, args));
    }

    public void error(String message, Object... args) {
        log("ERROR", format(message, args));
    }

    public void error(String message, Throwable throwable) {
        log("ERROR", message);
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        for (String line : writer.toString().split("\\R")) {
            log("ERROR", "  " + line);
        }
    }

    public void debug(String message, Object... args) {
        if (debug) {
            log("DEBUG", format(message, args));
        }
    }

    /** Emits an already-formatted line verbatim, e.g. piped service console output. */
    public static void raw(String line) {
        sink.accept(line);
    }

    private void log(String level, String message) {
        sink.accept("[" + TIME.format(LocalTime.now()) + " " + level + "] [" + name + "] " + message);
    }

    /** {@code {}} placeholder substitution, so callers never pay for string concat on disabled levels. */
    private static String format(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }
        StringBuilder builder = new StringBuilder(message.length() + 32);
        int argIndex = 0;
        int cursor = 0;
        while (cursor < message.length()) {
            int placeholder = message.indexOf("{}", cursor);
            if (placeholder < 0 || argIndex >= args.length) {
                builder.append(message, cursor, message.length());
                break;
            }
            builder.append(message, cursor, placeholder).append(args[argIndex++]);
            cursor = placeholder + 2;
        }
        return builder.toString();
    }
}
