package dev.sirius.cloud.module.moderation;

/**
 * {@code node/modules/moderation/config.json}.
 *
 * <p>Each feature can be switched off on its own - most usefully bans and mutes
 * where a network already runs a punishment plugin, which would otherwise be a
 * second authority contradicting the first.
 */
public final class ModerationConfig {

    private boolean bans = true;
    private boolean mutes = true;
    private boolean kicks = true;
    private boolean staffChat = true;
    private boolean find = true;
    private boolean jump = true;

    /** {@code {reason}}, {@code {expires}}, {@code {by}}. */
    private String banMessage = "<red><bold>You are banned from this network.</bold>\n\n"
            + "<gray>Reason: <white>{reason}\n<gray>Expires: <white>{expires}";

    private String kickMessage = "<red>You were kicked from the network.\n\n<gray>{reason}";

    /** {@code {player}}, {@code {server}}, {@code {message}}. */
    private String staffChatFormat = "<dark_red>Staff <dark_gray>» <red>{player} <dark_gray>({server})<gray>: <white>{message}";

    public boolean bans() {
        return bans;
    }

    public boolean mutes() {
        return mutes;
    }

    public boolean kicks() {
        return kicks;
    }

    public boolean staffChat() {
        return staffChat;
    }

    public boolean find() {
        return find;
    }

    public boolean jump() {
        return jump;
    }

    public String banMessage() {
        return banMessage == null ? "<red>You are banned." : banMessage;
    }

    public String kickMessage() {
        return kickMessage == null ? "<red>Kicked." : kickMessage;
    }

    public String staffChatFormat() {
        return staffChatFormat == null ? "<red>{player}: {message}" : staffChatFormat;
    }
}
