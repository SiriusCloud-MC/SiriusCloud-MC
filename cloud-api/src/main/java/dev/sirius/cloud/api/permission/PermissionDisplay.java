package dev.sirius.cloud.api.permission;

/**
 * How ranks are shown: in chat, above heads and in the tab list.
 *
 * <p>Set once on the node, in {@code node/modules/permissions/config.json},
 * and carried in every {@link PermissionSnapshot}, so every server formats
 * chat the same way without a config file of its own.
 *
 * <p>Prefixes and the chat format use {@code &} colour codes. A colour code
 * carries on until the next one, which is how a name takes its rank's colour:
 * with a prefix of {@code &cAdmin &8| &c}, the name after it is red.
 */
public final class PermissionDisplay {

    public static final String DEFAULT_CHAT_FORMAT = "{prefix}{name}{suffix}&7: &f{message}";

    /** Format chat as {@link #chatFormat()}. Off leaves chat to other plugins. */
    private boolean chat = true;

    /** Placeholders: {@code {prefix}}, {@code {name}}, {@code {suffix}}, {@code {message}}. */
    private String chatFormat = DEFAULT_CHAT_FORMAT;

    /** Prefix and suffix above players' heads, through scoreboard teams. */
    private boolean nametags = true;

    /** Prefix and suffix in the tab list, which is also sorted by group priority. */
    private boolean tablist = true;

    public boolean chat() {
        return chat;
    }

    public String chatFormat() {
        return chatFormat == null || !chatFormat.contains("{message}") ? DEFAULT_CHAT_FORMAT : chatFormat;
    }

    public boolean nametags() {
        return nametags;
    }

    public boolean tablist() {
        return tablist;
    }
}
