package dev.sirius.cloud.module.common;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.player.CloudPlayer;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Finding players by name, online or off. */
public final class Players {

    private Players() {
    }

    /** A player known to the cloud by name: online first, then from their profile. */
    public record Known(UUID uniqueId, String name, boolean online) {
    }

    /**
     * Resolves a name to a player the cloud has seen.
     *
     * <p>Blocking - it may reach the database - so only for command bodies,
     * which run on virtual threads.
     */
    public static Optional<Known> find(CloudDriver driver, String name) {
        Optional<CloudPlayer> online = driver.players().cachedPlayer(name);
        if (online.isPresent()) {
            return Optional.of(new Known(online.get().uniqueId(), online.get().name(), true));
        }
        return driver.players().profile(name).join()
                .map(profile -> new Known(profile.uniqueId(), profile.name(), false));
    }

    public static Optional<CloudPlayer> online(CloudDriver driver, UUID uniqueId) {
        return driver.players().cachedPlayer(uniqueId);
    }

    /** Names of everyone online, for tab completion. */
    public static List<String> onlineNames(CloudDriver driver) {
        Collection<CloudPlayer> players = driver.players().onlinePlayers().getNow(List.of());
        return players.stream().map(CloudPlayer::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
