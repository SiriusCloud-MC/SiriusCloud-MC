package dev.sirius.cloud.module.rest;

import com.sun.net.httpserver.HttpExchange;
import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.node.NodeInfo;
import dev.sirius.cloud.api.node.WrapperInfo;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.api.service.ServiceInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Every endpoint, routed by path.
 *
 * <p>Reached only through {@link CloudDriver} — the same interface a plugin
 * inside a running server uses. That is not incidental: it means the API can
 * expose nothing the public contract does not already offer, so it cannot
 * quietly become a second, privileged way into the node.
 */
final class ApiHandler {

    /**
     * Cap on how long an endpoint waits for the cloud.
     *
     * <p>On the node these futures are already complete, so this never fires in
     * practice. It is here because "already complete" is an implementation
     * detail of {@code LocalCloudDriver}, and a request thread parked forever
     * on a future nobody completes is the failure this forecloses.
     */
    private static final int TIMEOUT_SECONDS = 10;

    private final CloudDriver driver;

    ApiHandler(CloudDriver driver) {
        this.driver = driver;
    }

    void handle(HttpExchange exchange, String route) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
        String[] parts = route.split("/");

        try {
            if (route.equals("overview") && method.equals("GET")) {
                RestServer.respond(exchange, 200, overview());
                return;
            }
            if (route.equals("node") && method.equals("GET")) {
                RestServer.respond(exchange, 200, node(await(driver.node().info())));
                return;
            }
            if (route.equals("nodes") && method.equals("GET")) {
                RestServer.respond(exchange, 200, list(await(driver.node().nodes()), ApiHandler::node));
                return;
            }
            if (route.equals("wrappers") && method.equals("GET")) {
                RestServer.respond(exchange, 200, list(await(driver.node().wrappers()), ApiHandler::wrapper));
                return;
            }
            if (route.equals("groups") && method.equals("GET")) {
                RestServer.respond(exchange, 200, groups());
                return;
            }
            if (route.equals("players") && method.equals("GET")) {
                RestServer.respond(exchange, 200, list(await(driver.players().onlinePlayers()), ApiHandler::player));
                return;
            }
            if (route.equals("services") && method.equals("GET")) {
                RestServer.respond(exchange, 200, list(await(driver.services().services()), ApiHandler::service));
                return;
            }
            if (route.equals("services") && method.equals("POST")) {
                startServices(exchange);
                return;
            }
            if (route.equals("broadcast") && method.equals("POST")) {
                String message = string(RestServer.readBody(exchange), "message");
                if (message.isBlank()) {
                    RestServer.respond(exchange, 400, Json.error("'message' is required"));
                    return;
                }
                await(driver.players().broadcast(message));
                RestServer.respond(exchange, 200, ok("Broadcast to every player"));
                return;
            }

            // services/{name}/{action}
            if (parts.length == 3 && parts[0].equals("services") && method.equals("POST")) {
                serviceAction(exchange, parts[1], parts[2]);
                return;
            }
            // players/{uuid}/{action}
            if (parts.length == 3 && parts[0].equals("players") && method.equals("POST")) {
                playerAction(exchange, parts[1], parts[2]);
                return;
            }

            RestServer.respond(exchange, 404, Json.error("No such endpoint: " + route));

        } catch (IllegalArgumentException exception) {
            RestServer.respond(exchange, 400, Json.error(exception.getMessage()));
        } catch (CompletionException | IllegalStateException exception) {
            // The cloud refusing an operation is the caller's problem, not a
            // server fault: "no running service of that group", "already at its
            // maximum", "that player is not online".
            RestServer.respond(exchange, 409, Json.error(rootMessage(exception)));
        }
    }

    // ------------------------------------------------------------- actions

    private void startServices(HttpExchange exchange) throws IOException {
        Map<String, Object> body = RestServer.readBody(exchange);
        String group = string(body, "group");
        if (group.isBlank()) {
            RestServer.respond(exchange, 400, Json.error("'group' is required"));
            return;
        }

        int count = (int) number(body, "count", 1);
        if (count < 1 || count > 20) {
            RestServer.respond(exchange, 400, Json.error("'count' must be between 1 and 20"));
            return;
        }

        List<Map<String, Object>> started = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Sequential, and failures stop the loop: the usual reason a start
            // fails is a limit that the next one would hit identically, and
            // reporting one clear reason beats twenty copies of it.
            started.add(service(await(driver.services().startService(group))));
        }

        Map<String, Object> response = Json.map();
        response.put("started", started);
        RestServer.respond(exchange, 201, response);
    }

    private void serviceAction(HttpExchange exchange, String name, String action) throws IOException {
        Optional<ServiceInfo> found = driver.services().cachedService(name);
        if (found.isEmpty()) {
            RestServer.respond(exchange, 404, Json.error("No service named '" + name + "'"));
            return;
        }
        ServiceInfo target = found.get();
        Map<String, Object> body = RestServer.readBody(exchange);

        switch (action) {
            case "stop" -> {
                await(driver.services().stopService(target.uniqueId()));
                RestServer.respond(exchange, 200, ok("Stopping " + target.name()));
            }
            case "command" -> {
                String command = string(body, "command");
                if (command.isBlank()) {
                    RestServer.respond(exchange, 400, Json.error("'command' is required"));
                    return;
                }
                await(driver.services().dispatchCommand(target.uniqueId(), command));
                RestServer.respond(exchange, 200, ok("Sent to " + target.name()));
            }
            default -> RestServer.respond(exchange, 404, Json.error("No such action: " + action));
        }
    }

    private void playerAction(HttpExchange exchange, String rawId, String action) throws IOException {
        UUID playerId;
        try {
            playerId = UUID.fromString(rawId);
        } catch (IllegalArgumentException exception) {
            // Accept a name too: an operator reading the panel has a name in
            // front of them, not a UUID.
            Optional<CloudPlayer> byName = driver.players().cachedPlayer(rawId);
            if (byName.isEmpty()) {
                RestServer.respond(exchange, 404, Json.error("No player '" + rawId + "' is online"));
                return;
            }
            playerId = byName.get().uniqueId();
        }

        Map<String, Object> body = RestServer.readBody(exchange);

        switch (action) {
            case "connect" -> {
                String target = string(body, "target");
                if (target.isBlank()) {
                    RestServer.respond(exchange, 400, Json.error("'target' is required"));
                    return;
                }
                // A service name goes there exactly; anything else is a group
                // and the node balances — the same rule the console follows.
                boolean isService = driver.services().cachedService(target).isPresent();
                await(isService
                        ? driver.players().connect(playerId, target)
                        : driver.players().connectToGroup(playerId, target));
                RestServer.respond(exchange, 200, ok("Sent to " + target));
            }
            case "message" -> {
                String message = string(body, "message");
                if (message.isBlank()) {
                    RestServer.respond(exchange, 400, Json.error("'message' is required"));
                    return;
                }
                await(driver.players().sendMessage(playerId, message));
                RestServer.respond(exchange, 200, ok("Message sent"));
            }
            case "kick" -> {
                String reason = string(body, "reason");
                await(driver.players().kick(playerId,
                        reason.isBlank() ? "Disconnected by an administrator" : reason));
                RestServer.respond(exchange, 200, ok("Kicked"));
            }
            default -> RestServer.respond(exchange, 404, Json.error("No such action: " + action));
        }
    }

    // ----------------------------------------------------------- read models

    /** Everything the panel draws, in one request rather than six. */
    private Map<String, Object> overview() {
        Map<String, Object> body = Json.map();
        body.put("node", node(await(driver.node().info())));
        body.put("nodes", list(await(driver.node().nodes()), ApiHandler::node));
        body.put("wrappers", list(await(driver.node().wrappers()), ApiHandler::wrapper));
        body.put("groups", groups());
        body.put("services", list(await(driver.services().services()), ApiHandler::service));
        body.put("players", list(await(driver.players().onlinePlayers()), ApiHandler::player));
        return body;
    }

    private List<Map<String, Object>> groups() {
        Collection<ServiceGroup> all = await(driver.groups().groups());
        Collection<ServiceInfo> services = await(driver.services().services());

        List<Map<String, Object>> result = new ArrayList<>();
        for (ServiceGroup group : all) {
            long online = services.stream()
                    .filter(service -> service.groupName().equalsIgnoreCase(group.name()))
                    .filter(service -> service.state().isActive())
                    .count();

            Map<String, Object> entry = Json.map();
            entry.put("name", group.name());
            entry.put("type", group.type().name());
            entry.put("online", online);
            entry.put("minServices", group.minServiceCount());
            entry.put("maxServices", group.maxServiceCount());
            entry.put("memory", group.memory());
            entry.put("maxPlayers", group.maxPlayers());
            entry.put("startPort", group.startPort());
            entry.put("version", group.version());
            entry.put("staticService", group.staticService());
            entry.put("maintenance", group.maintenance());
            entry.put("fallback", group.fallback());
            result.add(entry);
        }
        return result;
    }

    private static Map<String, Object> node(NodeInfo info) {
        Map<String, Object> body = Json.map();
        body.put("name", info.name());
        body.put("platform", info.platform());
        body.put("connectAddress", info.connectAddress());
        body.put("port", info.port());
        body.put("maxMemory", info.maxMemory());
        body.put("committedMemory", info.committedMemory());
        body.put("serviceCount", info.serviceCount());
        body.put("wrapperCount", info.wrapperCount());
        body.put("playerCount", info.playerCount());
        body.put("uptimeMillis", info.uptimeMillis());
        return body;
    }

    private static Map<String, Object> wrapper(WrapperInfo info) {
        Map<String, Object> body = Json.map();
        body.put("name", info.name());
        body.put("host", info.host());
        body.put("platform", info.platform());
        body.put("maxMemory", info.maxMemory());
        body.put("usedMemory", info.usedMemory());
        body.put("freeMemory", info.freeMemory());
        return body;
    }

    private static Map<String, Object> service(ServiceInfo info) {
        Map<String, Object> body = Json.map();
        body.put("id", info.uniqueId().toString());
        body.put("name", info.name());
        body.put("group", info.groupName());
        body.put("type", info.type().name());
        body.put("state", info.state().name());
        body.put("host", info.host());
        body.put("port", info.port());
        body.put("memory", info.memory());
        body.put("players", info.playerCount());
        body.put("maxPlayers", info.maxPlayers());
        body.put("wrapper", info.wrapperName());
        body.put("fallback", info.fallback());
        body.put("uptimeMillis", info.uptimeMillis());
        return body;
    }

    private static Map<String, Object> player(CloudPlayer player) {
        Map<String, Object> body = Json.map();
        body.put("id", player.uniqueId().toString());
        body.put("name", player.name());
        body.put("server", player.serverName().orElse(null));
        body.put("proxy", player.proxyName());
        body.put("address", player.address());
        body.put("onlineMillis", player.onlineMillis());
        return body;
    }

    // ---------------------------------------------------------------- utils

    private static <T> List<Map<String, Object>> list(
            Collection<T> values, java.util.function.Function<T, Map<String, Object>> mapper) {
        return values.stream().map(mapper).toList();
    }

    private static Map<String, Object> ok(String message) {
        Map<String, Object> body = Json.map();
        body.put("ok", true);
        body.put("message", message);
        return body;
    }

    private <T> T await(CompletableFuture<T> future) {
        return future.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    private static String string(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private static double number(Map<String, Object> body, String key, double fallback) {
        Object value = body.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return fallback;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
