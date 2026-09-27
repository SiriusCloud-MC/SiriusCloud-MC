package dev.sirius.cloud.module.matchmaking;

import dev.sirius.cloud.api.service.ServiceInfo;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a group of players should go - kept free of the driver so it can be
 * reasoned about, and tested, on its own.
 */
final class Matchmaker {

    private Matchmaker() {
    }

    /** One queue entry: a player alone, or a party that has to land together. */
    record Ticket(UUID leader, List<UUID> members, String group, long queuedAt) {

        int size() {
            return members.size();
        }
    }

    /**
     * The fullest joinable service with room for the whole ticket.
     *
     * <p>Fullest first, so games fill and start instead of every service
     * waiting at half strength. {@code reserved} counts slots already promised
     * to players still on their way.
     */
    static Optional<ServiceInfo> pick(Collection<ServiceInfo> candidates, int ticketSize, Map<String, Integer> reserved) {
        return candidates.stream()
                .filter(service -> free(service, reserved) >= ticketSize)
                .max(Comparator.comparingInt((ServiceInfo service) -> occupied(service, reserved))
                        .thenComparing(ServiceInfo::name, Comparator.reverseOrder()));
    }

    static int occupied(ServiceInfo service, Map<String, Integer> reserved) {
        return service.playerCount() + reserved.getOrDefault(service.name(), 0);
    }

    static int free(ServiceInfo service, Map<String, Integer> reserved) {
        return service.maxPlayers() - occupied(service, reserved);
    }
}
