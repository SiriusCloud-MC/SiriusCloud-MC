package dev.sirius.cloud.wrapper.process;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.api.service.ServerSoftware;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.wrapper.config.WrapperConfig;
import dev.sirius.cloud.wrapper.jar.FabricMods;
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
import java.util.function.Function;

/** Owns every service process on this machine. */
public final class ServiceProcessManager {

    private static final CloudLogger LOGGER = CloudLogger.of("Processes");

    private final WrapperConfig config;
    private final Path runningDirectory;
    private final Path staticDirectory;
    private final Path serverPluginJar;
    private final Path proxyPluginJar;

    private final Function<ServerSoftware, JarResolver> jars;
    private final FabricMods fabricMods;
    private final ServiceLauncher launcher;
    private final TemplateManager templates;
    private final JavaRuntimeResolver javaRuntimes;

    private final Consumer<ConsoleLine> consoleSink;
    private final Consumer<ConsoleBacklog> backlogSink;
    private final BiConsumer<UUID, StateChange> stateSink;
    private final Consumer<CrashReport> crashSink;

    /** Told when a service's port turns out to be held by something outside the cloud. */
    private volatile Consumer<Integer> portUnavailableSink = port -> { };

    /** Told when a service without the cloud plugin logs that it finished starting. */
    private volatile Consumer<ServiceProcess> logReadySink = process -> { };

    public void onLogReady(Consumer<ServiceProcess> sink) {
        this.logReadySink = sink;
    }

    private final Map<UUID, ServiceProcess> processes = new ConcurrentHashMap<>();

    public ServiceProcessManager(WrapperConfig config,
                                 Path workingDirectory,
                                 Function<ServerSoftware, JarResolver> jars,
                                 FabricMods fabricMods,
                                 ServiceLauncher launcher,
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
        this.jars = jars;
        this.fabricMods = fabricMods;
        this.launcher = launcher;
        this.templates = templates;
        this.javaRuntimes = javaRuntimes;
        this.consoleSink = consoleSink;
        this.backlogSink = backlogSink;
        this.stateSink = stateSink;
        this.crashSink = crashSink;
    }

    public void onPortUnavailable(Consumer<Integer> sink) {
        this.portUnavailableSink = sink;
    }

    /**
     * Fails early and specifically if a service's port is already taken.
     *
     * <p>The node allocates ports from its own bookkeeping, which cannot see a
     * stray process or some other program on this machine. Without the check the
     * server downloads, copies, spawns and then dies with a bind error buried in
     * its log - and the node hands the next start the same port.
     */
    private void requirePortFree(ServiceInfo info) throws IOException {
        String bind = config.serviceBindAddress().isBlank() ? "0.0.0.0" : config.serviceBindAddress();
        try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
            // Reuse, so a port in TIME_WAIT from the service's own previous run
            // is not mistaken for one somebody else holds.
            probe.setReuseAddress(true);
            probe.bind(new java.net.InetSocketAddress(bind, info.port()));
        } catch (IOException exception) {
            portUnavailableSink.accept(info.port());
            throw new IOException("Port " + info.port() + " is already in use on this machine by something "
                    + "outside the cloud. The node will pick another port for the next start.");
        }
    }

    /**
     * Starts a service.
     *
     * <p>Runs on a virtual thread because the work is almost entirely blocking
     * I/O — an HTTP download, a recursive copy, a process spawn — and it is
     * called from a Netty event loop that must not be held up.
     */
    private volatile java.util.function.Supplier<List<String>> nodeEndpoints = List::of;

    /** Where to learn every node's address, for services to fail over between. */
    public void nodeEndpoints(java.util.function.Supplier<List<String>> endpoints) {
        this.nodeEndpoints = endpoints;
    }

    public void start(ServiceInfo info, ServiceGroup group, String token,
                      String nodeHost, int nodePort, String forwardingSecret) {
        Thread.ofVirtual().name("start-" + info.name()).start(() -> {
            try {
                // Resolved before anything is copied or downloaded: if the
                // machine cannot run this service at all, say so immediately
                // rather than after a 60MB download and a doomed spawn.
                String java = javaFor(group);
                requirePortFree(info);

                templates.prepare(group);

                boolean proxy = group.type() == ServiceType.PROXY;
                ServerSoftware software = group.software();
                Path serverJar = jars.apply(software).resolve(group.version(), group.build());

                // Fabric cannot run the cloud's Bukkit plugin, so it needs the
                // mod that understands the proxy's forwarding instead.
                List<Path> mods = software == ServerSoftware.FABRIC
                        ? fabricMods.resolve(resolvedVersion(group, software))
                        : List.of();

                Path directory = group.staticService()
                        ? staticDirectory.resolve(info.name())
                        : runningDirectory.resolve(info.name());

                ServiceProcess process = new ServiceProcess(
                        info,
                        group,
                        config,
                        directory,
                        java,
                        launcher,
                        line -> consoleSink.accept(new ConsoleLine(info.uniqueId(), info.name(), line)),
                        (state, exitCode) -> {
                            stateSink.accept(info.uniqueId(), new StateChange(state, exitCode));
                            if (state.isTerminal()) {
                                processes.remove(info.uniqueId());
                            }
                        },
                        (exitCode, lastLines) -> crashSink.accept(
                                new CrashReport(info.uniqueId(), info.name(), exitCode, lastLines)));

                if (!software.runsCloudPlugin()) {
                    process.onLogReady(() -> logReadySink.accept(process));
                }

                processes.put(info.uniqueId(), process);
                process.nodeEndpoints(nodeEndpoints.get());
                process.start(serverJar, proxy ? proxyPluginJar : serverPluginJar, mods,
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
        if (launcher.containerised()) {
            // The container image brings its own JVM; this machine's is irrelevant.
            return "java";
        }
        if (!group.javaExecutable().isBlank()) {
            return group.javaExecutable();
        }
        if (!config.javaExecutable().isBlank()) {
            return config.javaExecutable();
        }
        JavaRuntime runtime = javaRuntimes.require(config.serviceJavaVersion());
        return runtime.executable().toString();
    }

    /** The concrete Minecraft version a group resolves to, which Fabric's mods must match. */
    private String resolvedVersion(ServiceGroup group, ServerSoftware software) throws IOException {
        return versionResolver.apply(software, group.version());
    }

    /** Resolves "latest" per software; set by the wrapper, which owns the catalogs. */
    private volatile VersionResolver versionResolver = (software, version) -> version;

    @FunctionalInterface
    public interface VersionResolver {
        String apply(ServerSoftware software, String version) throws IOException;
    }

    public void versionResolver(VersionResolver resolver) {
        this.versionResolver = resolver;
    }

    public java.util.Optional<ServiceProcess> process(UUID serviceId) {
        return java.util.Optional.ofNullable(processes.get(serviceId));
    }

    /** A lifecycle transition observed by the wrapper. */
    public record StateChange(ServiceState state, int exitCode) {
    }
}
