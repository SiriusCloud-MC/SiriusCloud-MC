package dev.sirius.cloud.wrapper.process;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.wrapper.config.WrapperConfig;
import dev.sirius.cloud.wrapper.jar.JarResolver;
import dev.sirius.cloud.wrapper.java.JavaRuntime;
import dev.sirius.cloud.wrapper.java.JavaRuntimeResolver;
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
    private final Path serverPluginJar;
    private final Path proxyPluginJar;

    private final JarResolver serverJars;
    private final JarResolver proxyJars;
    private final TemplateManager templates;
    private final JavaRuntimeResolver javaRuntimes;

    private final Consumer<ConsoleLine> consoleSink;
    private final Consumer<ConsoleBacklog> backlogSink;
    private final BiConsumer<UUID, StateChange> stateSink;
    private final Consumer<CrashReport> crashSink;

    private final Map<UUID, ServiceProcess> processes = new ConcurrentHashMap<>();

    public ServiceProcessManager(WrapperConfig config,
                                 Path workingDirectory,
                                 JarResolver serverJars,
                                 JarResolver proxyJars,
                                 TemplateManager templates,
                                 JavaRuntimeResolver javaRuntimes,
                                 Consumer<ConsoleLine> consoleSink,
                                 Consumer<ConsoleBacklog> backlogSink,
                                 BiConsumer<UUID, StateChange> stateSink,
                                 Consumer<CrashReport> crashSink) {
        this.config = config;
        this.runningDirectory = workingDirectory.resolve("local").resolve("running");
        this.staticDirectory = workingDirectory.resolve("local").resolve("static");
        this.serverPluginJar = workingDirectory.resolve("plugins").resolve("cloud-plugin-paper.jar");
        this.proxyPluginJar = workingDirectory.resolve("plugins").resolve("cloud-plugin-velocity.jar");
        this.serverJars = serverJars;
        this.proxyJars = proxyJars;
        this.templates = templates;
        this.javaRuntimes = javaRuntimes;
        this.consoleSink = consoleSink;
        this.backlogSink = backlogSink;
        this.stateSink = stateSink;
        this.crashSink = crashSink;
    }

    /**
     * Starts a service.
     *
     * <p>Runs on a virtual thread because the work is almost entirely blocking
     * I/O — an HTTP download, a recursive copy, a process spawn — and it is
     * called from a Netty event loop that must not be held up.
     */
    public void start(ServiceInfo info, ServiceGroup group, String token,
                      String nodeHost, int nodePort, String forwardingSecret) {
        Thread.ofVirtual().name("start-" + info.name()).start(() -> {
            try {
                // Resolved before anything is copied or downloaded: if the
                // machine cannot run this service at all, say so immediately
                // rather than after a 60MB download and a doomed spawn.
                String java = javaFor(group);

                templates.prepare(group);

                boolean proxy = group.type() == ServiceType.PROXY;
                Path serverJar = (proxy ? proxyJars : serverJars).resolve(group.version(), group.build());

                Path directory = group.staticService()
                        ? staticDirectory.resolve(info.name())
                        : runningDirectory.resolve(info.name());

                ServiceProcess process = new ServiceProcess(
                        info,
                        group,
                        config,
                        directory,
                        java,
                        line -> consoleSink.accept(new ConsoleLine(info.uniqueId(), info.name(), line)),
                        (state, exitCode) -> {
                            stateSink.accept(info.uniqueId(), new StateChange(state, exitCode));
                            if (state.isTerminal()) {
                                processes.remove(info.uniqueId());
                            }
                        },
                        (exitCode, lastLines) -> crashSink.accept(
                                new CrashReport(info.uniqueId(), info.name(), exitCode, lastLines)));

                processes.put(info.uniqueId(), process);
                process.start(serverJar, proxy ? proxyPluginJar : serverPluginJar,
                        templates, token, nodeHost, nodePort, forwardingSecret);

            } catch (IOException exception) {
                LOGGER.error("Could not start {}: {}", info.name(), exception.getMessage());
                processes.remove(info.uniqueId());

                // The node has no other way to learn why, so the reason travels
                // with the failure instead of only reaching this log file.
                crashSink.accept(new CrashReport(
                        info.uniqueId(), info.name(), -1, List.of(exception.getMessage())));
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

    /**
     * Starts or stops streaming a service's console to the node.
     *
     * <p>On subscribe the backlog is sent first, so an operator attaching to a
     * server that booted an hour ago sees why it is in the state it is in
     * rather than waiting for the next log line.
     */
    public void setConsoleSubscribed(UUID serviceId, boolean subscribed) {
        ServiceProcess process = processes.get(serviceId);
        if (process == null) {
            LOGGER.debug("Console subscription for unknown service {}", serviceId);
            return;
        }

        process.streaming(subscribed);

        if (subscribed) {
            backlogSink.accept(new ConsoleBacklog(
                    serviceId, process.info().name(), process.consoleBacklog()));
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

    /**
     * Every service this machine currently has a process for.
     *
     * <p>Sent to the node after each handshake so it can adopt them instead of
     * provisioning duplicates. Losing the node does not stop a service, so the
     * wrapper is the only thing that still knows these exist.
     */
    public List<RunningService> runningServices() {
        return processes.values().stream()
                .map(process -> new RunningService(process.info(), process.token()))
                .toList();
    }

    /** A live service and the credential its process authenticates with. */
    public record RunningService(ServiceInfo info, String token) {
    }

    public int committedMemory() {
        return processes.values().stream().mapToInt(process -> process.info().memory()).sum();
    }

    /** A line of console output from a service. */
    public record ConsoleLine(UUID serviceId, String serviceName, String line) {
    }

    /** A service's buffered console history, replayed on attach. */
    public record ConsoleBacklog(UUID serviceId, String serviceName, List<String> lines) {
    }

    /** An unexpected exit, with the tail of what the service printed. */
    public record CrashReport(UUID serviceId, String serviceName, int exitCode, List<String> lastLines) {
    }

    /**
     * The JVM a group's services run on: its own override, then the wrapper's
     * pinned path, then detection of a qualifying installation.
     */
    private String javaFor(dev.sirius.cloud.api.group.ServiceGroup group) throws IOException {
        if (!group.javaExecutable().isBlank()) {
            return group.javaExecutable();
        }
        if (!config.javaExecutable().isBlank()) {
            return config.javaExecutable();
        }
        JavaRuntime runtime = javaRuntimes.require(config.serviceJavaVersion());
        return runtime.executable().toString();
    }

    /** A lifecycle transition observed by the wrapper. */
    public record StateChange(ServiceState state, int exitCode) {
    }
}
