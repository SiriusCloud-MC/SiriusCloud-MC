package dev.sirius.cloud.driver.paper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Minecraft versions Fabric supports.
 *
 * <p>Fabric's list is newest first - the opposite of Purpur's - so it is
 * reversed here. Its stability comes from the data rather than the string:
 * Fabric marks snapshots itself, and trusting that beats guessing from names.
 */
public final class FabricVersionCatalog extends SimpleVersionCatalog {

    public static final String META = "https://meta.fabricmc.net/v2/versions";

    private final Set<String> stable = ConcurrentHashMap.newKeySet();

    public FabricVersionCatalog() {
        super("fabric");
    }

    @Override
    protected List<String> fetch() throws IOException, InterruptedException {
        List<String> versions = new ArrayList<>();
        stable.clear();
        for (JsonElement element : getJson(META + "/game").getAsJsonArray()) {
            JsonObject entry = element.getAsJsonObject();
            String version = entry.get("version").getAsString();
            versions.add(version);
            if (entry.has("stable") && entry.get("stable").getAsBoolean()) {
                stable.add(version);
            }
        }
        Collections.reverse(versions);
        return versions;
    }

    @Override
    protected boolean isStable(String version) {
        return stable.contains(version);
    }

    /** The newest stable loader or installer version, which Fabric also lists newest first. */
    public static String latestStable(String kind) throws IOException {
        try {
            for (JsonElement element : getJson(META + "/" + kind).getAsJsonArray()) {
                JsonObject entry = element.getAsJsonObject();
                if (entry.has("stable") && entry.get("stable").getAsBoolean()) {
                    return entry.get("version").getAsString();
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", exception);
        }
        throw new IOException("Fabric lists no stable " + kind);
    }
}
