package dev.sirius.cloud.api.driver;

import dev.sirius.cloud.api.group.ServiceGroup;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Read and manage group definitions. */
public interface GroupProvider {

    CompletableFuture<Collection<ServiceGroup>> groups();

    Optional<ServiceGroup> cachedGroup(String name);
}
