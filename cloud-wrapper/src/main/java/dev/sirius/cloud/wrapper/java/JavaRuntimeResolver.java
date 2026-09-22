package dev.sirius.cloud.wrapper.java;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.platform.Platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Finds a JVM on this machine that is new enough to run a service.
 *
 * <p>The wrapper's own JVM is <strong>not</strong> assumed to be suitable.
 * Minecraft's minimum Java version moves with its releases (26.x requires Java
 * 25), while the cloud itself targets 21 — so the two genuinely differ, and
 * spawning a server on the wrapper's JVM produces an instant exit with a
 * message nobody sees. Detection happens once at startup instead, so a missing
 * JDK is an actionable error before anything is spawned rather than a crash
 * loop afterwards.
 *
 * <p>Search order covers the standard install locations on Linux, Windows and
 * macOS, plus {@code JAVA_HOME} and whatever is on {@code PATH}.
 */
public final class JavaRuntimeResolver {

    private static final CloudLogger LOGGER = CloudLogger.of("JavaRuntime");

    /** A JVM that does not answer {@code -version} this fast is not one we want. */
    private static final int PROBE_TIMEOUT_SECONDS = 5;

    private volatile List<JavaRuntime> discovered;

    /** Every JVM found on this machine, lowest feature version first. */
    public synchronized List<JavaRuntime> discover() {
        if (discovered != null) {
            return discovered;
        }

        List<JavaRuntime> runtimes = new ArrayList<>();
        for (Path candidate : candidates()) {
            probe(candidate).ifPresent(runtimes::add);
        }

        runtimes.sort(Comparator.comparingInt(JavaRuntime::feature));
        discovered = List.copyOf(runtimes);

        LOGGER.debug("Found {} JVM(s): {}", discovered.size(), discovered);
        return discovered;
    }

    /**
     * The best JVM for running services at or above {@code minimumFeature}.
     *
     * <p>Picks the <em>lowest</em> version that qualifies rather than the
     * newest: it is the one closest to what the server build was tested
     * against, and it keeps behaviour stable when a newer JDK lands on the
     * machine for unrelated reasons. Pin {@code javaExecutable} to override.
     */
    public Optional<JavaRuntime> find(int minimumFeature) {
        return discover().stream()
                .filter(runtime -> runtime.feature() >= minimumFeature)
                .findFirst();
    }

    /**
     * Like {@link #find(int)}, but fails with an actionable message.
     *
     * @throws IOException naming what is missing and how to install it
     */
    public JavaRuntime require(int minimumFeature) throws IOException {
        Optional<JavaRuntime> found = find(minimumFeature);
        if (found.isPresent()) {
            return found.get();
        }

        StringBuilder message = new StringBuilder();
        message.append("No Java ").append(minimumFeature)
                .append("+ installation found, which services need to run. ");

        List<JavaRuntime> available = discover();
        if (available.isEmpty()) {
            message.append("No JVMs were detected at all. ");
        } else {
            message.append("Detected: ");
            for (int i = 0; i < available.size(); i++) {
                message.append(i > 0 ? ", " : "")
                        .append("Java ").append(available.get(i).feature());
            }
            message.append(". ");
        }

        message.append(installHint(minimumFeature));
        throw new IOException(message.toString());
    }

    /** Platform-appropriate advice, since "install Java" is not actionable on its own. */
    private static String installHint(int feature) {
        return switch (Platform.current()) {
            case LINUX -> "Install it with your package manager "
                    + "(Arch: 'sudo pacman -S jdk" + feature + "-openjdk', "
                    + "Debian/Ubuntu: 'sudo apt install openjdk-" + feature + "-jdk'), "
                    + "or set 'javaExecutable' in config.json to its path.";
            case WINDOWS -> "Install it from https://adoptium.net/temurin/releases/?version=" + feature
                    + " , or set 'javaExecutable' in config.json to the full path of java.exe.";
            case MACOS -> "Install it with 'brew install openjdk@" + feature + "', "
                    + "or set 'javaExecutable' in config.json to its path.";
            default -> "Install it from https://adoptium.net/ , "
                    + "or set 'javaExecutable' in config.json to its path.";
        };
    }

    /**
     * Asks a JVM what version it is.
     *
     * <p>{@code -XshowSettings:properties} gives a machine-readable
     * {@code java.version} rather than the free-form banner, which has varied
     * between vendors. The banner is parsed as a fallback anyway.
     */
    public Optional<JavaRuntime> probe(Path executable) {
        if (!Files.isRegularFile(executable)) {
            return Optional.empty();
        }

        try {
            Process process = new ProcessBuilder(
                    executable.toString(), "-XshowSettings:properties", "-version")
                    .redirectErrorStream(true)
                    .start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }
                output = builder.toString();
            }

            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }

            String version = extractVersion(output);
            if (version == null) {
                return Optional.empty();
            }

            int feature = featureOf(version);
            return feature > 0
                    ? Optional.of(new JavaRuntime(executable.toAbsolutePath(), feature, version))
                    : Optional.empty();

        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static String extractVersion(String output) {
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("java.version = ")) {
                return trimmed.substring("java.version = ".length()).trim();
            }
        }
        // Fallback: the banner, e.g. openjdk version "25.0.4" 2026-01-20
        int quote = output.indexOf('"');
        if (quote >= 0) {
            int end = output.indexOf('"', quote + 1);
            if (end > quote) {
                return output.substring(quote + 1, end);
            }
        }
        return null;
    }

    /** {@code 25.0.4} to 25, {@code 1.8.0_402} to 8, {@code 21} to 21. */
    static int featureOf(String version) {
        String value = version.trim();

        // Pre-9 JDKs report 1.x, where x is the feature version.
        if (value.startsWith("1.")) {
            value = value.substring(2);
        }

        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(value.substring(0, end));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /** Every plausible {@code java} binary on this machine, de-duplicated, in preference order. */
    private static List<Path> candidates() {
        Set<Path> paths = new LinkedHashSet<>();

        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            addIfExists(paths, Path.of(javaHome));
        }

        for (Path root : searchRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> children = Files.list(root)) {
                children.filter(Files::isDirectory).forEach(child -> addIfExists(paths, child));
            } catch (IOException exception) {
                LOGGER.debug("Could not list {}: {}", root, exception.getMessage());
            }
        }

        // The wrapper's own JVM, and whatever PATH resolves to, last: they are
        // the least likely to be the right version but should not be ignored.
        String ownHome = System.getProperty("java.home");
        if (ownHome != null && !ownHome.isBlank()) {
            addIfExists(paths, Path.of(ownHome));
        }
        paths.add(Path.of(Platform.executableName("java")));

        return List.copyOf(paths);
    }

    private static void addIfExists(Set<Path> paths, Path javaHome) {
        Path direct = javaHome.resolve("bin").resolve(Platform.executableName("java"));
        if (Files.isRegularFile(direct)) {
            paths.add(canonical(direct));
            return;
        }
        // macOS bundles nest the real home one level down.
        Path bundled = javaHome.resolve("Contents").resolve("Home")
                .resolve("bin").resolve(Platform.executableName("java"));
        if (Files.isRegularFile(bundled)) {
            paths.add(canonical(bundled));
        }
    }

    /**
     * Resolves symlinks so one JVM is not discovered several times.
     *
     * <p>Distributions routinely point {@code default}, {@code default-runtime}
     * and the versioned directory at the same installation, which without this
     * shows up as three identical entries and three redundant probe processes.
     */
    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            return path.toAbsolutePath();
        }
    }

    private static List<Path> searchRoots() {
        List<Path> roots = new ArrayList<>();
        String home = System.getProperty("user.home", "");

        switch (Platform.current()) {
            case LINUX -> {
                roots.add(Path.of("/usr/lib/jvm"));
                roots.add(Path.of("/usr/java"));
                roots.add(Path.of("/opt/java"));
                roots.add(Path.of("/opt"));
            }
            case MACOS -> {
                roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
                roots.add(Path.of("/opt/homebrew/opt"));
                roots.add(Path.of("/usr/local/opt"));
            }
            case WINDOWS -> {
                addWindowsRoots(roots, System.getenv("ProgramFiles"));
                addWindowsRoots(roots, System.getenv("ProgramFiles(x86)"));
                String localAppData = System.getenv("LOCALAPPDATA");
                if (localAppData != null && !localAppData.isBlank()) {
                    roots.add(Path.of(localAppData, "Programs", "Eclipse Adoptium"));
                }
            }
            default -> {
            }
        }

        if (!home.isBlank()) {
            // SDKMAN and JetBrains both keep JDKs under the user's home.
            roots.add(Path.of(home, ".sdkman", "candidates", "java"));
            roots.add(Path.of(home, ".jdks"));
        }

        return roots;
    }

    private static void addWindowsRoots(List<Path> roots, String programFiles) {
        if (programFiles == null || programFiles.isBlank()) {
            return;
        }
        roots.add(Path.of(programFiles, "Java"));
        roots.add(Path.of(programFiles, "Eclipse Adoptium"));
        roots.add(Path.of(programFiles, "Microsoft"));
        roots.add(Path.of(programFiles, "Amazon Corretto"));
        roots.add(Path.of(programFiles, "Zulu"));
        roots.add(Path.of(programFiles, "BellSoft"));
    }
}
