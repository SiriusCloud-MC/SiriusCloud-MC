package dev.sirius.cloud.module.discord;

/**
 * {@code node/modules/discord/config.json}.
 *
 * <p>With no webhook URL the module does nothing at all, so shipping it in
 * every install costs nothing.
 */
public final class DiscordConfig {

    /** Channel settings -> Integrations -> Webhooks -> Copy webhook URL. */
    private String webhookUrl = "";

    private String username = "SiriusCloud";

    private boolean crashes = true;
    private boolean serviceStarts = false;
    private boolean serviceStops = false;
    private boolean wrapperDisconnects = true;
    private boolean backupFailures = true;

    /** Below this one-minute TPS a service is reported as lagging. 0 turns it off. */
    private double lowTps = 15.0;

    /** Per service: one lag alert per this many minutes, however long it lags. */
    private int lowTpsCooldownMinutes = 10;

    public String webhookUrl() {
        return webhookUrl == null ? "" : webhookUrl.trim();
    }

    public String username() {
        return username == null || username.isBlank() ? "SiriusCloud" : username;
    }

    public boolean crashes() {
        return crashes;
    }

    public boolean serviceStarts() {
        return serviceStarts;
    }

    public boolean serviceStops() {
        return serviceStops;
    }

    public boolean wrapperDisconnects() {
        return wrapperDisconnects;
    }

    public boolean backupFailures() {
        return backupFailures;
    }

    public double lowTps() {
        return lowTps;
    }

    public int lowTpsCooldownMinutes() {
        return Math.max(1, lowTpsCooldownMinutes);
    }
}
