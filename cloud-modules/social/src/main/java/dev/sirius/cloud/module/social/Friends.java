package dev.sirius.cloud.module.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sirius.cloud.api.database.DatabaseCollection;
import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.event.events.PlayerConnectEvent;
import dev.sirius.cloud.api.event.events.PlayerDisconnectEvent;
import dev.sirius.cloud.api.network.CommandSender;
import dev.sirius.cloud.api.network.NetworkCommand;
import dev.sirius.cloud.api.network.Text;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.module.common.Commands;
import dev.sirius.cloud.module.common.Durations;
import dev.sirius.cloud.module.common.Players;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Friends, persisted in the cloud's database.
 *
 * <p>A friendship is written to both players' documents, so either can list or
 * end it without the other being online. Read-modify-write on two documents is
 * serialised under one lock: two people befriending each other at the same
 * moment would otherwise each overwrite the other's change.
 */
final class Friends {

    private final CloudDriver driver;
    private final SocialConfig config;
    private final DatabaseCollection store;
    private final Object lock = new Object();

    /** One player's stored friendships. */
    private static final class Record {
        final Set<UUID> friends = new LinkedHashSet<>();
        /** Incoming requests. */
        final Set<UUID> requests = new LinkedHashSet<>();
    }

    Friends(CloudDriver driver, SocialConfig config) {
        this.driver = driver;
        this.config = config;
        this.store = driver.database().collection("friends");
    }

    void register(Consumer<NetworkCommand> register) {
        register.accept(Commands.named("friend")
                .aliases("friends", "f")
                .description("Friends across the network")
                .executes(this::friend)
                .suggests(this::suggest)
                .build());

        // Off the event thread: a notification means a database read.
        driver.events().subscribe(PlayerConnectEvent.class, event ->
                Thread.ofVirtual().start(() -> announce(event.player(), true)));
        driver.events().subscribe(PlayerDisconnectEvent.class, event ->
                Thread.ofVirtual().start(() -> announce(event.player(), false)));
    }

    private void friend(CommandSender sender, String[] args) {
        Optional<UUID> self = SocialModule.player(sender);
        if (self.isEmpty()) {
            return;
        }
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        String name = args.length > 1 ? args[1] : "";

        switch (action) {
            case "add" -> add(self.get(), sender.name(), name);
            case "accept" -> accept(self.get(), name);
            case "deny", "decline" -> deny(self.get(), name);
            case "remove", "delete" -> remove(self.get(), name);
            case "requests" -> requests(self.get());
            case "list" -> list(self.get());
            default -> {
                if (args.length == 1) {
                    add(self.get(), sender.name(), args[0]);
                } else {
                    tell(self.get(), "<red>Usage: /friend <add|accept|deny|remove|list|requests>");
                }
            }
        }
    }

    private void add(UUID self, String selfName, String name) {
        if (name.isBlank()) {
            tell(self, "<red>Usage: /friend add <player>");
            return;
        }
        Optional<Players.Known> target = Players.find(driver, name);
        if (target.isEmpty()) {
            tell(self, "<red>Nobody called " + Text.escape(name) + " has played here.");
            return;
        }
        UUID other = target.get().uniqueId();
        if (other.equals(self)) {
            tell(self, "<red>You cannot befriend yourself.");
            return;
        }

        synchronized (lock) {
            Record mine = load(self);
            Record theirs = load(other);
            if (mine.friends.contains(other)) {
                tell(self, "<yellow>You are already friends with " + target.get().name() + ".");
                return;
            }
            if (mine.requests.contains(other)) {
                // They asked first, so asking back is accepting.
                befriend(self, mine, other, theirs);
                tell(self, "<green>You are now friends with " + target.get().name() + ".");
                tell(other, "<green>" + selfName + " accepted your friend request.");
                return;
            }
            if (mine.friends.size() >= config.maxFriends()) {
                tell(self, "<red>You have reached the limit of " + config.maxFriends() + " friends.");
                return;
            }
            if (!theirs.requests.add(self)) {
                tell(self, "<yellow>You already asked " + target.get().name() + ".");
                return;
            }
            save(other, theirs);
        }

        tell(self, "<green>Friend request sent to " + target.get().name() + ".");
        tell(other, "<gold>" + selfName + "<yellow> wants to be friends. "
                + "<click:run_command:'/friend accept " + selfName + "'><green><bold>[Accept]</bold></click> "
                + "<click:run_command:'/friend deny " + selfName + "'><red>[Deny]</click>");
    }

    private void accept(UUID self, String name) {
        Optional<Players.Known> target = Players.find(driver, name);
        if (target.isEmpty()) {
            tell(self, "<red>No friend request from " + Text.escape(name) + ".");
            return;
        }
        UUID other = target.get().uniqueId();
        synchronized (lock) {
            Record mine = load(self);
            if (!mine.requests.contains(other)) {
                tell(self, "<red>No friend request from " + target.get().name() + ".");
                return;
            }
            Record theirs = load(other);
            if (mine.friends.size() >= config.maxFriends() || theirs.friends.size() >= config.maxFriends()) {
                tell(self, "<red>One of you has reached the friend limit.");
                return;
            }
            befriend(self, mine, other, theirs);
        }
        tell(self, "<green>You are now friends with " + target.get().name() + ".");
        tell(other, "<green>" + nameOf(self) + " accepted your friend request.");
    }

    private void deny(UUID self, String name) {
        Optional<Players.Known> target = Players.find(driver, name);
        synchronized (lock) {
            Record mine = load(self);
            if (target.isEmpty() || !mine.requests.remove(target.get().uniqueId())) {
                tell(self, "<red>No friend request from " + Text.escape(name) + ".");
                return;
            }
            save(self, mine);
        }
        tell(self, "<gray>Friend request declined.");
    }

    private void remove(UUID self, String name) {
        Optional<Players.Known> target = Players.find(driver, name);
        if (target.isEmpty()) {
            tell(self, "<red>" + Text.escape(name) + " is not your friend.");
            return;
        }
        UUID other = target.get().uniqueId();
        synchronized (lock) {
            Record mine = load(self);
            if (!mine.friends.remove(other)) {
                tell(self, "<red>" + target.get().name() + " is not your friend.");
                return;
            }
            Record theirs = load(other);
            theirs.friends.remove(self);
            save(self, mine);
            save(other, theirs);
        }
        tell(self, "<gray>You are no longer friends with " + target.get().name() + ".");
    }

    private void list(UUID self) {
        Record mine = load(self);
        if (mine.friends.isEmpty()) {
            tell(self, "<gray>No friends yet. <white>/friend add <player>");
            return;
        }
        List<String> online = new ArrayList<>();
        List<String> offline = new ArrayList<>();
        for (UUID friend : mine.friends) {
            Optional<CloudPlayer> player = driver.players().cachedPlayer(friend);
            if (player.isPresent()) {
                online.add("<green>" + player.get().name() + player.get().serverName()
                        .map(server -> " <gray>on " + server).orElse(""));
            } else {
                driver.players().profile(friend).join().ifPresent(profile -> offline.add(
                        "<gray>" + profile.name() + " <dark_gray>(" + Durations.ago(profile.lastSeen()) + ")"));
            }
        }
        StringBuilder text = new StringBuilder("<aqua>Friends <gray>(" + online.size() + "/"
                + mine.friends.size() + " online)");
        online.forEach(line -> text.append("\n <dark_gray>- ").append(line));
        offline.forEach(line -> text.append("\n <dark_gray>- ").append(line));
        tell(self, text.toString());
    }

    private void requests(UUID self) {
        Record mine = load(self);
        if (mine.requests.isEmpty()) {
            tell(self, "<gray>No pending friend requests.");
            return;
        }
        StringBuilder text = new StringBuilder("<aqua>Friend requests");
        for (UUID from : mine.requests) {
            String name = nameOf(from);
            text.append("\n <dark_gray>- <white>").append(name)
                    .append(" <click:run_command:'/friend accept ").append(name).append("'><green>[Accept]</click>")
                    .append(" <click:run_command:'/friend deny ").append(name).append("'><red>[Deny]</click>");
        }
        tell(self, text.toString());
    }

    /** Tells a player's online friends they came or went. */
    private void announce(CloudPlayer player, boolean joined) {
        Record record = load(player.uniqueId());
        for (UUID friend : record.friends) {
            if (driver.players().cachedPlayer(friend).isPresent()) {
                tell(friend, joined
                        ? "<green>" + player.name() + " is now online."
                        : "<gray>" + player.name() + " went offline.");
            }
        }
    }

    private List<String> suggest(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return Commands.matching(List.of("add", "accept", "deny", "remove", "list", "requests"), args);
        }
        return args.length == 2 ? Commands.matching(Players.onlineNames(driver), args) : List.of();
    }

    // ------------------------------------------------------------------ store

    private void befriend(UUID self, Record mine, UUID other, Record theirs) {
        mine.requests.remove(other);
        theirs.requests.remove(self);
        mine.friends.add(other);
        theirs.friends.add(self);
        save(self, mine);
        save(other, theirs);
    }

    private Record load(UUID player) {
        Record record = new Record();
        store.get(player.toString()).join().ifPresent(document -> {
            JsonObject json = JsonParser.parseString(document).getAsJsonObject();
            read(json, "friends", record.friends);
            read(json, "requests", record.requests);
        });
        return record;
    }

    private void save(UUID player, Record record) {
        JsonObject json = new JsonObject();
        json.add("friends", write(record.friends));
        json.add("requests", write(record.requests));
        store.put(player.toString(), json.toString()).join();
    }

    private static void read(JsonObject json, String key, Set<UUID> into) {
        if (json.has(key)) {
            for (JsonElement element : json.getAsJsonArray(key)) {
                into.add(UUID.fromString(element.getAsString()));
            }
        }
    }

    private static JsonArray write(Set<UUID> ids) {
        JsonArray array = new JsonArray();
        ids.forEach(id -> array.add(id.toString()));
        return array;
    }

    private String nameOf(UUID player) {
        return driver.players().cachedPlayer(player).map(CloudPlayer::name)
                .or(() -> driver.players().profile(player).join().map(profile -> profile.name()))
                .orElse("?");
    }

    private void tell(UUID player, String message) {
        driver.players().sendRichMessage(player, message).exceptionally(error -> null);
    }
}
