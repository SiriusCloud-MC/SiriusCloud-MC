package dev.sirius.cloud.node;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.driver.GroupProvider;
import dev.sirius.cloud.api.driver.NodeProvider;
import dev.sirius.cloud.api.messaging.ChannelMessage;
import dev.sirius.cloud.api.messaging.MessagingProvider;
import dev.sirius.cloud.api.driver.PlayerProvider;
import dev.sirius.cloud.api.driver.ServiceProvider;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.api.event.EventManager;
import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.node.NodeInfo;
import dev.sirius.cloud.api.node.WrapperInfo;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.node.group.GroupRegistry;
import dev.sirius.cloud.node.player.PlayerManager;
import dev.sirius.cloud.node.player.PlayerRegistry;
import dev.sirius.cloud.node.service.ServiceManager;
import dev.sirius.cloud.node.service.ServiceChannelRegistry;
import dev.sirius.cloud.node.service.ServiceRegistry;
import dev.sirius.cloud.node.wrapper.ConnectedWrapper;
import dev.sirius.cloud.node.wrapper.WrapperRegistry;
import dev.sirius.cloud.node.config.NodeConfig;
import dev.sirius.cloud.api.platform.Platform;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The node's own {@link CloudDriver}: the same interface remote processes use,
 * backed by direct registry access instead of packets.
 *
 * <p>Node-side modules therefore call exactly the API a plugin would, and a
 * feature written against {@code CloudDriver} works in both places untouched.
 */
public final class LocalCloudDriver implements CloudDriver {

    private final ServiceManager serviceManager;
    private final ServiceRegistry serviceRegistry;
    private final GroupRegistry groupRegistry;
    private final EventManager events;
    private final PlayerRegistry playerRegistry;
    private final PlayerManager playerManager;
    private final WrapperRegistry wrapperRegistry;
    private final NodeConfig config;
    private final long startedAt = System.currentTimeMillis();
    private final LocalMessagingProvider messaging;

    public LocalCloudDriver(ServiceManager serviceManager,
                            ServiceRegistry serviceRegistry,
                            GroupRegistry groupRegistry,
                            EventManager events,
                            PlayerRegistry playerRegistry,
                            PlayerManager playerManager,
                            WrapperRegistry wrapperRegistry,
                            NodeConfig config,
                            ServiceChannelRegistry serviceChannels) {
        this.serviceManager = serviceManager;
        this.serviceRegistry = serviceRegistry;
        this.groupRegistry = groupRegistry;
        this.events = events;
        this.playerRegistry = playerRegistry;
        this.playerManager = playerManager;
        this.wrapperRegistry = wrapperRegistry;
        this.config = config;
        this.messaging = new LocalMessagingProvider(serviceChannels);
    }

    /** The node's own description, rebuilt per call so the counters are current. */
    public NodeInfo describeNode() {
        NodeInfo info = new NodeInfo(
                config.nodeName(), Platform.describe(), config.connectAddress(), config.port(), startedAt);
        info.maxMemory(config.maxMemory());
        info.committedMemory(serviceRegistry.committedMemory());
        info.serviceCount(serviceRegistry.size());
        info.wrapperCount(wrapperRegistry.all().size());
        info.playerCount(playerRegistry.count());
        return info;
    }

    /** Connected machines, as the API sees them. */
    public Collection<WrapperInfo> describeWrappers() {
        return wrapperRegistry.all().stream().map(ConnectedWrapper::info).toList();
    }

    @Override
    public ServiceProvider services() {
        return new ServiceProvider() {
            @Override
            public CompletableFuture<ServiceInfo> startService(String groupName) {
                return serviceManager.start(groupName);
            }

            @Override
            public CompletableFuture<Void> stopService(UUID uniqueId) {
                return serviceManager.stop(uniqueId, false);
            }

            @Override
            public CompletableFuture<Collection<ServiceInfo>> services() {
                return CompletableFuture.completedFuture(serviceRegistry.all());
            }

            @Override
            public CompletableFuture<Collection<ServiceInfo>> servicesOfGroup(String groupName) {
                return CompletableFuture.<Collection<ServiceInfo>>completedFuture(
                        serviceRegistry.ofGroup(groupName));
            }

            @Override
            public Optional<ServiceInfo> cachedService(UUID uniqueId) {
                return serviceRegistry.byId(uniqueId);
            }

            @Override
            public Optional<ServiceInfo> cachedService(String name) {
                return serviceRegistry.byName(name);
            }

            @Override
            public CompletableFuture<Void> dispatchCommand(UUID uniqueId, String command) {
                serviceManager.dispatchCommand(uniqueId, command);
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    @Override
    public PlayerProvider players() {
        return new PlayerProvider() {
            @Override
            public CompletableFuture<Collection<CloudPlayer>> onlinePlayers() {
                return CompletableFuture.completedFuture(playerRegistry.all());
            }

            @Override
            public CompletableFuture<Collection<CloudPlayer>> playersOn(String serviceName) {
                return CompletableFuture.<Collection<CloudPlayer>>completedFuture(
                        playerRegistry.onService(serviceName));
            }

            @Override
            public Optional<CloudPlayer> cachedPlayer(UUID uniqueId) {
                return playerRegistry.byId(uniqueId);
            }

            @Override
            public Optional<CloudPlayer> cachedPlayer(String name) {
                return playerRegistry.byName(name);
            }

            @Override
            public int onlineCount() {
                return playerRegistry.count();
            }

            @Override
            public CompletableFuture<Void> connect(UUID uniqueId, String serviceName) {
                return playerManager.connect(uniqueId, serviceName);
            }

            @Override
            public CompletableFuture<Void> connectToGroup(UUID uniqueId, String groupName) {
                return playerManager.connectToGroup(uniqueId, groupName);
            }

            @Override
            public CompletableFuture<Void> sendMessage(UUID uniqueId, String message) {
                return playerManager.sendMessage(uniqueId, message);
            }

            @Override
            public CompletableFuture<Void> broadcast(String message) {
                return playerManager.broadcast(message);
            }

            @Override
            public CompletableFuture<Void> kick(UUID uniqueId, String reason) {
                return playerManager.kick(uniqueId, reason);
            }
        };
    }

    @Override
    public GroupProvider groups() {
        return new GroupProvider() {
            @Override
            public CompletableFuture<Collection<ServiceGroup>> groups() {
                return CompletableFuture.completedFuture(groupRegistry.all());
            }

            @Override
            public Optional<ServiceGroup> cachedGroup(String name) {
                return groupRegistry.byName(name);
            }
        };
    }

    @Override
    public NodeProvider node() {
        return new NodeProvider() {
            @Override
            public CompletableFuture<NodeInfo> info() {
                return CompletableFuture.completedFuture(describeNode());
            }

            @Override
            public CompletableFuture<Collection<NodeInfo>> nodes() {
                // One entry until clustering exists. Shaped as a collection now
                // so that adding peers later is not an API change.
                return CompletableFuture.completedFuture(List.of(describeNode()));
            }

            @Override
            public CompletableFuture<Collection<WrapperInfo>> wrappers() {
                return CompletableFuture.completedFuture(describeWrappers());
            }

            @Override
            public Optional<WrapperInfo> cachedWrapper(String name) {
                return wrapperRegistry.byName(name).map(ConnectedWrapper::info);
            }
        };
    }

    @Override
    public MessagingProvider messaging() {
        return messaging;
    }

    /** Routes an inbound channel message from a service to node-side subscribers. */
    public void deliverChannelMessage(ChannelMessage message) {
        messaging.deliver(message);
    }

    @Override
    public EventManager events() {
        return events;
    }

    @Override
    public String environment() {
        return "NODE";
    }
}
