package dev.sirius.cloud.api.network;

/**
 * MiniMessage helpers that do not need MiniMessage on the classpath.
 *
 * <p>The node has no Adventure library, yet it composes messages that proxies
 * render as MiniMessage - so anything a player typed has to be escaped here,
 * or {@code /msg} becomes a way to send somebody a clickable command.
 */
public final class Text {

    private Text() {
    }

    /** Makes user input render literally. */
    public static String escape(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("<", "\\<");
    }

    /**
     * Drops tags, for somewhere MiniMessage is not rendered - the node console.
     *
     * <p>Approximate by design: it removes anything tag-shaped and unescapes
     * what {@link #escape} escaped. It is for reading, not for security.
     */
    public static String plain(String miniMessage) {
        if (miniMessage == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(miniMessage.length());
        boolean inTag = false;
        for (int i = 0; i < miniMessage.length(); i++) {
            char c = miniMessage.charAt(i);
            if (c == '\\' && i + 1 < miniMessage.length()) {
                out.append(miniMessage.charAt(++i));
            } else if (c == '<') {
                inTag = true;
            } else if (c == '>' && inTag) {
                inTag = false;
            } else if (!inTag) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
