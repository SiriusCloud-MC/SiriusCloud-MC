package dev.sirius.cloud.module.matchmaking;

import java.util.ArrayList;
import java.util.List;

/** {@code node/modules/matchmaking/config.json}. */
public final class MatchmakingConfig {

    /** Groups players may queue for. Empty means every server group that is not a fallback. */
    private List<String> playableGroups = new ArrayList<>();

    /**
     * How long a slot stays promised to a player after they are sent.
     * Covers the gap between the connect and the server reporting them, during
     * which the slot still looks free and would otherwise be handed out twice.
     */
    private int reservationSeconds = 15;

    /** How often a waiting player is reminded of their position. */
    private int positionMessageSeconds = 10;

    /** Start a new service when nothing has room, if the group allows another. */
    private boolean startServices = true;

    public List<String> playableGroups() {
        return playableGroups == null ? List.of() : playableGroups;
    }

    public int reservationSeconds() {
        return Math.max(1, reservationSeconds);
    }

    public int positionMessageSeconds() {
        return Math.max(1, positionMessageSeconds);
    }

    public boolean startServices() {
        return startServices;
    }
}
