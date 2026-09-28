package dev.sirius.cloud.plugin.lobby;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceProperties;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.api.service.ServiceType;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What the lobby shows of the network, refreshed from the node every couple
 * of seconds.
 *
 * <p>One refresh for the whole server rather than a query per open menu: with
 * a hundred players looking at the game menu, the node would otherwise answer
 * a hundred identical questions a second.
 */
public final class NetworkView {

    /** Where a game stands, for its menu entry. */
    public enum Status {
        OPEN, STARTING, OFFLINE, MAINTENANCE
    }

    public record Game(String group, int players, int servers, int joinable, Status status) {
    }

    private volatile List<ServiceInfo> services = List.of();
    private volatile Collection<ServiceGroup> groups = List.of();

    /** Asks the node. Answers arrive on a network thread and replace the old view whole. */
    public void refresh() {
        CloudDriver driver = CloudDriver.instance();
        driver.services().services().thenAccept(latest -> services = List.copyOf(latest));
        driver.groups().groups().thenAccept(latest -> groups = List.copyOf(latest));
    }

    public List<ServiceInfo> services() {
        return services;
    }

    public Collection<ServiceGroup> groups() {
        return groups;
    }

    public Optional<ServiceGroup> group(String name) {
        return groups.stream().filter(group -> group.name().equalsIgnoreCase(name)).findFirst();
    }

    /** Players on game and lobby servers, the number people care about. */
    public int online() {
        return services.stream()
                .filter(service -> service.type() == ServiceType.SERVER)
                .mapToInt(ServiceInfo::playerCount)
                .sum();
    }

    public List<ServiceInfo> servicesOf(String group) {
        return services.stream()
                .filter(service -> service.groupName().equalsIgnoreCase(group))
                .filter(service -> service.state() != ServiceState.STOPPED && service.state() != ServiceState.CRASHED)
                .sorted(Comparator.comparing(service -> service.serviceId().ordinal()))
                .toList();
    }

    public Game game(String group) {
        List<ServiceInfo> of = servicesOf(group);
        int players = of.stream().mapToInt(ServiceInfo::playerCount).sum();
        List<ServiceInfo> running = of.stream().filter(service -> service.state() == ServiceState.RUNNING).toList();
        int joinable = (int) running.stream().filter(ServiceProperties::isJoinable).count();
        Status status;
        if (group(group).map(ServiceGroup::maintenance).orElse(false)) {
            status = Status.MAINTENANCE;
        } else if (!running.isEmpty()) {
            status = Status.OPEN;
        } else if (!of.isEmpty() || group(group).map(definition -> definition.minServiceCount() > 0).orElse(false)) {
            status = Status.STARTING;
        } else {
            // Nothing running and nothing kept online: the first player to
            // queue makes matchmaking start one, so it is still worth offering.
            status = group(group).isPresent() ? Status.STARTING : Status.OFFLINE;
        }
        return new Game(group, players, running.size(), joinable, status);
    }

    /** Server groups that are not lobbies, for a game menu nobody configured. */
    public List<String> gameGroups() {
        return groups.stream()
                .filter(group -> group.type() == ServiceType.SERVER && !group.fallback())
                .map(ServiceGroup::name)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
