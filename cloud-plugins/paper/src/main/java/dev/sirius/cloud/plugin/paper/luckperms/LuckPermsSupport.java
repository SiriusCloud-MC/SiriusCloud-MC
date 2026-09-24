package dev.sirius.cloud.plugin.paper.luckperms;

import dev.sirius.cloud.api.logging.CloudLogger;

/**
 * The guard in front of the LuckPerms integration.
 *
 * <p><strong>This class must never reference a LuckPerms type</strong>, and
 * that is the entire reason it exists. {@link LuckPermsHook} implements
 * {@code MessengerProvider}, so merely loading it forces the JVM to resolve
 * that interface: putting the "is LuckPerms installed?" check on the hook
 * itself means the check can only run after the very class load it was meant
 * to prevent, and a server without LuckPerms gets a
 * {@code NoClassDefFoundError} instead of a quiet skip.
 *
 * <p>So the check lives here, the hook is only touched once it passes, and
 * {@link LinkageError} is caught anyway in case a LuckPerms version arrives
 * whose messenger API has moved.
 */
public final class LuckPermsSupport {

    private static final CloudLogger LOGGER = CloudLogger.of("LuckPerms");

    private LuckPermsSupport() {
    }

    /** Whether LuckPerms is on the classpath. Touches none of its types. */
    public static boolean isAvailable() {
        try {
            Class.forName("net.luckperms.api.LuckPerms");
            return true;
        } catch (ClassNotFoundException | LinkageError exception) {
            return false;
        }
    }

    /**
     * Registers the cloud as LuckPerms' messaging service, if it is installed.
     *
     * @return true if the integration is active
     */
    public static boolean enable() {
        if (!isAvailable()) {
            return false;
        }
        try {
            return LuckPermsHook.register();
        } catch (LinkageError error) {
            // A LuckPerms whose messenger API differs from the one this was
            // built against. Worth saying, never worth failing startup for.
            LOGGER.warn("LuckPerms is installed but its API does not match this build, "
                    + "so cloud-backed permission syncing is off: {}", error.toString());
            return false;
        }
    }
}
