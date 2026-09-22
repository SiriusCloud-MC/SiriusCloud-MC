package dev.sirius.cloud.node.provisioning;

import dev.sirius.cloud.api.logging.CloudLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate-limits provisioning for groups whose services keep dying.
 *
 * <p>Without this the reconciliation loop is a crash loop: a group that cannot
 * start — wrong Java version, port in use, broken plugin — is below its minimum
 * every tick, so the node starts another one every second forever. That floods
 * the console, hammers the disk with template copies, and buries the one log
 * line that says why.
 *
 * <p>The delay grows with consecutive failures and caps rather than giving up,
 * so a group that fails because of something transient still recovers on its
 * own once the cause is gone.
 */
public final class GroupBackoff {

    private static final CloudLogger LOGGER = CloudLogger.of("Provisioning");

    /** Seconds to wait after the 1st, 2nd, ... consecutive failure. The last value is the cap. */
    private static final long[] DELAY_SECONDS = {5, 15, 30, 60, 120};

    /** Failures before the operator gets told loudly, once. */
    private static final int ALERT_AFTER = 3;

    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** Whether the provisioning loop may try this group right now. */
    public boolean ready(String groupName) {
        State state = states.get(key(groupName));
        return state == null || System.currentTimeMillis() >= state.nextAttemptAt;
    }

    /** A service of this group reached RUNNING: clear the penalty. */
    public void recordSuccess(String groupName) {
        State removed = states.remove(key(groupName));
        if (removed != null && removed.consecutiveFailures >= ALERT_AFTER) {
            LOGGER.info("{} is starting successfully again", groupName);
        }
    }

    /** A service of this group died: back off before trying again. */
    public void recordFailure(String groupName) {
        State state = states.computeIfAbsent(key(groupName), key -> new State());

        state.consecutiveFailures++;
        long delay = DELAY_SECONDS[Math.min(state.consecutiveFailures - 1, DELAY_SECONDS.length - 1)];
        state.nextAttemptAt = System.currentTimeMillis() + delay * 1000L;

        if (state.consecutiveFailures == ALERT_AFTER) {
            LOGGER.warn("{} has failed to start {} times in a row - something is wrong with it.",
                    groupName, state.consecutiveFailures);
            LOGGER.warn("Retries continue with a growing delay; fix the cause or set it to maintenance.");
        } else {
            LOGGER.debug("{} failed ({} in a row), next attempt in {}s",
                    groupName, state.consecutiveFailures, delay);
        }
    }

    public int consecutiveFailures(String groupName) {
        State state = states.get(key(groupName));
        return state == null ? 0 : state.consecutiveFailures;
    }

    /** Seconds until this group may be retried, or 0 if it is ready now. */
    public long secondsUntilReady(String groupName) {
        State state = states.get(key(groupName));
        if (state == null) {
            return 0;
        }
        long remaining = state.nextAttemptAt - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }

    private static String key(String groupName) {
        return groupName.toLowerCase(java.util.Locale.ROOT);
    }

    private static final class State {
        private int consecutiveFailures;
        private long nextAttemptAt;
    }
}
