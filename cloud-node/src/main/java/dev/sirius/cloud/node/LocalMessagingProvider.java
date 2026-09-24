package dev.sirius.cloud.node;

import dev.sirius.cloud.api.messaging.ChannelMessage;
import dev.sirius.cloud.api.messaging.MessagingProvider;
import dev.sirius.cloud.driver.ChannelSubscriptions;
import dev.sirius.cloud.node.service.ServiceChannelRegistry;
import dev.sirius.cloud.protocol.packet.impl.ChannelMessagePacket;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The node's own {@link MessagingProvider}. It is the broker, so publishing is
 * a direct fan-out rather than a round trip.
 *
 * <p>Node modules use this to reach every service at once: a notification
 * module publishes here and the servers receive it, without the module needing
 * any idea which services exist or holding a connection of its own.
 */
public final class LocalMessagingProvider implements MessagingProvider {

    /** Name a message carries when the node itself published it. */
    public static final String NODE_SOURCE = "node";

    private final ServiceChannelRegistry serviceChannels;
    private final ChannelSubscriptions subscriptions = new ChannelSubscriptions();

    public LocalMessagingProvider(ServiceChannelRegistry serviceChannels) {
        this.serviceChannels = serviceChannels;
    }

    @Override
    public CompletableFuture<Void> publish(String channel, String payload) {
        serviceChannels.broadcastToServices(
                new ChannelMessagePacket(channel, payload, NODE_SOURCE), null);
        // Node-side subscribers are not fed here: a publisher does not receive
        // its own message, and on the node every local subscriber shares the
        // one provider instance.
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void subscribe(String channel, Consumer<ChannelMessage> handler) {
        subscriptions.subscribe(channel, handler);
    }

    @Override
    public void unsubscribe(String channel) {
        subscriptions.unsubscribe(channel);
    }

    /** Delivers an inbound message from a service to node-side subscribers. */
    public void deliver(ChannelMessage message) {
        subscriptions.deliver(message);
    }
}
