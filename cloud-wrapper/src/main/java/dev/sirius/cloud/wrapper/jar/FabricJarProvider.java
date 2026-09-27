package dev.sirius.cloud.wrapper.jar;

import dev.sirius.cloud.driver.paper.FabricVersionCatalog;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Fabric's server launcher for a Minecraft version, a loader and an installer.
 *
 * <p>The group's {@code build} is the loader version, or {@code latest} for the
 * newest stable one. Fabric publishes no checksum for the launcher, so this is
 * the one download taken on trust - over HTTPS, from Fabric's own host.
 */
public final class FabricJarProvider implements JarProvider {

    private final Path cacheDirectory;
    private final FabricVersionCatalog catalog;

    public FabricJarProvider(Path jarDirectory, FabricVersionCatalog catalog) {
        this.cacheDirectory = jarDirectory.resolve("cache");
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "fabric";
    }

    @Override
    public Path resolve(String requestedVersion, String build) throws IOException {
        String game = catalog.resolve(requestedVersion);
        String loader = build == null || build.isBlank() || build.equalsIgnoreCase("latest")
                ? FabricVersionCatalog.latestStable("loader")
                : build.trim();
        String installer = FabricVersionCatalog.latestStable("installer");

        Path target = cacheDirectory.resolve("fabric-" + game + "-" + loader + "-" + installer + ".jar");
        String url = FabricVersionCatalog.META + "/loader/" + game + "/" + loader + "/" + installer + "/server/jar";
        return Downloads.ensure(url, target, null, null);
    }
}
