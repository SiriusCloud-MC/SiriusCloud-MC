package dev.sirius.cloud.api.service;

/**
 * Conventional keys for {@link ServiceInfo#properties()}.
 *
 * <p>None of these are enforced except the {@code cloud:} prefix. They are
 * written down so that a sign wall, a matchmaking queue and a minigame plugin
 * written by three different people agree on what "joinable" means without
 * having to talk to each other.
 */
public final class ServiceProperties {

    /** Where a game is in its life. Values below are the conventional ones. */
    public static final String STATE = "state";

    /** Accepting players - a waiting lobby or an empty arena. */
    public static final String STATE_LOBBY = "LOBBY";

    /** A round is in progress; new players would join mid-game. */
    public static final String STATE_INGAME = "INGAME";

    /** A round has finished and the server is about to reset or stop. */
    public static final String STATE_ENDING = "ENDING";

    /** Free-form: the map currently loaded. */
    public static final String MAP = "map";

    /**
     * Keys only the node may write.
     *
     * <p>A service setting {@code cloud:draining} on itself would pull itself
     * out of routing, which is the node's decision to make.
     */
    public static final String RESERVED_PREFIX = "cloud:";

    /** Set by the node while a service is being emptied before a planned stop. */
    public static final String DRAINING = RESERVED_PREFIX + "draining";

    private ServiceProperties() {
    }

    /** Whether a service in this state should be offered to new players. */
    public static boolean isJoinable(ServiceInfo service) {
        if (service.property(DRAINING).isPresent()) {
            return false;
        }
        String state = service.property(STATE).orElse(STATE_LOBBY);
        return state.equalsIgnoreCase(STATE_LOBBY);
    }
}
