package dev.sirius.cloud.plugin.velocity.luckperms;

import dev.sirius.cloud.api.logging.CloudLogger;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.messenger.IncomingMessageConsumer;
import net.luckperms.api.messenger.Messenger;
import net.luckperms.api.messenger.MessengerProvider;

/**
 * Registers the cloud as a LuckPerms messaging service on the proxy.
 *
 * <p><strong>Timing is weaker here than on Paper.</strong> Paper has
 * {@code loadbefore}, so the provider is registered before LuckPerms enables
 * and is picked up cleanly. Velocity has no equivalent: declaring LuckPerms as
 * a dependency would force this plugin to initialise <em>after</em> it, and
 * declaring nothing leaves the order unspecified.
 *
 * <p>So this registers on proxy initialise and says plainly what to do if
 * LuckPerms got there first. Running {@code /lp reloadconfig} once makes
 * LuckPerms resolve its messaging service again, at which point the registered
 * provider is found. That is a worse experience than the backend side and it is
 * documented rather than hidden.
 */
public final class LuckPermsHook implements MessengerProvider {

    private static final CloudLogger LOGGER = CloudLogger.of("LuckPerms");

    private LuckPermsHook() {
    }

    /** Called only through {@link LuckPermsSupport}, never directly. */
    static boolean register() {
        try {
            LuckPerms api = LuckPermsProvider.get();
            api.registerMessengerProvider(new LuckPermsHook());
            LOGGER.info("Registered the cloud as a LuckPerms messaging service.");
            LOGGER.info("Set 'messaging-service: custom' in LuckPerms' config.yml.");
            LOGGER.info("If syncing does not start, run '/lp reloadconfig' once - on Velocity "
                    + "LuckPerms may have resolved its messenger before this plugin loaded.");
            return true;
        } catch (IllegalStateException exception) {
            LOGGER.warn("LuckPerms is installed but not ready, so cloud-backed permission "
                    + "syncing is off on this proxy: {}", exception.getMessage());
            return false;
        }
    }

    @Override
    public String getName() {
        return "SiriusCloud";
    }

    @Override
    public Messenger obtain(IncomingMessageConsumer incomingMessageConsumer) {
        return new CloudMessenger(incomingMessageConsumer);
    }
}
