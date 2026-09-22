package dev.sirius.cloud.node;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.driver.GroupProvider;
import dev.sirius.cloud.api.driver.PlayerProvider;
import dev.sirius.cloud.api.driver.ServiceProvider;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.api.event.EventManager;
import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.node.group.GroupRegistry;
import dev.sirius.cloud.node.player.PlayerManager;
import dev.sirius.cloud.node.player.PlayerRegistry;
import dev.sirius.cloud.node.service.ServiceManager;
import dev.sirius.cloud.node.service.ServiceRegistry;

import java.util.Collection;
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

    public LocalCloudDriver(ServiceManager serviceManager,
                            ServiceRegistry serviceRegistry,
                            GroupRegistry groupRegistry,
                            EventManager events,
                            PlayerRegistry playerRegistry,
                            PlayerManager playerManager) {
        this.serviceManager = serviceManager;
        this.serviceRegistry = serviceRegistry;
        this.groupRegistry = groupRegistry;
        this.events = events;
        this.playerRegistry = playerRegistry;
        this.playerManager = playerManager;
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
    public EventManager events() {
        return events;
    }

    @Override
    public String environment() {
        return "NODE";
    }
}
