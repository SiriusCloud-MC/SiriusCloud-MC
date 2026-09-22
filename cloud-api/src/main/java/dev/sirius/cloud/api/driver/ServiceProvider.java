package dev.sirius.cloud.api.driver;

import dev.sirius.cloud.api.service.ServiceInfo;

import java.util.Collection;
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
}
