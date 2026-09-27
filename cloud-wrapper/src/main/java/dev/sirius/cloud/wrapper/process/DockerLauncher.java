package dev.sirius.cloud.wrapper.process;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.platform.Platform;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.wrapper.config.WrapperConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Runs each service in its own Docker container, with memory and CPU caps.
 *
 * <p>The point is containment: one runaway server - a leak, a plugin spinning a
 * core - cannot take the machine or its neighbours down with it. Everything the
 * cloud relies on survives the move into a container:
 * <ul>
 *   <li>{@code docker run -i} keeps stdin attached, so the graceful {@code stop}
 *       still reaches the server exactly as it does outside one;
 *   <li>the service directory is bind-mounted, so templates, the connection file
 *       and the console backlog work unchanged, and the files are owned by the
 *       wrapper's user rather than root, so they can still be deleted;
 *   <li>the port is published on the configured bind address, so a restricted
 *       {@code serviceBindAddress} still restricts;
 *   <li>a node on this machine's loopback is reached as {@code host.docker.internal}.
 * </ul>
 *
 * <p>Killing the {@code docker} client does not stop the container, so a forced
 * stop goes through {@code docker kill}. And since a container outlives a
 * crashed wrapper, leftovers from a previous run are removed at startup.
 */
public final class DockerLauncher implements ServiceLauncher {

    private static final CloudLogger LOGGER = CloudLogger.of("Docker");

    private static final String LABEL = "siriuscloud.wrapper";

    private final WrapperConfig config;

    public DockerLauncher(WrapperConfig config) {
        this.config = config;
    }

    @Override
    public boolean containerised() {
        return true;
    }

    @Override
    public List<String> command(ServiceInfo info, ServiceGroup group, Path directory, List<String> javaCommand) {
        WrapperConfig.Isolation isolation = config.isolation();
        List<String> command = new ArrayList<>(List.of(
                isolation.dockerBinary(), "run", "-i", "--rm",
                "--name", containerName(info),
                "--label", LABEL + "=" + config.name(),
                "--label", "siriuscloud.service=" + info.name()));

        ownerOf(directory).ifPresent(owner -> command.addAll(List.of("--user", owner)));

        String bind = config.serviceBindAddress().isBlank() ? "" : config.serviceBindAddress() + ":";
        command.addAll(List.of(
                "-v", directory.toAbsolutePath() + ":/data",
                "-w", "/data",
                "-p", bind + info.port() + ":" + info.port(),
                // Resolves to the host from inside the container on Linux as
                // well; Docker Desktop provides it on Windows and macOS anyway.
                "--add-host", "host.docker.internal:host-gateway",
                // The heap is only part of a JVM's footprint. Capping the
                // container at exactly -Xmx would kill it for merely existing.
                "--memory", (info.memory() + isolation.memoryOverheadMb()) + "m"));

        double cpus = group.cpuLimit() > 0 ? group.cpuLimit() : isolation.defaultCpuLimit();
        if (cpus > 0) {
            command.addAll(List.of("--cpus", String.format(Locale.ROOT, "%.2f", cpus)));
        }

        command.add(isolation.image());

        // The host's java path means nothing inside the image; the image's own
        // java runs instead, with every argument unchanged.
        List<String> inside = new ArrayList<>(javaCommand);
        inside.set(0, "java");
        command.addAll(inside);
        return command;
    }

    @Override
    public void kill(ServiceInfo info, Process process) {
        run(List.of(config.isolation().dockerBinary(), "kill", containerName(info)));
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    @Override
    public String nodeHostFor(String nodeHost) {
        String host = nodeHost.toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost") || host.startsWith("127.") || host.equals("::1");
        return loopback ? "host.docker.internal" : nodeHost;
    }

    /**
     * Removes containers a previous run of this wrapper left behind.
     *
     * <p>A container survives its wrapper crashing, keeps running unmanaged,
     * and holds its port - so the node's replacement for it would fail to bind.
     */
    public void removeLeftovers() {
        String docker = config.isolation().dockerBinary();
        String ids = output(List.of(docker, "ps", "-aq", "--filter", "label=" + LABEL + "=" + config.name()));
        List<String> containers = ids.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
        if (containers.isEmpty()) {
            return;
        }
        LOGGER.warn("Removing {} container(s) left behind by a previous run", containers.size());
        List<String> remove = new ArrayList<>(List.of(docker, "rm", "-f"));
        remove.addAll(containers);
        run(remove);
    }

    /** Fails early with a readable reason if Docker is not usable. */
    public void verify() throws IOException {
        String version = output(List.of(config.isolation().dockerBinary(), "version", "--format", "{{.Server.Version}}"));
        if (version.isBlank()) {
            throw new IOException("Isolation is set to docker, but '" + config.isolation().dockerBinary()
                    + " version' did not answer. Is Docker running, and may this user use it?");
        }
        LOGGER.info("Services run in Docker {} containers from {}", version.trim(), config.isolation().image());
    }

    private String containerName(ServiceInfo info) {
        return ("sirius-" + config.name() + "-" + info.name()).replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    /** {@code uid:gid} of the directory's owner - the wrapper's user - on systems that have them. */
    private static java.util.Optional<String> ownerOf(Path directory) {
        if (!Platform.isPosix()) {
            return java.util.Optional.empty();
        }
        try {
            Object uid = Files.getAttribute(directory, "unix:uid");
            Object gid = Files.getAttribute(directory, "unix:gid");
            return java.util.Optional.of(uid + ":" + gid);
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private static void run(List<String> command) {
        output(command);
    }

    private static String output(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(30, TimeUnit.SECONDS);
            return process.exitValue() == 0 ? result : "";
        } catch (IOException exception) {
            return "";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "";
        } catch (IllegalThreadStateException exception) {
            return "";
        }
    }
}
