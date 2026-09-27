package dev.sirius.cloud.driver;

import dev.sirius.cloud.api.network.LoginFilter;
import dev.sirius.cloud.api.network.NetworkCommand;
import dev.sirius.cloud.api.network.NetworkProvider;
import dev.sirius.cloud.api.network.ProxyDisplay;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.connection.NetworkClient;
import dev.sirius.cloud.protocol.packet.impl.AcknowledgePacket;
import dev.sirius.cloud.protocol.packet.impl.ChatRestrictionRequestPacket;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@link NetworkProvider} outside the node.
 *
 * <p>Chat restrictions work from here, because a moderation plugin on one server
 * muting somebody is exactly what they are for. Registering commands and login
 * filters does not: a command is executed where its state lives, and that is a
 * node module. Saying so loudly beats silently registering something that never
 * runs.
 */
final class RemoteNetworkProvider implements NetworkProvider {

    private static final String NODE_ONLY =
            "Network commands, login filters and the proxy display are registered on the node. "
                    + "Write a node module for this; see CloudModule.";

    private final NetworkClient client;

    RemoteNetworkProvider(NetworkClient client) {
        this.client = client;
    }

    @Override
    public void registerCommand(NetworkCommand command) {
        throw new UnsupportedOperationException(NODE_ONLY);
    }

    @Override
    public void unregisterCommand(String name) {
        throw new UnsupportedOperationException(NODE_ONLY);
    }

    @Override
    public void registerLoginFilter(LoginFilter filter) {
        throw new UnsupportedOperationException(NODE_ONLY);
    }

    @Override
    public void unregisterLoginFilter(LoginFilter filter) {
        throw new UnsupportedOperationException(NODE_ONLY);
    }

    @Override
    public void display(ProxyDisplay display) {
        throw new UnsupportedOperationException(NODE_ONLY);
    }

    @Override
    public Optional<ProxyDisplay> display() {
        return Optional.empty();
    }

    @Override
    public CompletableFuture<Void> restrictChat(UUID player, long untilMillis, String reason) {
        return request(new ChatRestrictionRequestPacket(player, false, untilMillis, reason));
    }

    @Override
    public CompletableFuture<Void> liftChatRestriction(UUID player) {
        return request(new ChatRestrictionRequestPacket(player, true, 0, ""));
    }

    @Override
    public boolean isChatRestricted(UUID player) {
        throw new UnsupportedOperationException(NODE_ONLY);
    }

    private CompletableFuture<Void> request(ChatRestrictionRequestPacket packet) {
        Optional<NetworkChannel> channel = client.channel();
        if (channel.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected to the node"));
        }
        return channel.get().query(packet).thenAccept(reply -> {
            if (reply instanceof AcknowledgePacket ack && !ack.success()) {
                throw new IllegalStateException(ack.message());
            }
        });
    }
}
