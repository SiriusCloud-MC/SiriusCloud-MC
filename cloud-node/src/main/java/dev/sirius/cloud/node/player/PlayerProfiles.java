package dev.sirius.cloud.node.player;

import com.google.gson.Gson;
import dev.sirius.cloud.api.database.Database;
import dev.sirius.cloud.api.database.DatabaseCollection;
import dev.sirius.cloud.api.event.EventManager;
import dev.sirius.cloud.api.event.events.PlayerConnectEvent;
import dev.sirius.cloud.api.event.events.PlayerDisconnectEvent;
import dev.sirius.cloud.api.event.events.PlayerSwitchServerEvent;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.api.player.PlayerProfile;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a profile for everyone who has ever joined: names, first and last seen,
 * playtime.
 *
 * <p>Every change for every player goes through one ordered queue. A quick
 * reconnect produces a disconnect and a connect for the same player within
 * milliseconds, and if those raced, the disconnect could save a profile the
 * connect was still loading - adding a session of playtime to a stale copy and
 * then losing it. One lane makes that impossible without a lock per player.
 *
 * <p>Names are indexed separately ({@code player_names}), since the database
 * can only look documents up by key and "who is Steve?" is the question a ban
 * or a friend request actually asks.
 */
public final class PlayerProfiles {

    private static final CloudLogger LOGGER = CloudLogger.of("Profiles");
    private static final Gson GSON = new Gson();

    private final DatabaseCollection profiles;
    private final DatabaseCollection names;

    private final ExecutorService lane = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("sirius-profiles").factory());

    /** Profiles of players online right now, and when their session began. */
    private final Map<UUID, PlayerProfile> online = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();

    public PlayerProfiles(Database database) {
        this.profiles = database.collection("players");
        this.names = database.collection("player_names");
    }

    public void attach(EventManager events) {
        events.subscribe(PlayerConnectEvent.class, event -> onConnect(event.player()));
        events.subscribe(PlayerSwitchServerEvent.class, event ->
                lane.execute(() -> {
                    PlayerProfile profile = online.get(event.player().uniqueId());
                    if (profile != null) {
                        profile.lastServer(event.player().serverName().orElse(""));
                    }
                }));
        events.subscribe(PlayerDisconnectEvent.class, event -> onDisconnect(event.player()));
    }

    private void onConnect(CloudPlayer player) {
        lane.execute(() -> {
            try {
                PlayerProfile profile = load(player.uniqueId())
                        .orElseGet(() -> new PlayerProfile(player.uniqueId(), player.name()));
                String previous = profile.name();
                if (profile.rename(player.name())) {
                    LOGGER.info("{} was previously known as {}", player.name(), previous);
                }
                profile.lastSeen(System.currentTimeMillis());

                online.put(player.uniqueId(), profile);
                // putIfAbsent: a node that already has this session (a proxy
                // re-sync after a node restart) must not reset its start.
                sessionStart.putIfAbsent(player.uniqueId(), System.currentTimeMillis());

                save(profile);
                names.put(player.name().toLowerCase(Locale.ROOT), player.uniqueId().toString()).join();
            } catch (Exception exception) {
                LOGGER.warn("Could not update the profile of {}: {}", player.name(), rootMessage(exception));
            }
        });
    }

    private void onDisconnect(CloudPlayer player) {
        lane.execute(() -> {
            PlayerProfile profile = online.remove(player.uniqueId());
            Long start = sessionStart.remove(player.uniqueId());
            if (profile == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (start != null) {
                profile.addPlaytime(now - start);
            }
            profile.lastSeen(now);
            try {
                save(profile);
            } catch (Exception exception) {
                LOGGER.warn("Could not save the profile of {}: {}", player.name(), rootMessage(exception));
            }
        });
    }

    /**
     * A profile with the current session counted in.
     *
     * <p>A copy, because the live one is mutated on the lane and handing it out
     * would let a caller read a half-applied update.
     */
    public CompletableFuture<Optional<PlayerProfile>> profile(UUID uniqueId) {
        PlayerProfile live = online.get(uniqueId);
        if (live != null) {
            PlayerProfile copy = GSON.fromJson(GSON.toJson(live), PlayerProfile.class);
            Long start = sessionStart.get(uniqueId);
            if (start != null) {
                copy.addPlaytime(System.currentTimeMillis() - start);
            }
            return CompletableFuture.completedFuture(Optional.of(copy));
        }
        return profiles.get(uniqueId.toString())
                .thenApply(json -> json.map(document -> GSON.fromJson(document, PlayerProfile.class)));
    }

    public CompletableFuture<Optional<PlayerProfile>> profile(String name) {
        return names.get(name.toLowerCase(Locale.ROOT)).thenCompose(id -> {
            if (id.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            try {
                return profile(UUID.fromString(id.get()));
            } catch (IllegalArgumentException exception) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
        });
    }

    /**
     * Closes every open session, for node shutdown.
     *
     * <p>Without it, the last session of everyone online when the node stops
     * would simply not count.
     */
    public void flushAll() {
        List<UUID> open = List.copyOf(online.keySet());
        for (UUID id : open) {
            PlayerProfile profile = online.get(id);
            if (profile != null) {
                onDisconnect(new CloudPlayer(id, profile.name(), null, "", ""));
            }
        }
        lane.shutdown();
        try {
            if (!lane.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.warn("Some player profiles were not saved in time");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private Optional<PlayerProfile> load(UUID uniqueId) {
        return profiles.get(uniqueId.toString()).join()
                .map(document -> GSON.fromJson(document, PlayerProfile.class));
    }

    private void save(PlayerProfile profile) {
        profiles.put(profile.uniqueId().toString(), GSON.toJson(profile)).join();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
