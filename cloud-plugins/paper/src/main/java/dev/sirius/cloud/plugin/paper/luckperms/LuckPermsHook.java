package dev.sirius.cloud.plugin.paper.luckperms;

import dev.sirius.cloud.api.logging.CloudLogger;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.messenger.IncomingMessageConsumer;
import net.luckperms.api.messenger.Messenger;
import net.luckperms.api.messenger.MessengerProvider;

/**
 * Registers the cloud as a LuckPerms messaging service.
 *
 * <p>Isolated in its own class, and only ever touched behind a check that the
 * LuckPerms classes are present, so the plugin loads normally on a server that
 * does not run LuckPerms. Referencing these types from
 * {@code SiriusCloudPlugin} directly would make the class unloadable there.
 *
 * <p>Registration has to happen before LuckPerms enables, which is why the
 * plugin declares {@code loadbefore: [LuckPerms]} and calls this from
 * {@code onLoad} rather than {@code onEnable}. LuckPerms resolves its messenger
 * once, while enabling; registering afterwards would be ignored until a reload.
 */
public final class LuckPermsHook implements MessengerProvider {

    private static final CloudLogger LOGGER = CloudLogger.of("LuckPerms");

    private LuckPermsHook() {
    }

    /**
     * Registers the provider if LuckPerms is installed.
     *
     * @return true if it was registered
     */
    /** Called only through {@link LuckPermsSupport}, never directly. */
    static boolean register() {
        try {
            LuckPerms api = LuckPermsProvider.get();
            api.registerMessengerProvider(new LuckPermsHook());
            LOGGER.info("Registered the cloud as a LuckPerms messaging service.");
            LOGGER.info("Set 'messaging-service: custom' in LuckPerms' config.yml to use it.");
            return true;
        } catch (IllegalStateException exception) {
            // LuckPermsProvider throws this until LuckPerms has loaded. With
            // loadbefore set that should not happen, but a server owner can
            // always reorder plugins, and a missing sync is better than a
            // failed startup.
            LOGGER.warn("LuckPerms is installed but not ready yet, so cloud-backed "
                    + "permission syncing is off: {}", exception.getMessage());
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
