package dev.sirius.cloud.api.permission;

/**
 * The channel names the permission module and its plugins agree on.
 *
 * <p>Constants rather than literals in four places: a typo in one of them
 * would produce a system that looks configured and silently never syncs.
 */
public final class PermissionChannels {

    /** Node to everyone: the complete {@link PermissionSnapshot}, as JSON. */
    public static final String SNAPSHOT = "siriuscloud:perms";

    /** Server to node: "send me the snapshot", on join or after a reconnect. */
    public static final String REQUEST = "siriuscloud:perms-req";

    /** Server to node: change something. Carries a request id. */
    public static final String MUTATE = "siriuscloud:perms-mut";

    /** Node to everyone: the outcome of a mutation, echoing its request id. */
    public static final String RESULT = "siriuscloud:perms-ack";

    private PermissionChannels() {
    }
}
