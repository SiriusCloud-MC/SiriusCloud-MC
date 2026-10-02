package dev.sirius.cloud.plugin.lobby.world;

import com.google.gson.Gson;
import dev.sirius.cloud.api.database.DatabaseCollection;
import dev.sirius.cloud.api.driver.CloudDriver;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Signs and NPCs placed in the lobby, kept in the cloud's database rather
 * than in one server's world.
 *
 * <p>Every server of a lobby group is started from the same template, so they
 * share one layout. A sign placed on Lobby-1 is stored here with its position,
 * and every other lobby server - including ones started later - builds the
 * same sign in the same spot. Each record names the lobby group it belongs
 * to, so two lobby groups with different worlds keep apart.
 */
public final class Placements {

    private static final Gson GSON = new Gson();

    /** A server sign: where it is, what block it is, and which game it shows. */
    public record SignRecord(String id, String lobby, String world, int x, int y, int z, String block, String game) {
    }

    /** An NPC: where it stands, which way it faces, what it is, and which game it queues for. */
    public record NpcRecord(String id, String lobby, String world, double x, double y, double z, float yaw,
                            float pitch, String entity, String game) {
    }

    private static DatabaseCollection signs() {
        return CloudDriver.instance().database().collection("lobby_signs");
    }

    private static DatabaseCollection npcs() {
        return CloudDriver.instance().database().collection("lobby_npcs");
    }

    public static CompletableFuture<Map<String, SignRecord>> loadSigns(String lobby) {
        return signs().all().thenApply(all -> read(all, SignRecord.class, lobby));
    }

    public static CompletableFuture<Map<String, NpcRecord>> loadNpcs(String lobby) {
        return npcs().all().thenApply(all -> read(all, NpcRecord.class, lobby));
    }

    public static CompletableFuture<Void> save(SignRecord sign) {
        return signs().put(sign.id(), GSON.toJson(sign));
    }

    public static CompletableFuture<Void> save(NpcRecord npc) {
        return npcs().put(npc.id(), GSON.toJson(npc));
    }

    public static CompletableFuture<Boolean> deleteSign(String id) {
        return signs().delete(id);
    }

    public static CompletableFuture<Boolean> deleteNpc(String id) {
        return npcs().delete(id);
    }

    private static <T> Map<String, T> read(Map<String, String> all, Class<T> type, String lobby) {
        Map<String, T> mine = new HashMap<>();
        all.forEach((id, json) -> {
            try {
                T record = GSON.fromJson(json, type);
                String owner = record instanceof SignRecord sign ? sign.lobby() : ((NpcRecord) record).lobby();
                if (lobby.equalsIgnoreCase(owner)) {
                    mine.put(id, record);
                }
            } catch (RuntimeException unreadable) {
                // A record from an older or newer build; not ours to fail over.
            }
        });
        return mine;
    }
}
