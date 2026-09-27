package dev.sirius.cloud.wrapper.jar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sirius.cloud.api.logging.CloudLogger;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The mods a Fabric server needs to sit behind the cloud's proxy.
 *
 * <p>Vanilla Fabric has no idea what Velocity's modern forwarding is, so a
 * Fabric server would reject every player the proxy sends it. FabricProxy-Lite
 * teaches it, and it needs Fabric API. Both come from Modrinth, matched to the
 * server's Minecraft version and verified against Modrinth's SHA-512.
 */
public final class FabricMods {

    private static final CloudLogger LOGGER = CloudLogger.of("FabricMods");

    private static final String MODRINTH = "https://api.modrinth.com/v2/project/";
    private static final List<String> REQUIRED = List.of("fabric-api", "fabricproxy-lite");

    private final Path cacheDirectory;

    public FabricMods(Path jarDirectory) {
        this.cacheDirectory = jarDirectory.resolve("mods");
    }

    /** The mod jars for a Minecraft version, downloading any that are missing. */
    public List<Path> resolve(String gameVersion) throws IOException {
        List<Path> jars = new ArrayList<>();
        for (String project : REQUIRED) {
            jars.add(resolve(project, gameVersion));
        }
        return jars;
    }

    private Path resolve(String project, String gameVersion) throws IOException {
        String url = MODRINTH + project + "/version"
                + "?loaders=" + URLEncoder.encode("[\"fabric\"]", StandardCharsets.UTF_8)
                + "&game_versions=" + URLEncoder.encode("[\"" + gameVersion + "\"]", StandardCharsets.UTF_8);

        JsonArray versions = JsonParser.parseString(Downloads.getString(url)).getAsJsonArray();
        if (versions.isEmpty()) {
            throw new IOException(project + " has no release for Minecraft " + gameVersion
                    + " yet, and Fabric servers need it to accept players from the proxy");
        }

        // Newest first; prefer a release over a beta when both exist.
        JsonObject chosen = versions.get(0).getAsJsonObject();
        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject();
            if ("release".equals(version.get("version_type").getAsString())) {
                chosen = version;
                break;
            }
        }

        JsonObject file = null;
        for (JsonElement element : chosen.getAsJsonArray("files")) {
            JsonObject candidate = element.getAsJsonObject();
            if (file == null || (candidate.has("primary") && candidate.get("primary").getAsBoolean())) {
                file = candidate;
            }
        }
        if (file == null) {
            throw new IOException(project + " " + chosen.get("version_number").getAsString() + " has no files");
        }

        String sha512 = file.getAsJsonObject("hashes").has("sha512")
                ? file.getAsJsonObject("hashes").get("sha512").getAsString() : null;
        Path target = cacheDirectory.resolve(gameVersion).resolve(file.get("filename").getAsString());
        LOGGER.debug("{} for {}: {}", project, gameVersion, target.getFileName());
        return Downloads.ensure(file.get("url").getAsString(), target, "SHA-512", sha512);
    }
}
