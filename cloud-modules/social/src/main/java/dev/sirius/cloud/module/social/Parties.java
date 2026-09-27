package dev.sirius.cloud.module.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.event.events.PlayerDisconnectEvent;
import dev.sirius.cloud.api.event.events.PlayerSwitchServerEvent;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.network.CommandSender;
import dev.sirius.cloud.api.network.NetworkCommand;
import dev.sirius.cloud.api.network.Text;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.module.common.Commands;
import dev.sirius.cloud.module.common.Players;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Parties: players who move through the network together.
 *
 * <p>When the leader lands on a server, every member who is elsewhere is sent
 * after them - so a party that queues for a game plays it together, whichever
 * machine it ends up on.
 *
 * <p>All state sits behind one lock. Commands run concurrently, and a party
 * half-disbanded while somebody accepts an invite into it is a far worse
 * outcome than a few microseconds of waiting.
 *
 * <p>Membership is mirrored into the cloud's key-value store under
 * {@code party:...}. That is the contract other modules read - matchmaking
 * queues a whole party - without depending on this module's classes, which
 * they cannot see anyway.
 */
final class Parties {

    private static final CloudLogger LOGGER = CloudLogger.of("Parties");

    /** Mirrored into the store: {@code party:member:<uuid>} -> party id, {@code party:<id>} -> members. */
    static final String MEMBER_KEY = "party:member:";
    static final String PARTY_KEY = "party:";

    private final CloudDriver driver;
    private final SocialConfig config;
    private final Object lock = new Object();

    private final Map<UUID, Party> parties = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> memberOf = new ConcurrentHashMap<>();

    /** Invited player to (party id to expiry). */
    private final Map<UUID, Map<UUID, Long>> invites = new ConcurrentHashMap<>();

    private static final class Party {
        final UUID id = UUID.randomUUID();
        UUID leader;
        final Set<UUID> members = new LinkedHashSet<>();

        Party(UUID leader) {
            this.leader = leader;
            members.add(leader);
        }
    }

    Parties(CloudDriver driver, SocialConfig config) {
        this.driver = driver;
        this.config = config;
    }

    void register(Consumer<NetworkCommand> register) {
        register.accept(Commands.named("party")
                .aliases("p")
                .description("Play together across the network")
                .executes(this::party)
                .suggests(this::suggest)
                .build());
        register.accept(Commands.named("partychat")
                .aliases("pc")
                .description("Message your party")
                .executes((sender, args) -> SocialModule.player(sender)
                        .ifPresent(self -> chat(self, Commands.rest(args, 0))))
                .build());

        // Off the event thread: events are posted from the node's network
        // threads, and a player who just left is no longer in the registry, so
        // naming them in the announcement is a database read.
        driver.events().subscribe(PlayerSwitchServerEvent.class, event ->
                Thread.ofVirtual().start(() -> followLeader(event.player())));
        driver.events().subscribe(PlayerDisconnectEvent.class, event ->
                Thread.ofVirtual().start(() -> leave(event.player().uniqueId(), true)));
    }

    /** Drops every party, for the module being disabled. */
    void clear() {
        synchronized (lock) {
            List.copyOf(parties.values()).forEach(this::disband);
        }
    }

    // --------------------------------------------------------------- commands

    private void party(CommandSender sender, String[] args) {
        Optional<UUID> self = SocialModule.player(sender);
        if (self.isEmpty()) {
            return;
        }
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        String target = args.length > 1 ? args[1] : "";

        switch (action) {
            case "invite", "add" -> invite(self.get(), target);
            case "accept", "join" -> accept(self.get(), target);
            case "deny", "decline" -> deny(self.get(), target);
            case "leave", "quit" -> {
                if (memberOf.containsKey(self.get())) {
                    leave(self.get(), false);
                } else {
                    tell(self.get(), "<red>You are not in a party.");
                }
            }
            case "kick", "remove" -> kick(self.get(), target);
            case "promote", "leader" -> promote(self.get(), target);
            case "disband" -> disband(self.get());
            case "warp" -> warp(self.get());
            case "chat" -> chat(self.get(), Commands.rest(args, 1));
            case "list", "info" -> list(self.get());
            default -> {
                // "/party Steve" is overwhelmingly meant as an invite.
                if (args.length == 1) {
                    invite(self.get(), args[0]);
                } else {
                    tell(self.get(), "<red>Usage: /party <invite|accept|deny|leave|kick|promote|warp|chat|disband|list>");
                }
            }
        }
    }

    private void invite(UUID self, String name) {
        if (name.isBlank()) {
            tell(self, "<red>Usage: /party invite <player>");
            return;
        }
        Optional<CloudPlayer> target = driver.players().cachedPlayer(name);
        if (target.isEmpty()) {
            tell(self, "<red>" + Text.escape(name) + " is not online.");
            return;
        }
        UUID invited = target.get().uniqueId();
        if (invited.equals(self)) {
            tell(self, "<red>You cannot invite yourself.");
            return;
        }

        Party party;
        synchronized (lock) {
            if (memberOf.containsKey(invited)) {
                tell(self, "<red>" + target.get().name() + " is already in a party.");
                return;
            }
            party = partyOf(self).orElseGet(() -> create(self));
            if (!party.leader.equals(self)) {
                tell(self, "<red>Only the party leader can invite.");
                return;
            }
            if (party.members.size() >= config.maxPartySize()) {
                tell(self, "<red>Your party is full (" + config.maxPartySize() + ").");
                return;
            }
            invites.computeIfAbsent(invited, id -> new ConcurrentHashMap<>())
                    .put(party.id, System.currentTimeMillis() + config.inviteSeconds() * 1000L);
        }

        String leader = nameOf(self);
        tell(invited, "<gold>" + leader + "<yellow> invited you to their party. "
                + "<click:run_command:'/party accept " + leader + "'><hover:show_text:'Join the party'>"
                + "<green><bold>[Accept]</bold></hover></click> "
                + "<click:run_command:'/party deny " + leader + "'><red>[Deny]</click>");
        tell(self, "<yellow>Invited " + target.get().name() + " - the invite lasts "
                + config.inviteSeconds() + "s.");
    }

    private void accept(UUID self, String inviterName) {
        synchronized (lock) {
            if (memberOf.containsKey(self)) {
                tell(self, "<red>Leave your current party first.");
                return;
            }
            Optional<Party> party = pendingInvite(self, inviterName);
            if (party.isEmpty()) {
                tell(self, "<red>You have no pending party invite" + (inviterName.isBlank() ? "" : " from "
                        + Text.escape(inviterName)) + ".");
                return;
            }
            if (party.get().members.size() >= config.maxPartySize()) {
                tell(self, "<red>That party is full.");
                return;
            }
            invites.remove(self);
            party.get().members.add(self);
            memberOf.put(self, party.get().id);
            mirror(party.get());
            announce(party.get(), "<green>" + nameOf(self) + " joined the party.");
        }
    }

    private void deny(UUID self, String inviterName) {
        synchronized (lock) {
            Optional<Party> party = pendingInvite(self, inviterName);
            if (party.isEmpty()) {
                tell(self, "<red>You have no pending party invite.");
                return;
            }
            Map<UUID, Long> pending = invites.get(self);
            if (pending != null) {
                pending.remove(party.get().id);
            }
            tell(party.get().leader, "<yellow>" + nameOf(self) + " declined your party invite.");
            tell(self, "<gray>Invite declined.");
        }
    }

    /**
     * Removes a player from their party.
     *
     * @param disconnected they left the network rather than typing /party leave
     */
    private void leave(UUID self, boolean disconnected) {
        synchronized (lock) {
            invites.remove(self);
            Optional<Party> found = partyOf(self);
            if (found.isEmpty()) {
                return;
            }
            Party party = found.get();
            party.members.remove(self);
            memberOf.remove(self);
            driver.store().delete(MEMBER_KEY + self);

            if (party.members.size() < 2) {
                // A party of one is not a party.
                party.members.forEach(member -> tell(member, "<yellow>Your party was disbanded."));
                disband(party);
                return;
            }
            if (party.leader.equals(self)) {
                party.leader = party.members.iterator().next();
                announce(party, "<yellow>" + nameOf(party.leader) + " now leads the party.");
            }
            mirror(party);
            announce(party, "<yellow>" + nameOf(self) + (disconnected ? " went offline and" : "")
                    + " left the party.");
            if (!disconnected) {
                tell(self, "<gray>You left the party.");
            }
        }
    }

    private void kick(UUID self, String name) {
        synchronized (lock) {
            Optional<Party> party = ledBy(self);
            if (party.isEmpty()) {
                return;
            }
            Optional<UUID> target = memberNamed(party.get(), name);
            if (target.isEmpty() || target.get().equals(self)) {
                tell(self, "<red>" + Text.escape(name) + " is not in your party.");
                return;
            }
            tell(target.get(), "<red>You were removed from the party.");
            leave(target.get(), false);
        }
    }

    private void promote(UUID self, String name) {
        synchronized (lock) {
            Optional<Party> party = ledBy(self);
            if (party.isEmpty()) {
                return;
            }
            Optional<UUID> target = memberNamed(party.get(), name);
            if (target.isEmpty() || target.get().equals(self)) {
                tell(self, "<red>" + Text.escape(name) + " is not in your party.");
                return;
            }
            party.get().leader = target.get();
            mirror(party.get());
            announce(party.get(), "<yellow>" + nameOf(target.get()) + " now leads the party.");
        }
    }

    private void disband(UUID self) {
        synchronized (lock) {
            ledBy(self).ifPresent(party -> {
                announce(party, "<yellow>The party was disbanded.");
                disband(party);
            });
        }
    }

    /** Pulls every member to the leader's server now. */
    private void warp(UUID self) {
        Optional<Party> party;
        synchronized (lock) {
            party = ledBy(self);
        }
        party.ifPresent(found -> {
            String server = driver.players().cachedPlayer(self).flatMap(CloudPlayer::serverName).orElse(null);
            if (server == null) {
                tell(self, "<red>You are not on a server yet.");
                return;
            }
            int moved = pullTo(found, server);
            tell(self, moved == 0 ? "<gray>Everyone is already here." : "<green>Warping " + moved + " member(s) to you.");
        });
    }

    private void chat(UUID self, String message) {
        if (message.isBlank()) {
            tell(self, "<red>Usage: /pc <message>");
            return;
        }
        if (driver.network().isChatRestricted(self)) {
            tell(self, "<red>You are muted.");
            return;
        }
        Optional<Party> party = partyOf(self);
        if (party.isEmpty()) {
            tell(self, "<red>You are not in a party.");
            return;
        }
        announce(party.get(), "<blue>Party <dark_gray>» <white>" + nameOf(self) + "<gray>: <white>"
                + Text.escape(message));
    }

    private void list(UUID self) {
        Optional<Party> party = partyOf(self);
        if (party.isEmpty()) {
            tell(self, "<gray>You are not in a party. <white>/party invite <player><gray> to start one.");
            return;
        }
        StringBuilder text = new StringBuilder("<blue>Party <gray>(" + party.get().members.size()
                + "/" + config.maxPartySize() + ")");
        for (UUID member : party.get().members) {
            Optional<CloudPlayer> online = driver.players().cachedPlayer(member);
            text.append("\n <dark_gray>- ")
                    .append(member.equals(party.get().leader) ? "<gold>★ " : "<white>")
                    .append(nameOf(member))
                    .append(online.flatMap(CloudPlayer::serverName).map(server -> " <gray>on " + server).orElse(""));
        }
        tell(self, text.toString());
    }

    private List<String> suggest(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return Commands.matching(List.of("invite", "accept", "deny", "leave", "kick",
                    "promote", "warp", "chat", "disband", "list"), args);
        }
        if (args.length == 2) {
            return Commands.matching(Players.onlineNames(driver), args);
        }
        return List.of();
    }

    // ------------------------------------------------------------- following

    /** When a leader lands on a server, sends every member elsewhere after them. */
    private void followLeader(CloudPlayer player) {
        if (!config.partyFollowsLeader()) {
            return;
        }
        Optional<Party> party;
        synchronized (lock) {
            party = partyOf(player.uniqueId()).filter(found -> found.leader.equals(player.uniqueId()));
        }
        party.ifPresent(found -> player.serverName().ifPresent(server -> {
            int moved = pullTo(found, server);
            if (moved > 0) {
                LOGGER.debug("Party of {} follows to {} ({} moved)", player.name(), server, moved);
            }
        }));
    }

    private int pullTo(Party party, String server) {
        int moved = 0;
        for (UUID member : List.copyOf(party.members)) {
            if (member.equals(party.leader)) {
                continue;
            }
            Optional<CloudPlayer> online = driver.players().cachedPlayer(member);
            if (online.isEmpty() || online.get().serverName().map(server::equalsIgnoreCase).orElse(false)) {
                continue;
            }
            tell(member, "<gray>Following your party leader to <white>" + server + "<gray>.");
            driver.players().connect(member, server).exceptionally(error -> {
                tell(member, "<red>Could not follow your leader: " + Text.escape(error.getMessage()));
                return null;
            });
            moved++;
        }
        return moved;
    }

    // ------------------------------------------------------------------ state

    private Party create(UUID leader) {
        Party party = new Party(leader);
        parties.put(party.id, party);
        memberOf.put(leader, party.id);
        mirror(party);
        return party;
    }

    private void disband(Party party) {
        parties.remove(party.id);
        for (UUID member : party.members) {
            memberOf.remove(member);
            driver.store().delete(MEMBER_KEY + member);
        }
        invites.values().forEach(pending -> pending.remove(party.id));
        driver.store().delete(PARTY_KEY + party.id);
    }

    /** Writes the party where other modules can read it. */
    private void mirror(Party party) {
        JsonObject document = new JsonObject();
        document.addProperty("leader", party.leader.toString());
        JsonArray members = new JsonArray();
        party.members.forEach(member -> members.add(member.toString()));
        document.add("members", members);
        driver.store().set(PARTY_KEY + party.id, document.toString());
        party.members.forEach(member -> driver.store().set(MEMBER_KEY + member, party.id.toString()));
    }

    private Optional<Party> partyOf(UUID player) {
        UUID id = memberOf.get(player);
        return id == null ? Optional.empty() : Optional.ofNullable(parties.get(id));
    }

    private Optional<Party> ledBy(UUID self) {
        Optional<Party> party = partyOf(self);
        if (party.isEmpty()) {
            tell(self, "<red>You are not in a party.");
            return Optional.empty();
        }
        if (!party.get().leader.equals(self)) {
            tell(self, "<red>Only the party leader can do that.");
            return Optional.empty();
        }
        return party;
    }

    /** An unexpired invite, from the named inviter, or the only one if no name is given. */
    private Optional<Party> pendingInvite(UUID self, String inviterName) {
        Map<UUID, Long> pending = invites.get(self);
        if (pending == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        pending.values().removeIf(expiry -> expiry < now);
        Map<UUID, Party> live = new LinkedHashMap<>();
        pending.keySet().forEach(id -> {
            Party party = parties.get(id);
            if (party != null) {
                live.put(id, party);
            }
        });
        if (inviterName.isBlank()) {
            return live.size() == 1 ? live.values().stream().findFirst() : Optional.empty();
        }
        return live.values().stream()
                .filter(party -> nameOf(party.leader).equalsIgnoreCase(inviterName))
                .findFirst();
    }

    private Optional<UUID> memberNamed(Party party, String name) {
        return party.members.stream().filter(member -> nameOf(member).equalsIgnoreCase(name)).findFirst();
    }

    private void announce(Party party, String message) {
        new ArrayList<>(party.members).forEach(member -> tell(member, message));
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
