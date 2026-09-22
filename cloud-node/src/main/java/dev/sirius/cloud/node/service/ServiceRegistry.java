package dev.sirius.cloud.node.service;

import dev.sirius.cloud.api.service.ServiceId;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative record of every service the cloud knows about, plus the
 * allocation of the two things services compete for: names and ports.
 */
public final class ServiceRegistry {

    private final Map<UUID, ServiceInfo> byId = new ConcurrentHashMap<>();

    /** Lowercased name to id, so lookups behave the same on Linux and Windows. */
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();

    /**
     * Ports in use, tracked per wrapper.
     *
     * <p>Per wrapper rather than globally because a port is only unique on one
     * machine: two wrappers on different hosts can both serve 41000 quite
     * happily, and a global set would waste the range as the cluster grows.
     */
    private final Map<String, Set<Integer>> portsByWrapper = new ConcurrentHashMap<>();

    /**
     * Reserves the lowest free ordinal for a group.
     *
     * <p>Lowest-free rather than monotonic so that restarting a long-lived
     * cloud does not drift to {@code Lobby-482} for the same two lobbies.
     */
    public synchronized ServiceId allocateId(String groupName) {
        Set<Integer> taken = new HashSet<>();
        for (ServiceInfo service : byId.values()) {
            if (service.groupName().equalsIgnoreCase(groupName)) {
                taken.add(service.serviceId().ordinal());
            }
        }
        int ordinal = 1;
        while (taken.contains(ordinal)) {
            ordinal++;
        }
        return new ServiceId(UUID.randomUUID(), groupName, ordinal);
    }

    /** Reserves the lowest free port at or above {@code startPort} on a wrapper. */
    public synchronized int allocatePort(String wrapperName, int startPort) {
        Set<Integer> used = portsByWrapper.computeIfAbsent(wrapperName, key -> new HashSet<>());
        int port = startPort;
        while (used.contains(port)) {
            port++;
            if (port > 65535) {
                throw new IllegalStateException("No free port above " + startPort + " on " + wrapperName);
            }
        }
        used.add(port);
        return port;
    }

    public synchronized void releasePort(String wrapperName, int port) {
        Set<Integer> used = portsByWrapper.get(wrapperName);
        if (used != null) {
            used.remove(port);
        }
    }

    public void add(ServiceInfo service) {
        byId.put(service.uniqueId(), service);
        byName.put(service.serviceId().nameKey(), service.uniqueId());
    }

    public Optional<ServiceInfo> remove(UUID uniqueId) {
        ServiceInfo service = byId.remove(uniqueId);
        if (service == null) {
            return Optional.empty();
        }
        byName.remove(service.serviceId().nameKey());
        releasePort(service.wrapperName(), service.port());
        return Optional.of(service);
    }

    public Optional<ServiceInfo> byId(UUID uniqueId) {
        return Optional.ofNullable(byId.get(uniqueId));
    }

    public Optional<ServiceInfo> byName(String name) {
        UUID uniqueId = byName.get(name.toLowerCase(Locale.ROOT));
        return uniqueId == null ? Optional.empty() : byId(uniqueId);
    }

    public Collection<ServiceInfo> all() {
        return List.copyOf(byId.values());
    }

    public List<ServiceInfo> ofGroup(String groupName) {
        return byId.values().stream()
                .filter(service -> service.groupName().equalsIgnoreCase(groupName))
                .toList();
    }

    public List<ServiceInfo> ofWrapper(String wrapperName) {
        return byId.values().stream()
                .filter(service -> wrapperName.equals(service.wrapperName()))
                .toList();
    }

    /** Services of a group that still occupy a slot, for provisioning decisions. */
    public long activeCount(String groupName) {
        return byId.values().stream()
                .filter(service -> service.groupName().equalsIgnoreCase(groupName))
                .filter(service -> service.state().isActive())
                .count();
    }

    public int committedMemory() {
        return byId.values().stream()
                .filter(service -> service.state().isActive())
                .mapToInt(ServiceInfo::memory)
                .sum();
    }

    public int size() {
        return byId.size();
    }

    /** Marks everything a wrapper was running as crashed after it drops. */
    public List<ServiceInfo> markWrapperServicesCrashed(String wrapperName) {
        List<ServiceInfo> affected = ofWrapper(wrapperName).stream()
                .filter(service -> service.state().isActive())
                .toList();
        affected.forEach(service -> service.state(ServiceState.CRASHED));
        return affected;
    }
}
