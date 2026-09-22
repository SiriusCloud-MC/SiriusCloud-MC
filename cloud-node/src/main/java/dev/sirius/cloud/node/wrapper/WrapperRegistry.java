package dev.sirius.cloud.node.wrapper;

import dev.sirius.cloud.protocol.connection.NetworkChannel;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Which machines are currently available to run services. */
public final class WrapperRegistry {

    private final Map<String, ConnectedWrapper> wrappers = new ConcurrentHashMap<>();

    public void register(ConnectedWrapper wrapper) {
        wrappers.put(wrapper.name().toLowerCase(Locale.ROOT), wrapper);
    }

    /**
     * Drops a wrapper, but only if the connection that dropped is still the one
     * registered under that name.
     *
     * <p>A wrapper reconnecting replaces its own entry, and the close of the old
     * channel is not necessarily observed before that happens. Removing by name
     * alone would then unregister the connection that just arrived, leaving a
     * wrapper that is connected, authenticated, and invisible to scheduling
     * until somebody restarts it.
     */
    public Optional<ConnectedWrapper> unregister(String name, NetworkChannel channel) {
        String key = name.toLowerCase(Locale.ROOT);
        ConnectedWrapper current = wrappers.get(key);
        if (current == null || current.channel() != channel) {
            return Optional.empty();
        }
        wrappers.remove(key, current);
        return Optional.of(current);
    }

    public Optional<ConnectedWrapper> byName(String name) {
        return Optional.ofNullable(wrappers.get(name.toLowerCase(Locale.ROOT)));
    }

    public Optional<ConnectedWrapper> byChannel(NetworkChannel channel) {
        return wrappers.values().stream()
                .filter(wrapper -> wrapper.channel() == channel)
                .findFirst();
    }

    public Collection<ConnectedWrapper> all() {
        return List.copyOf(wrappers.values());
    }

    public boolean isEmpty() {
        return wrappers.isEmpty();
    }

    /** Whether any connected wrapper has finished announcing what it runs. */
    public boolean hasReady() {
        return wrappers.values().stream().anyMatch(ConnectedWrapper::ready);
    }

    /**
     * Picks a wrapper for a service needing {@code requiredMemory} MB.
     *
     * <p>Least-loaded-first. Trivial today with one wrapper, and the single
     * place to change when scheduling needs to consider anything richer.
     *
     * <p>Wrappers that have not sent their service snapshot yet are skipped:
     * until it arrives the node cannot tell an idle machine from one already
     * running the very service it is about to start.
     */
    public Optional<ConnectedWrapper> selectFor(int requiredMemory) {
        return wrappers.values().stream()
                .filter(ConnectedWrapper::isAlive)
                .filter(ConnectedWrapper::ready)
                .filter(wrapper -> wrapper.info().freeMemory() >= requiredMemory)
                .min(Comparator.comparingInt(wrapper -> wrapper.info().usedMemory()));
    }
}
