package dev.sirius.cloud.module.matchmaking;

import dev.sirius.cloud.api.service.ServiceId;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchmakerTest {

    private static ServiceInfo service(int ordinal, int players, int max) {
        ServiceInfo service = new ServiceInfo(new ServiceId(UUID.randomUUID(), "BedWars", ordinal),
                ServiceType.SERVER, "wrapper", "127.0.0.1", 30000 + ordinal, 1024, max);
        service.playerCount(players);
        return service;
    }

    @Test
    void prefersTheFullestServiceWithRoom() {
        ServiceInfo quiet = service(1, 2, 8);
        ServiceInfo busy = service(2, 6, 8);
        assertEquals(busy.name(), Matchmaker.pick(List.of(quiet, busy), 1, Map.of()).orElseThrow().name());
    }

    @Test
    void aPartyOnlyGoesWhereItFitsWhole() {
        ServiceInfo quiet = service(1, 2, 8);
        ServiceInfo busy = service(2, 6, 8);
        assertEquals(quiet.name(), Matchmaker.pick(List.of(quiet, busy), 3, Map.of()).orElseThrow().name());
    }

    @Test
    void reservedSlotsCountAsTaken() {
        ServiceInfo almost = service(1, 6, 8);
        assertTrue(Matchmaker.pick(List.of(almost), 1, Map.of(almost.name(), 2)).isEmpty());
        assertEquals(almost.name(), Matchmaker.pick(List.of(almost), 1, Map.of(almost.name(), 1)).orElseThrow().name());
    }

    @Test
    void nothingFitsMeansNothing() {
        assertTrue(Matchmaker.pick(List.of(service(1, 8, 8)), 1, Map.of()).isEmpty());
        assertTrue(Matchmaker.pick(List.of(), 1, Map.of()).isEmpty());
    }
}
