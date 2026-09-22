package dev.sirius.cloud.wrapper.process;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.wrapper.config.WrapperConfig;
import dev.sirius.cloud.wrapper.jar.JarResolver;
import dev.sirius.cloud.wrapper.template.TemplateManager;
import dev.sirius.cloud.wrapper.util.FileUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Owns every service process on this machine. */
public final class ServiceProcessManager {

    private static final CloudLogger LOGGER = CloudLogger.of("Processes");

    private final WrapperConfig config;
    private final Path runningDirectory;
    private final Path staticDirectory;
    private final Path pluginJar;

    private final JarResolver jars;
    private final TemplateManager templates;

    private final Consumer<ConsoleLine> consoleSink;
    private final BiConsumer<UUID, StateChange> stateSink;

    private final Map<UUID, ServiceProcess> processes = new ConcurrentHashMap<>();

    public ServiceProcessManager(WrapperConfig config,
                                 Path workingDirectory,
                                 JarResolver jars,
                                 TemplateManager templates,
                                 Consumer<ConsoleLine> consoleSink,
                                 BiConsumer<UUID, StateChange> stateSink) {
        this.config = config;
        this.runningDirectory = workingDirectory.resolve("local").resolve("running");
        this.staticDirectory = workingDirectory.resolve("local").resolve("static");
        this.pluginJar = workingDirectory.resolve("plugins").resolve("cloud-plugin-paper.jar");
        this.jars = jars;
        this.templates = templates;
        this.consoleSink = consoleSink;
        this.stateSink = stateSink;
    }

    /**
     * Starts a service.
     *
     * <p>Runs on a virtual thread because the work is almost entirely blocking
     * I/O — an HTTP download, a recursive copy, a process spawn — and it is
     * called from a Netty event loop that must not be held up.
     */
    public void start(ServiceInfo info, ServiceGroup group, String token, String nodeHost, int nodePort) {
        Thread.ofVirtual().name("start-" + info.name()).start(() -> {
            try {
                templates.prepare(group);

                Path serverJar = jars.resolve(group.version(), group.build());

                Path directory = group.staticService()
                        ? staticDirectory.resolve(info.name())
                        : runningDirectory.resolve(info.name());

                ServiceProcess process = new ServiceProcess(
                        info,
                        group,
                        config,
                        directory,
                        line -> consoleSink.accept(new ConsoleLine(info.uniqueId(), info.name(), line)),
                        (state, exitCode) -> {
                            stateSink.accept(info.uniqueId(), new StateChange(state, exitCode));
                            if (state.isTerminal()) {
                                processes.remove(info.uniqueId());
                            }
                        });

                processes.put(info.uniqueId(), process);
                process.start(serverJar, pluginJar, templates, token, nodeHost, nodePort);

            } catch (IOException exception) {
                LOGGER.error("Could not start " + info.name(), exception);
                processes.remove(info.uniqueId());
                stateSink.accept(info.uniqueId(), new StateChange(ServiceState.CRASHED, -1));
            }
        });
    }

    /** Stops a service. Also runs off-thread, since a graceful stop waits. */
    public void stop(UUID serviceId, boolean force) {
        ServiceProcess process = processes.get(serviceId);
        if (process == null) {
            LOGGER.debug("Asked to stop unknown service {}", serviceId);
            return;
        }
        Thread.ofVirtual().name("stop-" + process.info().name())
                .start(() -> process.stop(force));
    }

    public void sendCommand(UUID serviceId, String command) {
        ServiceProcess process = processes.get(serviceId);
        if (process != null) {
            process.sendCommand(command);
        }
    }

    /** Stops everything and waits, for wrapper shutdown. */
    public void stopAll() {
        Collection<ServiceProcess> running = List.copyOf(processes.values());
        if (running.isEmpty()) {
            return;
        }

        LOGGER.info("Stopping {} service(s)", running.size());

        List<Thread> stopping = running.stream()
                .map(process -> Thread.ofVirtual()
                        .name("stop-" + process.info().name())
                        .start(() -> process.stop(false)))
                .toList();

        for (Thread thread : stopping) {
            try {
                thread.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Clears leftovers from a previous run that ended badly. */
    public void cleanStaleDirectories() throws IOException {
        Files.createDirectories(staticDirectory);

        if (Files.isDirectory(runningDirectory)) {
            LOGGER.info("Clearing stale service directories from a previous run");
            FileUtil.deleteRecursively(runningDirectory);
        }
        Files.createDirectories(runningDirectory);
    }

    public int runningCount() {
        return processes.size();
    }

    public int committedMemory() {
        return processes.values().stream().mapToInt(process -> process.info().memory()).sum();
    }

    /** A line of console output from a service. */
    public record ConsoleLine(UUID serviceId, String serviceName, String line) {
    }

    /** A lifecycle transition observed by the wrapper. */
    public record StateChange(ServiceState state, int exitCode) {
    }
}
