package dev.sirius.cloud.driver;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.driver.GroupProvider;
import dev.sirius.cloud.api.driver.NodeProvider;
import dev.sirius.cloud.api.driver.PlayerProvider;
import dev.sirius.cloud.api.driver.ServiceProvider;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.api.event.EventManager;
import dev.sirius.cloud.api.messaging.ChannelMessage;
import dev.sirius.cloud.api.messaging.MessagingProvider;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.driver.event.DefaultEventManager;
import dev.sirius.cloud.protocol.connection.NetworkClient;

import java.util.UUID;

/**
 * The driver every process outside the node uses.
 *
 * <p>Wrappers, in-service plugins and external tools all bind one of these.
 * Because it satisfies the same {@link CloudDriver} interface the node binds
 * locally, feature code compiles once and runs unchanged on either side —
 * which is the whole reason the API module exists.
 */
public final class RemoteCloudDriver implements CloudDriver {

    private final String environment;
    private final NetworkClient client;
    private final RemoteServiceProvider services;
    private final RemoteGroupProvider groups;
    private final RemotePlayerProvider players;
    private final RemoteNodeProvider node;
    private final RemoteMessagingProvider messaging;
    private final EventManager events = new DefaultEventManager();

    public RemoteCloudDriver(String environment, NetworkClient client) {
        this.environment = environment;
        this.client = client;
        this.services = new RemoteServiceProvider(client);
        this.groups = new RemoteGroupProvider(client);
        this.players = new RemotePlayerProvider(client);
        this.node = new RemoteNodeProvider(client);
        this.messaging = new RemoteMessagingProvider(client);
    }

    @Override
    public ServiceProvider services() {
        return services;
    }

    @Override
    public PlayerProvider players() {
        return players;
    }

    @Override
    public GroupProvider groups() {
        return groups;
    }

    @Override
    public NodeProvider node() {
        return node;
    }

    @Override
    public MessagingProvider messaging() {
        return messaging;
    }

    @Override
    public EventManager events() {
        return events;
    }

    @Override
    public String environment() {
        return environment;
    }

    public NetworkClient client() {
        return client;
    }

    /** Called by the owning process when the node pushes a service update. */
    public void cacheService(ServiceInfo service) {
        services.updateCache(service);
    }

    public void evictService(UUID uniqueId) {
        services.evictFromCache(uniqueId);
    }

    /** Called by the owning process when the node reports a player change. */
    public void cachePlayer(CloudPlayer player) {
        players.cachePlayer(player);
    }

    public void evictPlayer(UUID uniqueId) {
        players.evictPlayer(uniqueId);
    }

    /** Called by the owning process when the node delivers a channel message. */
    public void deliverChannelMessage(ChannelMessage message) {
        messaging.deliver(message);
    }
}
