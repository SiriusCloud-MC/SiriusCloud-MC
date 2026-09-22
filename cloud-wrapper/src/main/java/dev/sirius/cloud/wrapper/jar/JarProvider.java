package dev.sirius.cloud.wrapper.jar;

import java.io.IOException;
import java.nio.file.Path;

/** Supplies the server jar a service should run. */
public interface JarProvider {

    String name();

    /**
     * @param version Minecraft version, e.g. {@code 1.21.4}
     * @param build   build number, or {@code latest}
     * @return a local path to the jar, downloaded or cached as needed
     */
    Path resolve(String version, String build) throws IOException;
}
