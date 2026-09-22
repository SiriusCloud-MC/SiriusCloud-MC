package dev.sirius.cloud.wrapper;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.platform.Platform;
import dev.sirius.cloud.driver.RemoteCloudDriver;
import dev.sirius.cloud.driver.config.JsonConfig;
import dev.sirius.cloud.driver.paper.PaperVersionCatalog;
import dev.sirius.cloud.protocol.connection.NetworkClient;
import dev.sirius.cloud.protocol.packet.PacketRegistry;
import dev.sirius.cloud.protocol.packet.impl.ConsoleHistoryPacket;
import dev.sirius.cloud.protocol.packet.impl.ConsoleLinePacket;
import dev.sirius.cloud.protocol.packet.impl.HeartbeatPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStateUpdatePacket;
import dev.sirius.cloud.wrapper.config.WrapperConfig;
import dev.sirius.cloud.wrapper.jar.JarResolver;
import dev.sirius.cloud.wrapper.network.WrapperPacketHandler;
import dev.sirius.cloud.wrapper.process.ServiceProcessManager;
import dev.sirius.cloud.wrapper.template.TemplateManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final ServiceProcessManager processes;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sirius-wrapper-scheduler");
        thread.setDaemon(true);
        return thread;
    });

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
                line -> client.send(new ConsoleLinePacket(line.serviceId(), line.serviceName(), line.line())),
                backlog -> client.send(new ConsoleHistoryPacket(
                        backlog.serviceId(), backlog.serviceName(), backlog.lines())),
                (serviceId, change) -> client.send(
                        new ServiceStateUpdatePacket(serviceId, change.state(), change.exitCode())));
    }

    public void start() throws Exception {
        LOGGER.info("SiriusCloud wrapper '{}' on {}", config.name(), Platform.describe());

        if (config.secret().isBlank()) {
            LOGGER.error("No secret configured.");
            LOGGER.error("Copy 'secret' from node/config.json into wrapper/config.json and restart.");
            return;
        }

        processes.cleanStaleDirectories();

        WrapperPacketHandler handler = new WrapperPacketHandler(config, processes, connected -> {
        });

        client.connect(config.nodeHost(), config.nodePort(), handler);

        CloudDriver.bind(new RemoteCloudDriver("WRAPPER", client));

        scheduler.scheduleWithFixedDelay(this::heartbeat,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "sirius-wrapper-shutdown"));

        LOGGER.info("Connecting to node at {}:{}", config.nodeHost(), config.nodePort());

        // The wrapper has no console of its own; it idles until it is stopped.
        shutdownLatch.await();
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
