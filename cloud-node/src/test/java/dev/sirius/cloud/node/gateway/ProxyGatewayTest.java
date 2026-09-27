package dev.sirius.cloud.node.gateway;

import dev.sirius.cloud.api.network.CommandSender;
import dev.sirius.cloud.api.network.LoginFilter;
import dev.sirius.cloud.api.network.NetworkCommand;
import dev.sirius.cloud.node.player.PlayerManager;
import dev.sirius.cloud.node.player.PlayerRegistry;
import dev.sirius.cloud.node.service.ServiceChannelRegistry;
import dev.sirius.cloud.node.service.ServiceRegistry;
import dev.sirius.cloud.protocol.packet.impl.LoginCheckPacket;
import dev.sirius.cloud.protocol.packet.impl.LoginVerdictPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyGatewayTest {

    private ProxyGateway gateway;

    @BeforeEach
    void setUp() {
        ServiceRegistry services = new ServiceRegistry();
        ServiceChannelRegistry channels = new ServiceChannelRegistry();
        PlayerRegistry players = new PlayerRegistry();
        gateway = new ProxyGateway(services, channels, players, new PlayerManager(players, services, channels));
    }

    private LoginVerdictPacket login(Map<String, Boolean> permissions) {
        return gateway.checkLogin(
                new LoginCheckPacket(UUID.randomUUID(), "Steve", "127.0.0.1", permissions), "Proxy-1").join();
    }

    @Test
    void withNoFiltersEveryoneGetsIn() {
        assertTrue(login(Map.of()).allowed());
    }

    @Test
    void theFirstRefusalWins() {
        gateway.registerLoginFilter(attempt -> Optional.of("banned"));
        gateway.registerLoginFilter(attempt -> Optional.of("maintenance"));

        LoginVerdictPacket verdict = login(Map.of());
        assertFalse(verdict.allowed());
        assertEquals("banned", verdict.reason());
    }

    @Test
    void aFilterThatThrowsLetsThePlayerThrough() {
        // A bug in one module must not be able to lock the network out.
        gateway.registerLoginFilter(attempt -> {
            throw new IllegalStateException("database down");
        });
        assertTrue(login(Map.of()).allowed());
    }

    @Test
    void filtersSeeThePermissionsTheProxyEvaluated() {
        gateway.registerLoginFilter(new LoginFilter() {
            @Override
            public Optional<String> check(dev.sirius.cloud.api.network.LoginAttempt attempt) {
                return attempt.hasPermission("bypass") ? Optional.empty() : Optional.of("closed");
            }

            @Override
            public List<String> permissions() {
                return List.of("bypass");
            }
        });
        assertTrue(login(Map.of("bypass", true)).allowed());
        assertFalse(login(Map.of("bypass", false)).allowed());
        assertFalse(login(Map.of()).allowed());
    }

    @Test
    void commandsResolveByAliasAndIgnoreCase() {
        gateway.registerCommand(new NetworkCommand() {
            @Override
            public String name() {
                return "party";
            }

            @Override
            public List<String> aliases() {
                return List.of("p");
            }

            @Override
            public void execute(CommandSender sender, String[] args) {
            }
        });

        assertTrue(gateway.command("PARTY").isPresent());
        assertTrue(gateway.command("p").isPresent());
        assertTrue(gateway.command("unknown").isEmpty());

        gateway.unregisterCommand("Party");
        assertTrue(gateway.command("p").isEmpty());
    }
}
