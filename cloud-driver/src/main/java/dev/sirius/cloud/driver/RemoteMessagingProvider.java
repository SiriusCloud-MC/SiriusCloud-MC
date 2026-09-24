package dev.sirius.cloud.driver;

import dev.sirius.cloud.api.messaging.ChannelMessage;
import dev.sirius.cloud.api.messaging.MessagingProvider;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.connection.NetworkClient;
import dev.sirius.cloud.protocol.packet.impl.ChannelMessagePacket;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * {@link MessagingProvider} for everything outside the node.
 *
 * <p>Publishing hands the message to the node, which fans it out; subscribing
 * is local, fed by whatever the node sends back. The service never needs to
 * know what else is running, which is the entire point of brokering it.
 */
final class RemoteMessagingProvider implements MessagingProvider {

    private final NetworkClient client;
    private final ChannelSubscriptions subscriptions = new ChannelSubscriptions();

    RemoteMessagingProvider(NetworkClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<Void> publish(String channel, String payload) {
        Optional<NetworkChannel> connection = client.channel();
        if (connection.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected to the node"));
        }
        // The source is left empty: the node stamps it from the authenticated
        // connection, so a service cannot publish under another's name.
        connection.get().send(new ChannelMessagePacket(channel, payload, ""));
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

    /** Called by the owning process when the node delivers a message. */
    void deliver(ChannelMessage message) {
        subscriptions.deliver(message);
    }
}
