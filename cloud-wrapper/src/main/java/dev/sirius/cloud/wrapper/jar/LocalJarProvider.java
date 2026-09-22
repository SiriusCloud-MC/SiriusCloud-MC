package dev.sirius.cloud.wrapper.jar;

import dev.sirius.cloud.api.logging.CloudLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Serves a jar the user put in {@code local/jars/} themselves.
 *
 * <p>This is the offline escape hatch. If PaperMC's API is unreachable, has
 * changed shape, or the machine simply has no internet, dropping
 * {@code paper.jar} into that directory keeps the cloud working.
 */
public final class LocalJarProvider implements JarProvider {

    private static final CloudLogger LOGGER = CloudLogger.of(LocalJarProvider.class);

    private final Path jarDirectory;
    private final String project;

    public LocalJarProvider(Path jarDirectory, String project) {
        this.jarDirectory = jarDirectory;
        this.project = project;
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public Path resolve(String version, String build) throws IOException {
        Files.createDirectories(jarDirectory);

        // Most specific name first, then progressively looser.
        for (String candidate : new String[]{
                project + "-" + version + "-" + build + ".jar",
                project + "-" + version + ".jar",
                "server.jar",
                project + ".jar"}) {
            Path path = jarDirectory.resolve(candidate);
            if (Files.isRegularFile(path)) {
                LOGGER.info("Using local jar {}", path.getFileName());
                return path;
            }
        }

        Optional<Path> newest = newestJar();
        if (newest.isPresent()) {
            LOGGER.warn("No jar matches {} exactly, falling back to {}",
                    version, newest.get().getFileName());
            return newest.get();
        }

        throw new IOException("No server jar found in " + jarDirectory.toAbsolutePath()
                + ". Place " + project + ".jar there, or let the wrapper download one.");
    }

    private Optional<Path> newestJar() throws IOException {
        try (Stream<Path> files = Files.list(jarDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .max(Comparator.comparingLong(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis();
                        } catch (IOException exception) {
                            return 0L;
                        }
                    }));
        }
    }
}
