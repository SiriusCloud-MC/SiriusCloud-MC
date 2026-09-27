package dev.sirius.cloud.node.provisioning;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceProperties;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.node.group.GroupRegistry;
import dev.sirius.cloud.node.player.PlayerManager;
import dev.sirius.cloud.node.player.PlayerRegistry;
import dev.sirius.cloud.node.service.ServiceManager;
import dev.sirius.cloud.node.service.ServiceRegistry;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces a group's services one at a time without ever leaving it short.
 *
 * <p>One mechanism behind three triggers: a scheduled restart when a service
 * outlives its group's {@code maxUptimeMinutes}, a template file changing on a
 * wrapper, and the {@code rollout} command. Each replacement runs through the
 * same four steps:
 *
 * <ol>
 *   <li>start a replacement - allowed one over the group's maximum, since a
 *       group already at its maximum could not otherwise start anything;
 *   <li>wait for it to be ready;
 *   <li>drain the old one: mark it so proxies stop routing new players there,
 *       and move the players it has to the group's other services or a lobby;
 *   <li>stop it, and only then begin the next.
 * </ol>
 *
 * <p>If there is no room for a replacement at all - the memory budget is spent -
 * it drains and stops the old one anyway and lets provisioning bring the group
 * back up. That is a restart with a gap, but it is what the operator asked for,
 * and silently doing nothing would be worse.
 *
 * <p>Server groups only. A proxy's address is fixed, and a replacement proxy on
 * the next port along is of no use to the players connected to the first.
 */
public final class Rollouts {

    private static final CloudLogger LOGGER = CloudLogger.of("Rollout");

    /** How long players get to move off a draining service before it is stopped regardless. */
    static final long DRAIN_TIMEOUT_MILLIS = 60_000;

    /** How long a stopping service gets to disappear before the rollout moves on anyway. */
    static final long STOP_TIMEOUT_MILLIS = 90_000;

    private enum Phase { START, WAIT_READY, DRAIN, STOP }

    private static final class Step {
        final UUID oldId;
        UUID newId;
        Phase phase = Phase.START;
        long phaseSince;
        boolean startRequested;

        Step(UUID oldId, long now) {
            this.oldId = oldId;
            this.phaseSince = now;
        }

        void enter(Phase phase, long now) {
            this.phase = phase;
            this.phaseSince = now;
        }
    }

    private static final class Rollout {
        final String group;
        final String reason;
        final Deque<UUID> queue;
        final int total;
        int done;
        Step current;

        Rollout(String group, String reason, Deque<UUID> queue) {
            this.group = group;
            this.reason = reason;
            this.queue = queue;
            this.total = queue.size();
        }
    }

    private final GroupRegistry groups;
    private final ServiceRegistry services;
    private final ServiceManager serviceManager;
    private final PlayerRegistry players;
    private final PlayerManager playerManager;

    private final Map<String, Rollout> active = new ConcurrentHashMap<>();

    public Rollouts(GroupRegistry groups, ServiceRegistry services, ServiceManager serviceManager,
                    PlayerRegistry players, PlayerManager playerManager) {
        this.groups = groups;
        this.services = services;
        this.serviceManager = serviceManager;
        this.players = players;
        this.playerManager = playerManager;
    }

    /**
     * Replaces every running service of a group.
     *
     * @return why it could not start, or empty if it did
     */
    public Optional<String> start(String groupName, String reason) {
        Optional<ServiceGroup> group = groups.byName(groupName);
        if (group.isEmpty()) {
            return Optional.of("No group named '" + groupName + "'");
        }
        if (group.get().type() != ServiceType.SERVER) {
            return Optional.of(group.get().name() + " is a proxy group; restart proxies one by one with 'restart'");
        }
        if (active.containsKey(key(groupName))) {
            return Optional.of("A rollout of " + group.get().name() + " is already running");
        }

        Deque<UUID> queue = new ArrayDeque<>();
        services.ofGroup(groupName).stream()
                .filter(service -> service.state() == ServiceState.RUNNING)
                .sorted(Comparator.comparingInt(service -> service.serviceId().ordinal()))
                .forEach(service -> queue.add(service.uniqueId()));
        if (queue.isEmpty()) {
            return Optional.of(group.get().name() + " has nothing running to replace");
        }

        active.put(key(groupName), new Rollout(group.get().name(), reason, queue));
        LOGGER.info("Rolling {} service(s) of {}: {}", queue.size(), group.get().name(), reason);
        return Optional.empty();
    }

    /** Replaces one service, unless its group is already rolling. */
    public void replace(ServiceInfo service, String reason) {
        if (active.containsKey(key(service.groupName()))) {
            return;
        }
        Deque<UUID> queue = new ArrayDeque<>(List.of(service.uniqueId()));
        active.put(key(service.groupName()), new Rollout(service.groupName(), reason, queue));
        LOGGER.info("Replacing {}: {}", service.name(), reason);
    }

    public boolean cancel(String groupName) {
        Rollout removed = active.remove(key(groupName));
        if (removed == null) {
            return false;
        }
        // Whatever was draining goes back into rotation rather than being
        // left marked and unroutable forever.
        if (removed.current != null) {
            undrain(removed.current.oldId);
        }
        LOGGER.info("Cancelled the rollout of {} after {} of {}", removed.group, removed.done, removed.total);
        return true;
    }

    public boolean isRolling(String groupName) {
        return active.containsKey(key(groupName));
    }

    /** Services a rollout is working on, which nothing else may stop. */
    public Set<UUID> involved() {
        Set<UUID> ids = new HashSet<>();
        for (Rollout rollout : active.values()) {
            if (rollout.current != null) {
                ids.add(rollout.current.oldId);
                if (rollout.current.newId != null) {
                    ids.add(rollout.current.newId);
                }
            }
        }
        return ids;
    }

    /** One line per running rollout, for the console. */
    public Map<String, String> describe() {
        Map<String, String> lines = new LinkedHashMap<>();
        active.values().forEach(rollout -> lines.put(rollout.group, String.format(Locale.ROOT,
                "%d/%d done, %s (%s)", rollout.done, rollout.total,
                rollout.current == null ? "next" : rollout.current.phase.name().toLowerCase(Locale.ROOT)
                        .replace('_', ' '),
                rollout.reason)));
        return lines;
    }

    /** Called once a second by the provisioning loop. */
    public void tick(long now) {
        scheduleUptimeRestarts(now);
        for (Rollout rollout : List.copyOf(active.values())) {
            try {
                advance(rollout, now);
            } catch (RuntimeException exception) {
                LOGGER.error("The rollout of " + rollout.group + " failed; stopping it", exception);
                cancel(rollout.group);
            }
        }
    }

    /** Starts a replacement for the oldest service past its group's uptime limit. */
    private void scheduleUptimeRestarts(long now) {
        for (ServiceGroup group : groups.all()) {
            if (group.maxUptimeMinutes() <= 0 || group.type() != ServiceType.SERVER
                    || group.maintenance() || isRolling(group.name())) {
                continue;
            }
            long limit = group.maxUptimeMinutes() * 60_000L;
            services.ofGroup(group.name()).stream()
                    .filter(service -> service.state() == ServiceState.RUNNING)
                    .filter(service -> now - service.creationTime() >= limit)
                    .min(Comparator.comparingLong(ServiceInfo::creationTime))
                    .ifPresent(service -> replace(service, "uptime limit of "
                            + group.maxUptimeMinutes() + " minutes"));
        }
    }

    private void advance(Rollout rollout, long now) {
        if (rollout.current == null) {
            UUID next = rollout.queue.poll();
            if (next == null) {
                active.remove(key(rollout.group));
                LOGGER.info("Rollout of {} finished ({} replaced)", rollout.group, rollout.done);
                return;
            }
            Optional<ServiceInfo> old = services.byId(next);
            if (old.isEmpty() || old.get().state() != ServiceState.RUNNING) {
                // It went away on its own in the meantime; nothing to replace.
                rollout.done++;
                return;
            }
            rollout.current = new Step(next, now);
        }

        Step step = rollout.current;
        Optional<ServiceInfo> old = services.byId(step.oldId);

        switch (step.phase) {
            case START -> {
                if (step.startRequested) {
                    return;
                }
                step.startRequested = true;
                Optional<ServiceGroup> group = groups.byName(rollout.group);
                if (group.isEmpty()) {
                    cancel(rollout.group);
                    return;
                }
                serviceManager.start(group.get(), true).whenComplete((service, error) -> {
                    if (error != null) {
                        LOGGER.warn("No room to start a replacement for {} ({}); restarting it in place",
                                old.map(ServiceInfo::name).orElse("?"), error.getMessage());
                        step.enter(Phase.DRAIN, System.currentTimeMillis());
                    } else {
                        step.newId = service.uniqueId();
                        step.enter(Phase.WAIT_READY, System.currentTimeMillis());
                    }
                });
            }
            case WAIT_READY -> {
                Optional<ServiceInfo> replacement = services.byId(step.newId);
                if (replacement.isEmpty() || replacement.get().state().isTerminal()) {
                    // The replacement died on the way up. Carrying on would take
                    // the old service down too and leave the group short, so the
                    // whole rollout stops here with the old one still serving.
                    LOGGER.warn("The replacement for {} failed to start; rollout of {} stopped",
                            old.map(ServiceInfo::name).orElse("?"), rollout.group);
                    active.remove(key(rollout.group));
                    return;
                }
                if (replacement.get().state() == ServiceState.RUNNING) {
                    step.enter(Phase.DRAIN, now);
                }
            }
            case DRAIN -> {
                if (old.isEmpty()) {
                    finishStep(rollout);
                    return;
                }
                if (old.get().property(ServiceProperties.DRAINING).isEmpty()) {
                    beginDrain(old.get());
                }
                boolean empty = players.onService(old.get().name()).isEmpty();
                if (empty || now - step.phaseSince >= DRAIN_TIMEOUT_MILLIS) {
                    serviceManager.stop(old.get().uniqueId(), false);
                    step.enter(Phase.STOP, now);
                }
            }
            case STOP -> {
                // Waiting for it to be gone before the next replacement keeps the
                // group at most one over its size, never two.
                if (old.isEmpty() || now - step.phaseSince >= STOP_TIMEOUT_MILLIS) {
                    finishStep(rollout);
                }
            }
        }
    }

    private void finishStep(Rollout rollout) {
        rollout.done++;
        rollout.current = null;
    }

    /**
     * Takes a service out of rotation and moves its players off.
     *
     * <p>Marked first, so proxies stop sending new players the moment the
     * move begins rather than after it. Players go to the group's other
     * services where there are any, so somebody in a lobby stays in a lobby,
     * and to a lobby otherwise.
     */
    private void beginDrain(ServiceInfo service) {
        serviceManager.updateProperties(service.uniqueId(),
                Map.of(ServiceProperties.DRAINING, "true"), true);

        List<CloudPlayer> onIt = players.onService(service.name());
        if (onIt.isEmpty()) {
            return;
        }
        LOGGER.info("Moving {} player(s) off {}", onIt.size(), service.name());
        for (CloudPlayer player : onIt) {
            playerManager.sendRichMessage(player.uniqueId(),
                    "<yellow>" + service.name() + " is restarting - moving you to another server.");
            playerManager.connectToGroup(player.uniqueId(), service.groupName())
                    .exceptionallyCompose(error ->
                            playerManager.connectToFallback(player.uniqueId(), service.uniqueId()))
                    .exceptionally(error -> {
                        LOGGER.debug("Could not move {}: {}", player.name(), error.getMessage());
                        return null;
                    });
        }
    }

    private void undrain(UUID serviceId) {
        services.byId(serviceId).ifPresent(service -> {
            java.util.HashMap<String, String> change = new java.util.HashMap<>();
            change.put(ServiceProperties.DRAINING, null);
            serviceManager.updateProperties(service.uniqueId(), change, true);
        });
    }

    private static String key(String groupName) {
        return groupName.toLowerCase(Locale.ROOT);
    }
}
