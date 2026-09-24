package dev.sirius.cloud.api.driver;

import dev.sirius.cloud.api.event.EventManager;
import dev.sirius.cloud.api.messaging.MessagingProvider;

/**
 * The single entry point into the cloud.
 *
 * <p>The node binds a local implementation that talks straight to its own
 * registries; wrappers, plugins and external tools bind a remote one that
 * serialises the same calls over the network. Because both satisfy this
 * interface, feature code is written exactly once and runs on either side.
 */
public interface CloudDriver {

    ServiceProvider services();

    PlayerProvider players();

    GroupProvider groups();

    /** The control plane itself, and the machines attached to it. */
    NodeProvider node();

    /** Publish/subscribe between everything in the cloud, brokered by the node. */
    MessagingProvider messaging();

    EventManager events();

    /** {@code NODE}, {@code WRAPPER} or {@code SERVICE} — where this driver runs. */
    String environment();

    static CloudDriver instance() {
        CloudDriver instance = Holder.INSTANCE;
        if (instance == null) {
            throw new IllegalStateException("CloudDriver has not been initialised in this JVM");
        }
        return instance;
    }

    static boolean isAvailable() {
        return Holder.INSTANCE != null;
    }

    static void bind(CloudDriver driver) {
        if (Holder.INSTANCE != null) {
            throw new IllegalStateException("CloudDriver is already bound to " + Holder.INSTANCE.environment());
        }
        Holder.INSTANCE = driver;
    }

    /** Test/shutdown hook. */
    static void unbind() {
        Holder.INSTANCE = null;
    }

    /** Package-private holder so the field is not part of the public surface. */
    final class Holder {
        private static volatile CloudDriver INSTANCE;

        private Holder() {
        }
    }
}
