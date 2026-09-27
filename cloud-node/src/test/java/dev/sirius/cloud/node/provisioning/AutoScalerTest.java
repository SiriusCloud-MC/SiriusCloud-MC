package dev.sirius.cloud.node.provisioning;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.service.ServiceId;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceProperties;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.api.service.ServiceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoScalerTest {

    private static final long MINUTE = 60_000;

    private static ServiceGroup group(String name, int min, int max) {
        ServiceGroup group = new ServiceGroup(name, ServiceType.SERVER);
        group.minServiceCount(min);
        group.maxServiceCount(max);
        group.autoscale().scaleUpAtPercent(80);
        group.autoscale().scaleDownAfterEmptySeconds(300);
        return group;
    }

    private static ServiceInfo service(String group, int ordinal, ServiceState state, int players, int max) {
        ServiceInfo service = new ServiceInfo(new ServiceId(UUID.randomUUID(), group, ordinal),
                ServiceType.SERVER, "w1", "127.0.0.1", 41000 + ordinal, 1024, max);
        service.state(state);
        service.playerCount(players);
        return service;
    }

    @Test
    void growsWhenRunningServicesAreNearlyFull() {
        AutoScaler scaler = new AutoScaler();
        var decision = scaler.decide(group("Lobby", 1, 5),
                List.of(service("Lobby", 1, ServiceState.RUNNING, 41, 50)), Set.of(), 0);
        assertEquals(AutoScaler.Action.SCALE_UP, decision.action());
    }

    @Test
    void doesNotGrowBelowTheThreshold() {
        AutoScaler scaler = new AutoScaler();
        var decision = scaler.decide(group("Lobby", 1, 5),
                List.of(service("Lobby", 1, ServiceState.RUNNING, 39, 50)), Set.of(), 0);
        assertEquals(AutoScaler.Action.NONE, decision.action());
    }

    @Test
    void waitsForAServiceAlreadyStartingRatherThanStartingAnother() {
        // Capacity arrives late: without this a burst of joins starts a server
        // per tick until the first one is ready.
        AutoScaler scaler = new AutoScaler();
        var decision = scaler.decide(group("Lobby", 1, 5), List.of(
                service("Lobby", 1, ServiceState.RUNNING, 50, 50),
                service("Lobby", 2, ServiceState.STARTING, 0, 50)), Set.of(), 0);
        assertEquals(AutoScaler.Action.NONE, decision.action());
    }

    @Test
    void neverGrowsPastTheMaximum() {
        AutoScaler scaler = new AutoScaler();
        var decision = scaler.decide(group("Lobby", 1, 2), List.of(
                service("Lobby", 1, ServiceState.RUNNING, 50, 50),
                service("Lobby", 2, ServiceState.RUNNING, 50, 50)), Set.of(), 0);
        assertEquals(AutoScaler.Action.NONE, decision.action());
    }

    @Test
    void theCooldownStopsAnImmediateSecondAction() {
        AutoScaler scaler = new AutoScaler();
        ServiceGroup lobby = group("Lobby", 1, 5);
        var full = List.of(service("Lobby", 1, ServiceState.RUNNING, 50, 50));

        assertEquals(AutoScaler.Action.SCALE_UP, scaler.decide(lobby, full, Set.of(), 0).action());
        assertEquals(AutoScaler.Action.NONE, scaler.decide(lobby, full, Set.of(), 5_000).action());
        assertEquals(AutoScaler.Action.SCALE_UP,
                scaler.decide(lobby, full, Set.of(), AutoScaler.COOLDOWN_MILLIS + 1).action());
    }

    @Test
    void shrinksAfterAServiceHasBeenEmptyLongEnoughAndKeepsTheLowNames() {
        AutoScaler scaler = new AutoScaler();
        ServiceGroup lobby = group("Lobby", 1, 5);
        var services = List.of(
                service("Lobby", 1, ServiceState.RUNNING, 3, 50),
                service("Lobby", 2, ServiceState.RUNNING, 0, 50),
                service("Lobby", 3, ServiceState.RUNNING, 0, 50));

        assertEquals(AutoScaler.Action.NONE, scaler.decide(lobby, services, Set.of(), 0).action());
        assertEquals(AutoScaler.Action.NONE, scaler.decide(lobby, services, Set.of(), 4 * MINUTE).action());

        var decision = scaler.decide(lobby, services, Set.of(), 6 * MINUTE);
        assertEquals(AutoScaler.Action.SCALE_DOWN, decision.action());
        assertEquals(3, decision.target().serviceId().ordinal());
    }

    @Test
    void anyoneJoiningResetsTheEmptyTimer() {
        AutoScaler scaler = new AutoScaler();
        ServiceGroup lobby = group("Lobby", 1, 5);
        ServiceInfo busy = service("Lobby", 1, ServiceState.RUNNING, 3, 50);
        ServiceInfo quiet = service("Lobby", 2, ServiceState.RUNNING, 0, 50);

        scaler.decide(lobby, List.of(busy, quiet), Set.of(), 0);
        quiet.playerCount(1);
        scaler.decide(lobby, List.of(busy, quiet), Set.of(), 4 * MINUTE);
        quiet.playerCount(0);
        scaler.decide(lobby, List.of(busy, quiet), Set.of(), 5 * MINUTE);

        // Empty again only since minute 5, so not yet at minute 8.
        assertEquals(AutoScaler.Action.NONE,
                scaler.decide(lobby, List.of(busy, quiet), Set.of(), 8 * MINUTE).action());
        assertEquals(AutoScaler.Action.SCALE_DOWN,
                scaler.decide(lobby, List.of(busy, quiet), Set.of(), 10 * MINUTE + 1).action());
    }

    @Test
    void neverShrinksBelowTheMinimum() {
        AutoScaler scaler = new AutoScaler();
        ServiceGroup lobby = group("Lobby", 2, 5);
        var services = List.of(
                service("Lobby", 1, ServiceState.RUNNING, 0, 50),
                service("Lobby", 2, ServiceState.RUNNING, 0, 50));
        scaler.decide(lobby, services, Set.of(), 0);
        assertEquals(AutoScaler.Action.NONE, scaler.decide(lobby, services, Set.of(), 60 * MINUTE).action());
    }

    @Test
    void doesNotShrinkIfWhatRemainsWouldImmediatelyNeedToGrowAgain() {
        // 70 players over three 50-slot servers: stopping the empty one leaves
        // 70/100, above 80-20=60%, which is too close to the scale-up line.
        AutoScaler scaler = new AutoScaler();
        ServiceGroup lobby = group("Lobby", 1, 5);
        var services = List.of(
                service("Lobby", 1, ServiceState.RUNNING, 35, 50),
                service("Lobby", 2, ServiceState.RUNNING, 35, 50),
                service("Lobby", 3, ServiceState.RUNNING, 0, 50));
        scaler.decide(lobby, services, Set.of(), 0);
        assertEquals(AutoScaler.Action.NONE, scaler.decide(lobby, services, Set.of(), 60 * MINUTE).action());
    }

    @Test
    void evaluatingOneGroupDoesNotResetAnotherGroupsTimers() {
        AutoScaler scaler = new AutoScaler();
        ServiceGroup lobby = group("Lobby", 1, 5);
        ServiceGroup bedwars = group("BedWars", 1, 5);
        var lobbies = List.of(
                service("Lobby", 1, ServiceState.RUNNING, 3, 50),
                service("Lobby", 2, ServiceState.RUNNING, 0, 50));
        var games = List.of(service("BedWars", 1, ServiceState.RUNNING, 2, 8));

        // Interleaved for four minutes - under the five-minute threshold, so
        // nothing is stopped yet. Were BedWars' evaluation clearing Lobby's
        // timers, Lobby-2's empty clock would restart every minute.
        for (long minute = 0; minute <= 4; minute++) {
            scaler.decide(lobby, lobbies, Set.of(), minute * MINUTE);
            scaler.decide(bedwars, games, Set.of(), minute * MINUTE + 1);
        }
        assertEquals(AutoScaler.Action.SCALE_DOWN,
                scaler.decide(lobby, lobbies, Set.of(), 5 * MINUTE + 1).action());
    }

    @Test
    void leavesServicesAnotherProcessOwnsAlone() {
        AutoScaler scaler = new AutoScaler();
        ServiceGroup lobby = group("Lobby", 1, 5);
        ServiceInfo busy = service("Lobby", 1, ServiceState.RUNNING, 3, 50);
        ServiceInfo replacement = service("Lobby", 2, ServiceState.RUNNING, 0, 50);
        scaler.decide(lobby, List.of(busy, replacement), Set.of(), 0);
        assertEquals(AutoScaler.Action.NONE, scaler.decide(lobby, List.of(busy, replacement),
                Set.of(replacement.uniqueId()), 60 * MINUTE).action());
    }

    @Test
    void drainingServicesCountForNeitherCapacityNorShrinking() {
        AutoScaler scaler = new AutoScaler();
        ServiceGroup lobby = group("Lobby", 1, 5);
        ServiceInfo full = service("Lobby", 1, ServiceState.RUNNING, 45, 50);
        ServiceInfo draining = service("Lobby", 2, ServiceState.RUNNING, 0, 50);
        draining.applyProperties(Map.of(ServiceProperties.DRAINING, "true"));
        // 45/50 once the draining one is left out, so it grows.
        assertEquals(AutoScaler.Action.SCALE_UP,
                scaler.decide(lobby, List.of(full, draining), Set.of(), 0).action());
    }

    @Test
    void doesNothingForProxiesDisabledGroupsOrMaintenance() {
        AutoScaler scaler = new AutoScaler();
        var full = List.of(service("Lobby", 1, ServiceState.RUNNING, 50, 50));

        ServiceGroup disabled = group("Lobby", 1, 5);
        disabled.autoscale().enabled(false);
        assertEquals(AutoScaler.Action.NONE, scaler.decide(disabled, full, Set.of(), 0).action());

        ServiceGroup maintenance = group("Lobby", 1, 5);
        maintenance.maintenance(true);
        assertEquals(AutoScaler.Action.NONE, scaler.decide(maintenance, full, Set.of(), 0).action());

        ServiceGroup proxy = new ServiceGroup("Proxy", ServiceType.PROXY);
        assertEquals(AutoScaler.Action.NONE, scaler.decide(proxy, full, Set.of(), 0).action());
    }
}
