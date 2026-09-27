package dev.sirius.cloud.module.social;

/** {@code node/modules/social/config.json}. Each feature can be switched off on its own. */
public final class SocialConfig {

    private boolean parties = true;
    private int maxPartySize = 8;
    private int inviteSeconds = 60;

    /** Members are moved to whatever server their leader joins. */
    private boolean partyFollowsLeader = true;

    private boolean friends = true;
    private int maxFriends = 100;

    private boolean messages = true;

    /** /seen and /playtime. */
    private boolean history = true;

    public boolean parties() {
        return parties;
    }

    public int maxPartySize() {
        return Math.max(2, maxPartySize);
    }

    public int inviteSeconds() {
        return Math.max(10, inviteSeconds);
    }

    public boolean partyFollowsLeader() {
        return partyFollowsLeader;
    }

    public boolean friends() {
        return friends;
    }

    public int maxFriends() {
        return Math.max(1, maxFriends);
    }

    public boolean messages() {
        return messages;
    }

    public boolean history() {
        return history;
    }
}
