package dev.sirius.cloud.api.platform;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Host operating system detection and the platform-specific details the cloud
 * actually cares about. SiriusCloud supports Linux and Windows as first-class
 * targets; macOS behaves like Linux everywhere it matters here.
 */
public enum Platform {

    LINUX,
    WINDOWS,
    MACOS,
    UNKNOWN;

    private static final Platform CURRENT = detect();

    public static Platform current() {
        return CURRENT;
    }

    public static boolean isWindows() {
        return CURRENT == WINDOWS;
    }

    public static boolean isPosix() {
        return CURRENT == LINUX || CURRENT == MACOS;
    }

    private static Platform detect() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return MACOS;
        }
        if (name.contains("nux") || name.contains("nix") || name.contains("aix") || name.contains("bsd")) {
            return LINUX;
        }
        return UNKNOWN;
    }

    /**
     * Absolute path to the {@code java} binary of the JVM running this process.
     *
     * <p>Never hardcode {@code "java"} for spawned services: on Windows the
     * binary is {@code java.exe}, and on neither platform is the JVM that
     * happens to be on {@code PATH} guaranteed to be the one we are running on.
     */
    public static String javaExecutable() {
        String home = System.getProperty("java.home");
        if (home != null && !home.isBlank()) {
            Path candidate = Path.of(home, "bin", isWindows() ? "java.exe" : "java");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        // Last resort: rely on PATH resolution.
        return isWindows() ? "java.exe" : "java";
    }

    /**
     * Appends the platform's executable suffix to a bare program name.
     */
    public static String executableName(String name) {
        return isWindows() ? name + ".exe" : name;
    }

    /**
     * The script extension used for generated launchers on this platform.
     */
    public static String scriptExtension() {
        return isWindows() ? ".bat" : ".sh";
    }

    public static String describe() {
        return System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " (java " + System.getProperty("java.version") + ", separator '" + File.separator + "')";
    }
}
