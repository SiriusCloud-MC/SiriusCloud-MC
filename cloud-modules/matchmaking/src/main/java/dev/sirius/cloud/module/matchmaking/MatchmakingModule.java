package dev.sirius.cloud.module.matchmaking;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.event.events.PlayerDisconnectEvent;
import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.module.CloudModule;
import dev.sirius.cloud.api.module.ModuleContext;
import dev.sirius.cloud.api.network.CommandSender;
import dev.sirius.cloud.api.network.NetworkCommand;
import dev.sirius.cloud.api.network.Text;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceProperties;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.module.common.Commands;
import dev.sirius.cloud.module.common.ModuleConfigs;
import dev.sirius.cloud.module.matchmaking.Matchmaker.Ticket;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@code /play <group>}: queue for a game, alone or as a party, and be sent
 * to a server with room once there is one.
 *
 * <p>Parties come from the social module's entries in the key-value store,
 * so the two modules work together without either depending on the other:
 * without the social module everyone simply queues alone.
 *
 * <p>Everything runs on one thread - the queue, the reservations, the sends -
 * so there is no locking to get wrong, and the commands only hand work to it.
 */
public final class MatchmakingModule implements CloudModule {

    private static final CloudLogger LOGGER = CloudLogger.of("Matchmaking");

    /** For plugins: publish {@code {"player": "<uuid>", "game": "<group>"}} to the node on this channel. */
    public static final String QUEUE_CHANNEL = "matchmaking:queue";
    /** For plugins: {@code {"player": "<uuid>"}} leaves whatever queue the player is in. */
    public static final String LEAVE_CHANNEL = "matchmaking:leave";

    /** A service started for the queue gets this long to come up before another is tried. */
    private static final long START_GRACE_MILLIS = 90_000;

    private CloudDriver driver;
    private MatchmakingConfig config;
    private ScheduledExecutorService lane;

    /** Per group, in queue order. Only touched on {@link #lane}. */
    private final Map<String, List<Ticket>> queues = new LinkedHashMap<>();
    /** Service name -> slots promised, with when each promise lapses. */
    private final Map<String, List<long[]>> reservations = new HashMap<>();
    /** Group -> when a service was last started for it. */
    private final Map<String, Long> startedFor = new HashMap<>();
    private long lastReminder;

    private final List<String> registered = new ArrayList<>();

    @Override
    public void onEnable(ModuleContext context) {
        this.driver = context.driver();
        Path configFile = context.dataDirectory().resolve("config.json");
        try {
            this.config = ModuleConfigs.load(configFile, MatchmakingConfig.class, MatchmakingConfig::new);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + configFile + ": " + exception.getMessage());
        }

        lane = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "matchmaking");
            thread.setDaemon(true);
            return thread;
        });
        lane.scheduleWithFixedDelay(this::safeTick, 1, 1, TimeUnit.SECONDS);

        register(Commands.named("play").aliases("queue", "joinqueue")
                .description("Queue for a game")
                .executes((sender, args) -> lane.execute(() -> play(sender, args)))
                .suggests((sender, args) -> args.length <= 1 ? Commands.matching(playable(), args) : List.of())
                .build());
        register(Commands.named("leavequeue").aliases("lq")
                .description("Leave the queue you are in")
                .executes((sender, args) -> lane.execute(() -> leave(sender)))
                .build());

        // Plugins queue players the same way /play does: {"player": uuid, "game": group}.
        driver.messaging().subscribe(QUEUE_CHANNEL, message -> onRequest(message.payload(), true));
        driver.messaging().subscribe(LEAVE_CHANNEL, message -> onRequest(message.payload(), false));

        driver.events().subscribe(PlayerDisconnectEvent.class,
                event -> lane.execute(() -> dropPlayer(event.player().uniqueId(), event.player().name())));
    }

    private void onRequest(String payload, boolean join) {
        try {
            JsonObject request = JsonParser.parseString(payload).getAsJsonObject();
            UUID player = UUID.fromString(request.get("player").getAsString());
            if (join) {
                String game = request.get("game").getAsString();
                lane.execute(() -> queue(player, game));
            } else {
                lane.execute(() -> leave(player));
            }
        } catch (RuntimeException malformed) {
            LOGGER.debug("Ignoring a malformed queue request: {}", payload);
        }
    }

    @Override
    public void onDisable() {
        driver.messaging().unsubscribe(QUEUE_CHANNEL);
        driver.messaging().unsubscribe(LEAVE_CHANNEL);
        registered.forEach(driver.network()::unregisterCommand);
        registered.clear();
        if (lane != null) {
            lane.shutdownNow();
        }
    }

    private void register(NetworkCommand command) {
        driver.network().registerCommand(command);
        registered.add(command.name());
    }

    // --------------------------------------------------------------- commands

    private void play(CommandSender sender, String[] args) {
        Optional<UUID> self = sender.uniqueId();
        if (self.isEmpty()) {
            sender.sendMessage("Only players can queue.");
            return;
        }
        if (args.length < 1) {
            sender.sendMessage("<gray>Usage: /play <game>. Games: <white>" + String.join(", ", playable()));
            return;
        }
        queue(self.get(), args[0]);
    }

    /**
     * Queues a player, and their party if they lead one. The same whether it
     * came from {@code /play} or from a plugin - a lobby's game menu, say -
     * through {@link #QUEUE_CHANNEL}.
     */
    private void queue(UUID self, String game) {
        Optional<String> group = playable().stream().filter(name -> name.equalsIgnoreCase(game)).findFirst();
        if (group.isEmpty()) {
            tell(self, "<red>There is no game called " + Text.escape(game)
                    + ". <gray>Games: <white>" + String.join(", ", playable()));
            return;
        }

        List<UUID> members = party(self);
        if (members == null) {
            tell(self, "<red>Only your party leader can queue for the party.");
            return;
        }
        Optional<ServiceGroup> definition = driver.groups().cachedGroup(group.get());
        if (definition.isPresent() && members.size() > definition.get().maxPlayers()) {
            tell(self, "<red>Your party is larger than a " + group.get() + " server holds.");
            return;
        }

        // Queueing again moves you: whatever you were waiting for, you now want this.
        members.forEach(this::removeTicketOf);
        Ticket ticket = new Ticket(self, members, group.get(), System.currentTimeMillis());
        queues.computeIfAbsent(group.get(), key -> new ArrayList<>()).add(ticket);

        int position = queues.get(group.get()).size();
        String who = members.size() > 1 ? "Your party (" + members.size() + ") is" : "You are";
        members.forEach(member -> tell(member, "<green>" + who + " queued for <white>" + group.get()
                + "<green>. <gray>Position " + position + ". /leavequeue to leave."));
        tick();
    }

    private void leave(CommandSender sender) {
        sender.uniqueId().ifPresent(this::leave);
    }

    private void leave(UUID self) {
        Optional<Ticket> ticket = ticketOf(self);
        if (ticket.isEmpty()) {
            tell(self, "<red>You are not in a queue.");
            return;
        }
        removeTicketOf(self);
        ticket.get().members().forEach(member -> tell(member, "<yellow>Left the queue for " + ticket.get().group() + "."));
    }

    private void dropPlayer(UUID player, String name) {
        Optional<Ticket> ticket = ticketOf(player);
        if (ticket.isEmpty()) {
            return;
        }
        removeTicketOf(player);
        ticket.get().members().stream().filter(member -> !member.equals(player)).forEach(member ->
                tell(member, "<yellow>" + Text.escape(name) + " left, so your party left the queue."));
    }

    // ------------------------------------------------------------------- loop

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException exception) {
            LOGGER.error("Matchmaking tick failed", exception);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        reservations.values().forEach(list -> list.removeIf(entry -> entry[1] <= now));
        reservations.values().removeIf(List::isEmpty);

        Collection<ServiceInfo> services = driver.services().services().join();

        for (Map.Entry<String, List<Ticket>> entry : queues.entrySet()) {
            String group = entry.getKey();
            List<Ticket> queue = entry.getValue();
            if (queue.isEmpty()) {
                continue;
            }

            List<ServiceInfo> candidates = services.stream()
                    .filter(service -> service.groupName().equalsIgnoreCase(group))
                    .filter(service -> service.state() == ServiceState.RUNNING)
                    .filter(ServiceProperties::isJoinable)
                    .toList();

            Map<String, Integer> reserved = reservedCounts();
            Iterator<Ticket> iterator = queue.iterator();
            boolean blocked = false;
            while (iterator.hasNext()) {
                Ticket ticket = iterator.next();
                Optional<ServiceInfo> target = Matchmaker.pick(candidates, ticket.size(), reserved);
                if (target.isEmpty()) {
                    // A party that fits nowhere does not hold up the solo
                    // players behind it, but it is the reason to start more.
                    blocked = true;
                    continue;
                }
                iterator.remove();
                send(ticket, target.get(), now);
                reserved.merge(target.get().name(), ticket.size(), Integer::sum);
            }

            if (blocked && config.startServices()) {
                maybeStart(group, services, now);
            }
        }

        if (now - lastReminder >= config.positionMessageSeconds() * 1000L) {
            lastReminder = now;
            queues.forEach((group, queue) -> {
                for (int index = 0; index < queue.size(); index++) {
                    Ticket ticket = queue.get(index);
                    long waited = (now - ticket.queuedAt()) / 1000;
                    if (waited < 2) {
                        continue;
                    }
                    String line = "<gray>Queue for <white>" + group + "<gray>: position " + (index + 1)
                            + " of " + queue.size() + ", waiting " + waited + "s.";
                    ticket.members().forEach(member -> tell(member, line));
                }
            });
        }
    }

    private void send(Ticket ticket, ServiceInfo target, long now) {
        reservations.computeIfAbsent(target.name(), key -> new ArrayList<>())
                .add(new long[]{ticket.size(), now + config.reservationSeconds() * 1000L});
        for (UUID member : ticket.members()) {
            tell(member, "<green>Sending you to <white>" + target.name() + "<green>...");
            driver.players().connect(member, target.name()).exceptionally(error -> {
                tell(member, "<red>Could not join " + target.name() + ": " + Text.escape(String.valueOf(error.getMessage())));
                return null;
            });
        }
        LOGGER.info("Sent {} player(s) to {}", ticket.size(), target.name());
    }

    /** Starts one more service for the group, unless one is already on its way or the group is full. */
    private void maybeStart(String group, Collection<ServiceInfo> services, long now) {
        Long last = startedFor.get(group);
        if (last != null && now - last < START_GRACE_MILLIS) {
            return;
        }
        List<ServiceInfo> ofGroup = services.stream()
                .filter(service -> service.groupName().equalsIgnoreCase(group))
                .filter(service -> service.state().isActive())
                .toList();
        if (ofGroup.stream().anyMatch(service -> service.state() == ServiceState.PREPARED
                || service.state() == ServiceState.STARTING)) {
            return;
        }
        Optional<ServiceGroup> definition = driver.groups().cachedGroup(group);
        if (definition.isEmpty() || definition.get().maintenance()
                || ofGroup.size() >= definition.get().maxServiceCount()) {
            return;
        }
        startedFor.put(group, now);
        LOGGER.info("Starting a {} service for the queue", group);
        driver.services().startService(group).exceptionally(error -> {
            LOGGER.warn("Could not start a {} service for the queue: {}", group, error.getMessage());
            return null;
        });
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Integer> reservedCounts() {
        Map<String, Integer> counts = new HashMap<>();
        reservations.forEach((service, list) ->
                counts.put(service, list.stream().mapToInt(entry -> (int) entry[0]).sum()));
        return counts;
    }

    private Optional<Ticket> ticketOf(UUID player) {
        return queues.values().stream().flatMap(List::stream)
                .filter(ticket -> ticket.members().contains(player))
                .findFirst();
    }

    private void removeTicketOf(UUID player) {
        queues.values().forEach(queue -> queue.removeIf(ticket -> ticket.members().contains(player)));
    }

    /**
     * Who queues with this player: just them, their online party if they lead
     * one, or {@code null} if they are in a party someone else leads.
     */
    private List<UUID> party(UUID player) {
        try {
            Optional<String> partyId = driver.store().get("party:member:" + player).join();
            if (partyId.isEmpty()) {
                return List.of(player);
            }
            Optional<String> document = driver.store().get("party:" + partyId.get()).join();
            if (document.isEmpty()) {
                return List.of(player);
            }
            JsonObject party = JsonParser.parseString(document.get()).getAsJsonObject();
            if (!UUID.fromString(party.get("leader").getAsString()).equals(player)) {
                return null;
            }
            List<UUID> members = new ArrayList<>();
            members.add(player);
            JsonArray array = party.getAsJsonArray("members");
            for (JsonElement element : array) {
                UUID member = UUID.fromString(element.getAsString());
                if (!member.equals(player) && driver.players().cachedPlayer(member).isPresent()) {
                    members.add(member);
                }
            }
            return members;
        } catch (RuntimeException exception) {
            // A malformed entry is the social module's problem; queue alone.
            return List.of(player);
        }
    }

    private List<String> playable() {
        if (!config.playableGroups().isEmpty()) {
            return config.playableGroups();
        }
        List<String> names = new ArrayList<>();
        for (ServiceGroup group : driver.groups().groups().join()) {
            if (group.type() == ServiceType.SERVER && !group.fallback()) {
                names.add(group.name());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private void tell(UUID player, String miniMessage) {
        driver.players().sendRichMessage(player, miniMessage).exceptionally(error -> null);
    }
}
