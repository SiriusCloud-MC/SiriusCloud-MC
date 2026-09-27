package dev.sirius.cloud.node.provisioning;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceProperties;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.api.service.ServiceType;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides when a group should grow or shrink with its player load.
 *
 * <p>The provisioning loop only ever enforced a minimum, so a group never grew
 * on its own however full it got. This adds the other half: start one more
 * when the running services are nearly full, stop one that has been empty for
 * a while once the group is above its minimum.
 *
 * <p>It only decides; the provisioning task acts. Keeping it a function of the
 * services and a clock is what makes it testable without a running cloud.
 *
 * <p>Two things stop it flapping. A cooldown per group, so the effect of one
 * change is seen before the next. And hysteresis on the way down: a service is
 * only stopped if the capacity left behind would sit comfortably below the
 * scale-up threshold - otherwise removing it would immediately trigger a start.
 */
public final class AutoScaler {

    /** How long after acting on a group before acting on it again. */
    static final long COOLDOWN_MILLIS = 20_000;

    /** How far below the scale-up threshold the group must stay after a stop. */
    static final int HYSTERESIS_PERCENT = 20;

    public enum Action { NONE, SCALE_UP, SCALE_DOWN }

    /** @param target the service to stop, for {@link Action#SCALE_DOWN} */
    public record Decision(Action action, ServiceInfo target, String reason) {
        static final Decision NONE = new Decision(Action.NONE, null, "");
    }

    /** When each running service was last seen empty, cleared as soon as someone joins. */
    private final Map<UUID, Long> emptySince = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAction = new ConcurrentHashMap<>();

    /**
     * @param services  every service of the group
     * @param protected services another process owns right now - a rolling
     *                  replacement, for one - which must not be stopped here
     */
    public Decision decide(ServiceGroup group, Collection<ServiceInfo> services, Set<UUID> protected_, long now) {
        ServiceGroup.Autoscale policy = group.autoscale();
        if (!policy.enabled() || group.type() != ServiceType.SERVER || group.maintenance()) {
            return Decision.NONE;
        }

        long active = services.stream().filter(service -> service.state().isActive()).count();
        long pending = services.stream()
                .filter(service -> service.state() == ServiceState.PREPARED
                        || service.state() == ServiceState.STARTING)
                .count();
        var running = services.stream()
                .filter(service -> service.state() == ServiceState.RUNNING)
                .filter(service -> service.property(ServiceProperties.DRAINING).isEmpty())
                .toList();

        int capacity = running.stream().mapToInt(ServiceInfo::maxPlayers).sum();
        int players = running.stream().mapToInt(ServiceInfo::playerCount).sum();

        // Only this group's own services are touched. The map is shared across
        // groups, and clearing anything not in this list would wipe every other
        // group's timers each tick - so no group but the last would ever shrink.
        for (ServiceInfo service : services) {
            boolean idle = service.state() == ServiceState.RUNNING
                    && service.property(ServiceProperties.DRAINING).isEmpty()
                    && service.playerCount() == 0;
            if (idle) {
                emptySince.putIfAbsent(service.uniqueId(), now);
            } else {
                emptySince.remove(service.uniqueId());
            }
        }

        // Absent means never acted, not "acted at time zero" - which would only
        // happen to work because wall-clock millis are large.
        Long last = lastAction.get(key(group));
        if (last != null && now - last < COOLDOWN_MILLIS) {
            return Decision.NONE;
        }

        int threshold = policy.scaleUpAtPercent();

        // Waiting for one already starting is what stops a surge of joins from
        // starting five servers in five seconds: capacity arrives late.
        if (pending == 0 && active < group.maxServiceCount() && capacity > 0
                && (long) players * 100 >= (long) capacity * threshold) {
            lastAction.put(key(group), now);
            return new Decision(Action.SCALE_UP, null, String.format(Locale.ROOT,
                    "%s is %d%% full (%d/%d)", group.name(), players * 100 / capacity, players, capacity));
        }

        if (active > group.minServiceCount()) {
            long emptyFor = policy.scaleDownAfterEmptySeconds() * 1000L;
            int floor = Math.max(10, threshold - HYSTERESIS_PERCENT);

            // The highest-numbered empty one, so the survivors keep the low
            // names: Lobby-1 and Lobby-2 rather than Lobby-2 and Lobby-5.
            Optional<ServiceInfo> candidate = running.stream()
                    .filter(service -> service.playerCount() == 0)
                    .filter(service -> !protected_.contains(service.uniqueId()))
                    .filter(service -> {
                        Long since = emptySince.get(service.uniqueId());
                        return since != null && now - since >= emptyFor;
                    })
                    .max(Comparator.comparingInt(service -> service.serviceId().ordinal()));

            if (candidate.isPresent()) {
                int remaining = capacity - candidate.get().maxPlayers();
                if (remaining > 0 && (long) players * 100 < (long) remaining * floor) {
                    long idleSeconds = (now - emptySince.get(candidate.get().uniqueId())) / 1000;
                    lastAction.put(key(group), now);
                    emptySince.remove(candidate.get().uniqueId());
                    return new Decision(Action.SCALE_DOWN, candidate.get(), String.format(Locale.ROOT,
                            "%s has been empty for %ds", candidate.get().name(), idleSeconds));
                }
            }
        }

        return Decision.NONE;
    }

    /** Called when a service leaves the registry, whichever group it was in. */
    public void forget(UUID serviceId) {
        emptySince.remove(serviceId);
    }

    private static String key(ServiceGroup group) {
        return group.name().toLowerCase(Locale.ROOT);
    }
}
