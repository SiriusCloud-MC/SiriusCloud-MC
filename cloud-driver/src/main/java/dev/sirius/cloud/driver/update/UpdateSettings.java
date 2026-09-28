package dev.sirius.cloud.driver.update;

/**
 * The {@code updates} block of the node's and the wrapper's {@code config.json}.
 */
public final class UpdateSettings {

    /** What to do when a new release appears. */
    public enum Mode {
        /** Nothing, not even look. */
        OFF,
        /** Say that there is one, and do nothing else. */
        NOTIFY,
        /** Download and check it, and install it the next time this is restarted. */
        DOWNLOAD,
        /** Download it, and restart to install it as soon as that is safe. */
        AUTO
    }

    private String mode = "auto";

    /** Where releases are published, {@code owner/repository} on GitHub. */
    private String repository = "SiriusCloud-MC/SiriusCloud-MC";

    private int checkEveryHours = 6;

    /** Also consider releases marked as pre-releases. */
    private boolean preReleases = false;

    public Mode mode() {
        try {
            return Mode.valueOf(mode == null ? "AUTO" : mode.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return Mode.NOTIFY;
        }
    }

    public String repository() {
        return repository == null || repository.isBlank() ? "SiriusCloud-MC/SiriusCloud-MC" : repository.trim();
    }

    public int checkEveryHours() {
        return Math.max(1, checkEveryHours);
    }

    public boolean preReleases() {
        return preReleases;
    }
}
