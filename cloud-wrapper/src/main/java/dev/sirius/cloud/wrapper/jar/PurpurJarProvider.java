package dev.sirius.cloud.wrapper.jar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sirius.cloud.driver.paper.PurpurVersionCatalog;

import java.io.IOException;
import java.nio.file.Path;

/** Purpur jars, verified against the MD5 Purpur publishes per build. */
public final class PurpurJarProvider implements JarProvider {

    private final Path cacheDirectory;
    private final PurpurVersionCatalog catalog;

    public PurpurJarProvider(Path jarDirectory, PurpurVersionCatalog catalog) {
        this.cacheDirectory = jarDirectory.resolve("cache");
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "purpur";
    }

    @Override
    public Path resolve(String requestedVersion, String build) throws IOException {
        String version = catalog.resolve(requestedVersion);
        String base = PurpurVersionCatalog.API + "/" + version;

        String resolvedBuild = build == null || build.isBlank() || build.equalsIgnoreCase("latest")
                ? JsonParser.parseString(Downloads.getString(base)).getAsJsonObject()
                        .getAsJsonObject("builds").get("latest").getAsString()
                : build.trim();

        Path target = cacheDirectory.resolve("purpur-" + version + "-" + resolvedBuild + ".jar");
        JsonObject detail = JsonParser.parseString(Downloads.getString(base + "/" + resolvedBuild)).getAsJsonObject();
        String md5 = detail.has("md5") ? detail.get("md5").getAsString() : null;

        return Downloads.ensure(base + "/" + resolvedBuild + "/download", target, "MD5", md5);
    }
}
