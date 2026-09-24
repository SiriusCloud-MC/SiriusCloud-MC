package dev.sirius.cloud.module.notify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.event.events.ServiceStateChangedEvent;
import dev.sirius.cloud.api.event.events.WrapperConnectedEvent;
import dev.sirius.cloud.api.event.events.WrapperDisconnectedEvent;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.module.CloudModule;
import dev.sirius.cloud.api.module.ModuleContext;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tells staff in game what the cloud is doing.
 *
 * <p>This has to be a node module rather than part of the in-service plugin,
 * and the reason is worth stating: a plugin's {@code CloudDriver} carries an
 * event bus local to its own JVM, so it never sees
 * {@link ServiceStateChangedEvent} or anything else the node posts. Only the
 * node observes cloud lifecycle, so only the node can announce it.
 *
 * <p>It publishes on a channel rather than messaging players directly. The node
 * has no idea who holds a permission — that is a question only the server the
 * player is on can answer — so the filtering happens there, and this module
 * stays a thing that describes events rather than a thing that knows about
 * permission plugins.
 */
public final class NotifyModule implements CloudModule {

    private static final CloudLogger LOGGER = CloudLogger.of("Notify");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private NotifyConfig config;
    private CloudDriver driver;

    @Override
    public void onEnable(ModuleContext context) {
        this.driver = context.driver();

        Path configFile = context.dataDirectory().resolve("config.json");
        try {
            this.config = loadConfig(configFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + configFile + ": " + exception.getMessage());
        }

        driver.events().subscribe(ServiceStateChangedEvent.class, this::onServiceState);
        driver.events().subscribe(WrapperConnectedEvent.class, event -> {
            if (config.wrapperConnected()) {
                publish(Notification.GOOD,
                        "Machine " + event.wrapper().name() + " connected ("
                                + event.wrapper().maxMemory() + "MB available)");
            }
        });
        driver.events().subscribe(WrapperDisconnectedEvent.class, event -> {
            if (config.wrapperDisconnected()) {
                publish(Notification.BAD,
                        "Machine " + event.wrapper().name() + " disconnected. Its servers keep "
                                + "running but cannot be controlled until it returns.");
            }
        });

        LOGGER.info("Announcing cloud events to players with '{}'", config.permission());
    }

    @Override
    public void onDisable() {
        // Subscriptions are dropped by the loader, which unsubscribes this
        // module's class loader from the event bus. Nothing else to undo.
        LOGGER.info("No longer announcing cloud events");
    }

    private void onServiceState(ServiceStateChangedEvent event) {
        ServiceInfo service = event.service();
        ServiceState state = event.current();

        switch (state) {
            case STARTING -> {
                if (config.serviceStarting()) {
                    publish(Notification.INFO, service.name() + " is starting on " + service.wrapperName());
                }
            }
            case RUNNING -> {
                if (config.serviceRunning()) {
                    publish(Notification.GOOD, service.name() + " is ready ("
                            + service.host() + ":" + service.port() + ")");
                }
            }
            case STOPPING -> {
                if (config.serviceStopping()) {
                    publish(Notification.INFO, service.name() + " is shutting down");
                }
            }
            case STOPPED -> {
                if (config.serviceStopped()) {
                    publish(Notification.INFO, service.name() + " stopped");
                }
            }
            case CRASHED -> {
                if (config.serviceCrashed()) {
                    // No exit code here: the event carries the service, not the
                    // reason. The node console prints the crash output, which is
                    // where somebody diagnosing this should be looking anyway.
                    publish(Notification.BAD, service.name() + " crashed. Check the node console.");
                }
            }
            default -> {
            }
        }
    }

    private void publish(String kind, String message) {
        String payload = GSON.toJson(new Notification(kind, message, config.permission()));
        driver.messaging().publish(Notification.CHANNEL, payload).exceptionally(error -> {
            // A notification failing to go out must never disturb the cloud: it
            // is reported and dropped, not retried into a backlog.
            LOGGER.debug("Could not publish a notification: {}", error.getMessage());
            return null;
        });
    }

    /** Loads the config, writing defaults on first start and after an upgrade. */
    private static NotifyConfig loadConfig(Path path) throws IOException {
        NotifyConfig config = null;
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                config = GSON.fromJson(reader, NotifyConfig.class);
            }
        }
        if (config == null) {
            config = new NotifyConfig();
        }
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        }
        return config;
    }
}
