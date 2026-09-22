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

    public Optional<ConnectedWrapper> unregister(String name) {
        return Optional.ofNullable(wrappers.remove(name.toLowerCase(Locale.ROOT)));
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

    /**
     * Picks a wrapper for a service needing {@code requiredMemory} MB.
     *
     * <p>Least-loaded-first. Trivial today with one wrapper, and the single
     * place to change when scheduling needs to consider anything richer.
     */
    public Optional<ConnectedWrapper> selectFor(int requiredMemory) {
        return wrappers.values().stream()
                .filter(ConnectedWrapper::isAlive)
                .filter(wrapper -> wrapper.info().freeMemory() >= requiredMemory)
                .min(Comparator.comparingInt(wrapper -> wrapper.info().usedMemory()));
    }
}
