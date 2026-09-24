package dev.sirius.cloud.module.notify;

/** {@code node/modules/notify/config.json}. */
public final class NotifyConfig {

    /** Permission a player needs to see any of this. */
    private String permission = "siriuscloud.notify";

    /** Prefix shown before every notification. */
    private String prefix = "[Cloud]";

    private boolean serviceStarting = false;
    private boolean serviceRunning = true;
    private boolean serviceStopping = false;
    private boolean serviceStopped = true;

    /**
     * A service dying unasked. On by default and deliberately so: it is the one
     * event where somebody needs to know immediately, and the only one that
     * cannot be inferred from a server simply being absent.
     */
    private boolean serviceCrashed = true;

    private boolean wrapperConnected = true;
    private boolean wrapperDisconnected = true;

    public String permission() {
        return permission == null || permission.isBlank() ? "siriuscloud.notify" : permission;
    }

    public String prefix() {
        return prefix == null ? "" : prefix;
    }

    public boolean serviceStarting() {
        return serviceStarting;
    }

    public boolean serviceRunning() {
        return serviceRunning;
    }

    public boolean serviceStopping() {
        return serviceStopping;
    }

    public boolean serviceStopped() {
        return serviceStopped;
    }

    public boolean serviceCrashed() {
        return serviceCrashed;
    }

    public boolean wrapperConnected() {
        return wrapperConnected;
    }

    public boolean wrapperDisconnected() {
        return wrapperDisconnected;
    }
}
