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
        LuckPerms api;
        try {
            api = LuckPermsProvider.get();
        } catch (IllegalStateException notReady) {
            // LuckPerms has not started yet; LuckPermsSupport tries again.
            return false;
        }
        // Accepted after LuckPerms has started too: with 'messaging-service:
        // custom' it switches to this provider the moment it is registered.
        api.registerMessengerProvider(new LuckPermsHook());
        LOGGER.info("Registered the cloud as LuckPerms' messaging service ('messaging-service: custom').");
        return true;
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
