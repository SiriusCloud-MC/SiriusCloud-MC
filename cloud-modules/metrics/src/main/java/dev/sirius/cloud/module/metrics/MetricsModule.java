package dev.sirius.cloud.module.metrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.module.CloudModule;
import dev.sirius.cloud.api.module.ModuleContext;
import dev.sirius.cloud.api.node.NodeInfo;
import dev.sirius.cloud.api.node.WrapperInfo;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.module.common.ModuleConfigs;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Serves the network's numbers at {@code /metrics} for Prometheus.
 *
 * <p>Everything is read from the node's own state at scrape time, so there is
 * no second copy to drift and nothing runs between scrapes. TPS, MSPT and heap
 * come from the services' heartbeats; a service that has not reported yet, or
 * a platform without a TPS figure, is simply absent from those series rather
 * than reported as zero, which would look like an outage on a dashboard.
 */
public final class MetricsModule implements CloudModule {

    private static final CloudLogger LOGGER = CloudLogger.of("Metrics");

    private CloudDriver driver;
    private MetricsConfig config;
    private HttpServer server;

    @Override
    public void onEnable(ModuleContext context) {
        this.driver = context.driver();
        Path configFile = context.dataDirectory().resolve("config.json");
        try {
            this.config = ModuleConfigs.load(configFile, MetricsConfig.class, MetricsConfig::new);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + configFile + ": " + exception.getMessage());
        }

        try {
            server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 16);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not listen on " + config.host() + ":" + config.port()
                    + " for metrics: " + exception.getMessage());
        }
        server.createContext("/metrics", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        if (!"127.0.0.1".equals(config.host()) && config.token().isEmpty()) {
            LOGGER.warn("Metrics are served on {} without a token; anyone who can reach the port can read them.",
                    config.host());
        }
        LOGGER.info("Prometheus metrics on http://{}:{}/metrics", config.host(), config.port());
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            byte[] body;
            try {
                body = collect().getBytes(StandardCharsets.UTF_8);
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not collect metrics: {}", exception.getMessage());
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private boolean authorized(String header) {
        if (config.token().isEmpty()) {
            return true;
        }
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        // Constant time, so the token cannot be guessed a byte at a time.
        return MessageDigest.isEqual(header.substring(7).trim().getBytes(StandardCharsets.UTF_8),
                config.token().getBytes(StandardCharsets.UTF_8));
    }

    String collect() {
        Exposition out = new Exposition();

        NodeInfo node = driver.node().info().join();
        out.gauge("siriuscloud_node_uptime_seconds", "Seconds since the node started.", node.uptimeMillis() / 1000.0);
        out.gauge("siriuscloud_node_memory_committed_mb", "Memory promised to running services, in MB.",
                node.committedMemory());
        out.gauge("siriuscloud_node_memory_max_mb", "Memory the network may give out, in MB.", node.maxMemory());
        out.gauge("siriuscloud_players_online", "Players on the network.", driver.players().onlineCount());

        for (WrapperInfo wrapper : driver.node().wrappers().join()) {
            out.gauge("siriuscloud_wrapper_memory_used_mb", "Memory in use by a wrapper's services, in MB.",
                    wrapper.usedMemory(), "wrapper", wrapper.name());
            out.gauge("siriuscloud_wrapper_memory_max_mb", "Memory a wrapper may give out, in MB.",
                    wrapper.maxMemory(), "wrapper", wrapper.name());
        }

        Collection<ServiceInfo> services = driver.services().services().join();
        Map<String, Map<ServiceState, Integer>> byGroup = new HashMap<>();
        Map<String, Integer> playersByGroup = new HashMap<>();
        for (ServiceGroup group : driver.groups().groups().join()) {
            Map<ServiceState, Integer> states = byGroup.computeIfAbsent(group.name(), key -> new HashMap<>());
            for (ServiceState state : ServiceState.values()) {
                states.put(state, 0);
            }
            playersByGroup.put(group.name(), 0);
            out.gauge("siriuscloud_group_maintenance", "1 if the group is in maintenance.",
                    group.maintenance() ? 1 : 0, "group", group.name());
            out.gauge("siriuscloud_group_services_min", "Services the group keeps online.",
                    group.minServiceCount(), "group", group.name());
            out.gauge("siriuscloud_group_services_max", "Services the group may run.",
                    group.maxServiceCount(), "group", group.name());
        }

        for (ServiceInfo service : services) {
            byGroup.computeIfAbsent(service.groupName(), key -> new HashMap<>())
                    .merge(service.state(), 1, Integer::sum);
            playersByGroup.merge(service.groupName(), service.playerCount(), Integer::sum);

            String[] labels = {"service", service.name(), "group", service.groupName(), "wrapper", service.wrapperName()};
            out.gauge("siriuscloud_service_players", "Players on a service.", service.playerCount(), labels);
            out.gauge("siriuscloud_service_players_max", "Player slots on a service.", service.maxPlayers(), labels);
            out.gauge("siriuscloud_service_uptime_seconds", "Seconds since the service was created.",
                    service.uptimeMillis() / 1000.0, labels);
            out.gauge("siriuscloud_service_memory_mb", "Memory given to a service, in MB.", service.memory(), labels);
            if (service.tps() >= 0 && service.heapMaxMb() > 0) {
                out.gauge("siriuscloud_service_tps", "Ticks per second, one-minute average.", service.tps(), labels);
            }
            if (service.mspt() >= 0 && service.heapMaxMb() > 0) {
                out.gauge("siriuscloud_service_mspt", "Milliseconds per tick.", service.mspt(), labels);
            }
            if (service.heapMaxMb() > 0) {
                out.gauge("siriuscloud_service_heap_used_mb", "Heap in use, in MB.", service.heapUsedMb(), labels);
                out.gauge("siriuscloud_service_heap_max_mb", "Heap the service may grow to, in MB.",
                        service.heapMaxMb(), labels);
            }
        }

        byGroup.forEach((group, states) -> states.forEach((state, count) ->
                out.gauge("siriuscloud_group_services", "Services of a group, by state.", count,
                        "group", group, "state", state.name())));
        playersByGroup.forEach((group, players) ->
                out.gauge("siriuscloud_group_players", "Players across a group.", players, "group", group));

        return out.render();
    }
}
