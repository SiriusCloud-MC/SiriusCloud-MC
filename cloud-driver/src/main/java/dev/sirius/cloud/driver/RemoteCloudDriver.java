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
import dev.sirius.cloud.api.event.events.ServiceCreatedEvent;
import dev.sirius.cloud.api.event.events.ServiceRemovedEvent;
import dev.sirius.cloud.api.event.events.ServiceStateChangedEvent;
import dev.sirius.cloud.api.event.events.ServiceUpdatedEvent;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.driver.event.DefaultEventManager;
import dev.sirius.cloud.protocol.connection.NetworkClient;
import dev.sirius.cloud.protocol.packet.Packet;
import dev.sirius.cloud.protocol.packet.impl.ChannelMessagePacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceUpdatePacket;

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

    private static final CloudLogger LOGGER = CloudLogger.of("Driver");

    public RemoteCloudDriver(String environment, NetworkClient client) {
        this(environment, client, null);
    }

    /**
     * @param selfId the service this driver runs inside, so
     *               {@code services().self()} works; null outside a service
     */
    public RemoteCloudDriver(String environment, NetworkClient client, UUID selfId) {
        this.environment = environment;
        this.client = client;
        this.services = new RemoteServiceProvider(client, selfId);
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

    /**
     * Routes a packet that belongs to the driver rather than to the plugin.
     *
     * <p>One entry point, so a new push the driver understands reaches every
     * plugin without each of them growing another {@code instanceof} branch.
     *
     * @return whether the driver consumed it
     */
    public boolean handle(Packet packet) {
        if (packet instanceof ChannelMessagePacket message) {
            messaging.deliver(new ChannelMessage(
                    message.channel(), message.payload(), message.sourceService()));
            return true;
        }
        if (packet instanceof ServiceUpdatePacket update) {
            applyServiceUpdate(update.service(), update.removed());
            return true;
        }
        return false;
    }

    /**
     * Called by the owning process once its handshake is accepted.
     *
     * <p>Re-announces subscriptions and reloads the service view, because the
     * node on the other end may have restarted and know neither.
     */
    public void onAuthenticated() {
        messaging.sync();
        services.services().exceptionally(error -> {
            LOGGER.debug("Could not load the initial service list: {}", error.getMessage());
            return null;
        });
    }

    /**
     * Applies a pushed service state, and fires the same lifecycle events the
     * node does.
     *
     * <p>A plugin's event bus previously never saw a service start or stop
     * anywhere else in the cloud; with this it does, derived from the difference
     * between what it held and what it was just told.
     */
    private void applyServiceUpdate(ServiceInfo service, boolean removed) {
        if (removed) {
            ServiceInfo previous = services.evictFromCache(service.uniqueId());
            if (previous != null) {
                events.post(new ServiceRemovedEvent(service));
            }
            return;
        }

        ServiceInfo previous = services.updateCache(service);
        if (previous == null) {
            events.post(new ServiceCreatedEvent(service));
        } else if (previous.state() != service.state()) {
            events.post(new ServiceStateChangedEvent(service, previous.state(), service.state()));
        }
        events.post(new ServiceUpdatedEvent(service));
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
