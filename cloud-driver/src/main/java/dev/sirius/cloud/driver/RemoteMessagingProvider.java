package dev.sirius.cloud.driver;

import dev.sirius.cloud.api.messaging.ChannelMessage;
import dev.sirius.cloud.api.messaging.MessagingProvider;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.connection.NetworkClient;
import dev.sirius.cloud.protocol.packet.impl.ChannelMessagePacket;
import dev.sirius.cloud.protocol.packet.impl.ChannelSubscriptionsPacket;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * {@link MessagingProvider} for everything outside the node.
 *
 * <p>Publishing hands the message to the node, which fans it out; subscribing
 * is local, fed by whatever the node sends back. The service never needs to
 * know what else is running, which is the entire point of brokering it.
 *
 * <p>The node is told which channels this process listens on, so it only
 * forwards what somebody here will handle. The set is re-sent after every
 * handshake, because a node that restarted knows nothing about it.
 */
final class RemoteMessagingProvider implements MessagingProvider {

    private final NetworkClient client;
    private final ChannelSubscriptions subscriptions = new ChannelSubscriptions();
    private final Set<String> channels = ConcurrentHashMap.newKeySet();

    RemoteMessagingProvider(NetworkClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<Void> publish(String channel, String payload) {
        return send(new ChannelMessagePacket(channel, payload, "", null));
    }

    @Override
    public CompletableFuture<Void> publishTo(String target, String channel, String payload) {
        return send(new ChannelMessagePacket(channel, payload, "", target));
    }

    private CompletableFuture<Void> send(ChannelMessagePacket packet) {
        Optional<NetworkChannel> connection = client.channel();
        if (connection.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected to the node"));
        }
        // The source is left empty: the node stamps it from the authenticated
        // connection, so a service cannot publish under another's name.
        connection.get().send(packet);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void subscribe(String channel, Consumer<ChannelMessage> handler) {
        subscriptions.subscribe(channel, handler);
        if (channels.add(key(channel))) {
            sync();
        }
    }

    @Override
    public void unsubscribe(String channel) {
        subscriptions.unsubscribe(channel);
        if (channels.remove(key(channel))) {
            sync();
        }
    }

    /**
     * Tells the node the full set of channels this process listens on.
     *
     * <p>Quietly does nothing while disconnected: the handshake calls this
     * again, and a subscription made before the connection is up is the normal
     * case for a plugin that subscribes in {@code onEnable}.
     */
    void sync() {
        client.channel().ifPresent(connection -> {
            if (connection.authenticated()) {
                connection.send(new ChannelSubscriptionsPacket(new ArrayList<>(channels)));
            }
        });
    }

    /** Called by the owning process when the node delivers a message. */
    void deliver(ChannelMessage message) {
        subscriptions.deliver(message);
    }

    private static String key(String channel) {
        return channel == null ? "" : channel.toLowerCase(Locale.ROOT);
    }
}
