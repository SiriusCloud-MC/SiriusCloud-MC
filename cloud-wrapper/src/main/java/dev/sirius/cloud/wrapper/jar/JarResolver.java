package dev.sirius.cloud.wrapper.jar;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.driver.paper.PaperVersionCatalog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Tries each provider in order and returns the first jar that resolves.
 *
 * <p>Order matters: download first so groups track the build they ask for, then
 * fall back to whatever is on disk so a network problem degrades to a slightly
 * stale server rather than no server.
 */
public final class JarResolver {

    private static final CloudLogger LOGGER = CloudLogger.of(JarResolver.class);

    private final List<JarProvider> providers;

    public JarResolver(List<JarProvider> providers) {
        this.providers = providers;
    }

    public static JarResolver standard(Path jarDirectory, PaperVersionCatalog catalog, String project) {
        return new JarResolver(List.of(
                new PaperMcJarProvider(jarDirectory, catalog, project),
                new LocalJarProvider(jarDirectory, project)));
    }

    public Path resolve(String version, String build) throws IOException {
        IOException last = null;

        for (JarProvider provider : providers) {
            try {
                return provider.resolve(version, build);
            } catch (IOException exception) {
                LOGGER.debug("Provider '{}' could not supply {}/{}: {}",
                        provider.name(), version, build, exception.getMessage());
                last = exception;
            }
        }

        throw new IOException("No jar provider could supply Paper " + version
                + " (build " + build + ")", last);
    }
}
