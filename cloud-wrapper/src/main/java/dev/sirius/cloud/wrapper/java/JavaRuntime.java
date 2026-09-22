package dev.sirius.cloud.wrapper.java;

import java.nio.file.Path;

/**
 * A JVM installation found on this machine.
 *
 * @param executable  absolute path to {@code java} (or {@code java.exe})
 * @param feature     feature version, e.g. 25 for {@code 25.0.4}, 8 for {@code 1.8.0_402}
 * @param fullVersion the version string the JVM reported
 */
public record JavaRuntime(Path executable, int feature, String fullVersion) {

    @Override
    public String toString() {
        return "Java " + feature + " (" + fullVersion + ") at " + executable;
    }
}
