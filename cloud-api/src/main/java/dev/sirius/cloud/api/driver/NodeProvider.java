package dev.sirius.cloud.api.driver;

import dev.sirius.cloud.api.node.NodeInfo;
import dev.sirius.cloud.api.node.WrapperInfo;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The control plane and the machines attached to it.
 *
 * <p>The other providers answer "what is running"; this one answers "what is it
 * running on". A module or panel needs both, and without this the only way to
 * see a wrapper was to be inside the node — which is exactly the coupling
 * modules exist to avoid.
 *
 * <p>Singular today: one node, many wrappers. When node clustering lands the
 * shape here is already right, since {@link #nodes()} is a collection.
 */
public interface NodeProvider {

    /** The node this driver is attached to. */
    CompletableFuture<NodeInfo> info();

    /**
     * Every node in the cluster.
     *
     * <p>One entry until clustering exists, which is why it is not the same
     * call as {@link #info()} — "which node am I talking to" and "what nodes
     * are there" stop being the same question.
     */
    CompletableFuture<Collection<NodeInfo>> nodes();

    /** Machines currently connected and able to run services. */
    CompletableFuture<Collection<WrapperInfo>> wrappers();

    Optional<WrapperInfo> cachedWrapper(String name);
}
