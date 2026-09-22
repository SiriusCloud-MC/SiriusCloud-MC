package dev.sirius.cloud.wrapper;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.platform.Platform;
import dev.sirius.cloud.driver.RemoteCloudDriver;
import dev.sirius.cloud.driver.config.DirectoryLock;
import dev.sirius.cloud.driver.config.JsonConfig;
import dev.sirius.cloud.driver.paper.PaperVersionCatalog;
import dev.sirius.cloud.protocol.connection.NetworkClient;
import dev.sirius.cloud.protocol.packet.PacketRegistry;
import dev.sirius.cloud.protocol.packet.impl.ConsoleHistoryPacket;
import dev.sirius.cloud.protocol.packet.impl.ConsoleLinePacket;
import dev.sirius.cloud.protocol.packet.impl.HeartbeatPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceCrashReportPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStateUpdatePacket;
import dev.sirius.cloud.wrapper.config.WrapperConfig;
import dev.sirius.cloud.wrapper.jar.JarResolver;
import dev.sirius.cloud.wrapper.java.JavaRuntime;
import dev.sirius.cloud.wrapper.java.JavaRuntimeResolver;
import dev.sirius.cloud.wrapper.network.WrapperPacketHandler;
import dev.sirius.cloud.wrapper.process.ServiceProcessManager;
import dev.sirius.cloud.wrapper.setup.Prompter;
import dev.sirius.cloud.wrapper.setup.WrapperSetup;
import dev.sirius.cloud.wrapper.template.TemplateManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The machine agent.
 *
 * <p>It holds no policy of its own: the node decides what should run and the
 * wrapper starts it, pipes its console back, and reports when it dies. Keeping
 * it that dumb is what lets a second machine join by starting a second wrapper.
 */
public final class CloudWrapper {

    private static final CloudLogger LOGGER = CloudLogger.of("CloudWrapper");

    private static final int HEARTBEAT_SECONDS = 10;

    private final Path workingDirectory;
    private final WrapperConfig config;

    private final NetworkClient client = new NetworkClient(PacketRegistry.standard());
    private final PaperVersionCatalog versions = new PaperVersionCatalog();
    private final JavaRuntimeResolver javaRuntimes = new JavaRuntimeResolver();
    private final ServiceProcessManager processes;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sirius-wrapper-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    private DirectoryLock directoryLock;

    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public CloudWrapper(Path workingDirectory) throws IOException {
        this.workingDirectory = workingDirectory;

        Files.createDirectories(workingDirectory.resolve("local"));

        this.config = JsonConfig.loadOrCreate(
                workingDirectory.resolve("config.json"), WrapperConfig.class, WrapperConfig::new);
        CloudLogger.debugEnabled(config.debug());

        Path jarDirectory = workingDirectory.resolve("local").resolve("jars");
        Path templatesDirectory = workingDirectory.resolve("local").resolve("templates");
        Files.createDirectories(jarDirectory);
        Files.createDirectories(templatesDirectory);

        this.processes = new ServiceProcessManager(
                config,
                workingDirectory,
                JarResolver.standard(jarDirectory, versions),
                new TemplateManager(templatesDirectory),
                javaRuntimes,
                line -> client.send(new ConsoleLinePacket(line.serviceId(), line.serviceName(), line.line())),
                backlog -> client.send(new ConsoleHistoryPacket(
                        backlog.serviceId(), backlog.serviceName(), backlog.lines())),
                (serviceId, change) -> client.send(
                        new ServiceStateUpdatePacket(serviceId, change.state(), change.exitCode())),
                crash -> client.send(new ServiceCrashReportPacket(
                        crash.serviceId(), crash.serviceName(), crash.exitCode(), crash.lastLines())));
    }

    public void start() throws Exception {
        LOGGER.info("SiriusCloud wrapper '{}' on {}", config.name(), Platform.describe());

        // Before anything connects or logs from another thread, so the
        // questions are not interleaved with output.
        if (!config.setupCompleted()) {
            new WrapperSetup(new Prompter(), config,
                    workingDirectory.resolve("config.json"), javaRuntimes).run();
            config.setupCompleted(true);
            JsonConfig.save(workingDirectory.resolve("config.json"), config);
        }

        if (config.secret().isBlank()) {
            LOGGER.error("No secret configured.");
            LOGGER.error("Copy the 'Wrapper secret' line from the node's console into wrapper/config.json.");
            return;
        }

        // Before cleanStaleDirectories(), which is precisely the operation that
        // would destroy another wrapper's running services.
        directoryLock = DirectoryLock.acquire(workingDirectory.resolve(".lock"), "wrapper");

        processes.cleanStaleDirectories();
        reportServiceRuntime();

        WrapperPacketHandler handler = new WrapperPacketHandler(
                config, processes, connected -> {
                }, this::onAuthenticationRejected);

        client.connect(config.nodeHost(), config.nodePort(), handler);

        CloudDriver.bind(new RemoteCloudDriver("WRAPPER", client));

        scheduler.scheduleWithFixedDelay(this::heartbeat,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "sirius-wrapper-shutdown"));

        LOGGER.info("Connecting to node at {}:{}", config.nodeHost(), config.nodePort());

        // The wrapper has no console of its own; it idles until it is stopped.
        shutdownLatch.await();
    }

    /**
     * Checks up front that this machine can actually run services.
     *
     * <p>Detection happens at startup rather than lazily so that a missing JDK
     * is a clear message on the first screen, not an unexplained crash loop the
     * first time the node schedules something.
     */
    private void reportServiceRuntime() {
        if (!config.javaExecutable().isBlank()) {
            LOGGER.info("Services will run on the pinned JVM: {}", config.javaExecutable());
            return;
        }

        int required = config.serviceJavaVersion();
        Optional<JavaRuntime> runtime = javaRuntimes.find(required);

        if (runtime.isPresent()) {
            LOGGER.info("Services will run on {}", runtime.get());
            return;
        }

        LOGGER.error("No Java {}+ installation found - services cannot start.", required);
        javaRuntimes.discover().forEach(found -> LOGGER.error("  detected: {}", found));
        try {
            javaRuntimes.require(required);
        } catch (java.io.IOException exception) {
            LOGGER.error("  {}", exception.getMessage());
        }
    }

    /**
     * The node refused our credentials.
     *
     * <p>Stop reconnecting either way, then: with nothing running, exit, since
     * an idle wrapper that can never register is only a confusing process to
     * find later. With services running, stay up and leave them alone — the
     * node's secret may have changed under a cloud full of players, and taking
     * every server down over a config mismatch would be the worse outcome.
     */
    private void onAuthenticationRejected() {
        client.stopReconnecting();

        int running = processes.runningCount();
        if (running == 0) {
            LOGGER.error("Cannot register with the node. Fix 'secret' in config.json and restart.");
            shutdown();
            return;
        }

        LOGGER.error("Cannot register with the node, but {} service(s) are still running.", running);
        LOGGER.error("They are left alone. Fix 'secret' in config.json and restart the wrapper.");
    }

    private void heartbeat() {
        client.send(new HeartbeatPacket(
                System.currentTimeMillis(),
                processes.committedMemory(),
                processes.runningCount()));
    }

    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        LOGGER.info("Shutting down...");
        scheduler.shutdownNow();

        // Services are stopped gracefully before the connection goes away, so
        // the node still sees the STOPPED updates rather than inferring crashes.
        processes.stopAll();

        client.close();

        if (directoryLock != null) {
            directoryLock.close();
        }

        shutdownLatch.countDown();
        LOGGER.info("Goodbye.");
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public WrapperConfig config() {
        return config;
    }
}
