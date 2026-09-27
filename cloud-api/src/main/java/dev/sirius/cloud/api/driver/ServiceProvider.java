package dev.sirius.cloud.api.driver;

import dev.sirius.cloud.api.service.ServiceInfo;

import dev.sirius.cloud.api.service.ServiceProperties;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Everything you can do to services, identically from inside or outside the node. */
public interface ServiceProvider {

    CompletableFuture<ServiceInfo> startService(String groupName);

    CompletableFuture<Void> stopService(UUID uniqueId);

    CompletableFuture<Collection<ServiceInfo>> services();

    CompletableFuture<Collection<ServiceInfo>> servicesOfGroup(String groupName);

    Optional<ServiceInfo> cachedService(UUID uniqueId);

    Optional<ServiceInfo> cachedService(String name);

    /** Sends a console command to a running service. */
    CompletableFuture<Void> dispatchCommand(UUID uniqueId, String command);

    /**
     * The service this driver runs inside.
     *
     * <p>Empty on the node and on wrappers, which are not services.
     */
    Optional<ServiceInfo> self();

    /**
     * Merges metadata into a service's properties and tells every other
     * service. A null or empty value removes the key.
     *
     * <p>A service may only change its own properties, and nobody but the node
     * may write keys under {@link ServiceProperties#RESERVED_PREFIX}.
     */
    CompletableFuture<Void> updateProperties(UUID uniqueId, Map<String, String> properties);

    /** Sets one property on the service this driver runs inside. */
    default CompletableFuture<Void> setProperty(String key, String value) {
        Optional<ServiceInfo> self = self();
        if (self.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("This driver is not running inside a service"));
        }
        Map<String, String> change = new HashMap<>();
        change.put(key, value);
        return updateProperties(self.get().uniqueId(), change);
    }

    /**
     * Shorthand for {@link ServiceProperties#STATE}.
     *
     * <p>A minigame calls {@code setState("INGAME")} when a round starts, and
     * matchmaking stops sending players there until it says {@code LOBBY} again.
     */
    default CompletableFuture<Void> setState(String state) {
        return setProperty(ServiceProperties.STATE, state);
    }
}
