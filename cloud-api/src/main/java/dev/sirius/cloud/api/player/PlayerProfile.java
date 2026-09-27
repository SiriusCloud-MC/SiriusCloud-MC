package dev.sirius.cloud.api.player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the cloud remembers about a player after they leave.
 *
 * <p>Kept by the node in the configured database. It is the one place an
 * offline player can be looked up by name, which is what lets a ban, a friend
 * request or a {@code /seen} name somebody who is not currently online.
 */
public final class PlayerProfile {

    private UUID uniqueId;
    private String name;
    private long firstJoin;
    private long lastSeen;
    private long playtimeMillis;
    private String lastServer;
    private List<String> nameHistory = new ArrayList<>();

    /** Required by the JSON codec. */
    @SuppressWarnings("unused")
    PlayerProfile() {
    }

    public PlayerProfile(UUID uniqueId, String name) {
        this.uniqueId = uniqueId;
        this.name = name;
        this.firstJoin = System.currentTimeMillis();
        this.lastSeen = firstJoin;
        this.nameHistory.add(name);
    }

    public UUID uniqueId() {
        return uniqueId;
    }

    public String name() {
        return name;
    }

    /**
     * Records the name a player joined with.
     *
     * @return whether it differs from the last one, i.e. they renamed
     */
    public boolean rename(String newName) {
        if (newName == null || newName.equals(name)) {
            return false;
        }
        this.name = newName;
        if (nameHistory == null) {
            nameHistory = new ArrayList<>();
        }
        nameHistory.remove(newName);
        nameHistory.add(newName);
        return true;
    }

    public long firstJoin() {
        return firstJoin;
    }

    public long lastSeen() {
        return lastSeen;
    }

    public void lastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    public long playtimeMillis() {
        return playtimeMillis;
    }

    public void addPlaytime(long millis) {
        if (millis > 0) {
            this.playtimeMillis += millis;
        }
    }

    public String lastServer() {
        return lastServer == null ? "" : lastServer;
    }

    public void lastServer(String lastServer) {
        this.lastServer = lastServer;
    }

    /** Every name this player has joined with, oldest first. */
    public List<String> nameHistory() {
        return nameHistory == null ? List.of() : List.copyOf(nameHistory);
    }
}
