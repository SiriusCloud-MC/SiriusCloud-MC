package dev.sirius.cloud.module.discord;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.event.events.BackupCompletedEvent;
import dev.sirius.cloud.api.event.events.ServiceCrashedEvent;
import dev.sirius.cloud.api.event.events.ServiceMetricsEvent;
import dev.sirius.cloud.api.event.events.ServiceRemovedEvent;
import dev.sirius.cloud.api.event.events.ServiceStateChangedEvent;
import dev.sirius.cloud.api.event.events.WrapperDisconnectedEvent;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.module.CloudModule;
import dev.sirius.cloud.api.module.ModuleContext;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.module.common.ModuleConfigs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends the things someone should look at to a Discord channel: crashes with
 * their last log lines, wrappers dropping off, failed backups, lagging servers.
 */
public final class DiscordModule implements CloudModule {

    private static final CloudLogger LOGGER = CloudLogger.of("Discord");

    private DiscordConfig config;
    private Webhook webhook;

    /** Service name -> when it was last reported as lagging. */
    private final Map<String, Long> lagReported = new ConcurrentHashMap<>();

    @Override
    public void onEnable(ModuleContext context) {
        CloudDriver driver = context.driver();
        Path configFile = context.dataDirectory().resolve("config.json");
        try {
            this.config = ModuleConfigs.load(configFile, DiscordConfig.class, DiscordConfig::new);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + configFile + ": " + exception.getMessage());
        }
        if (config.webhookUrl().isEmpty()) {
            LOGGER.info("No webhook URL in {} - Discord alerts are off.", configFile);
            return;
        }
        if (!config.webhookUrl().startsWith("https://")) {
            LOGGER.warn("The webhook URL in {} is not an https:// URL - Discord alerts are off.", configFile);
            return;
        }
        webhook = new Webhook(config.webhookUrl(), config.username());

        if (config.crashes()) {
            driver.events().subscribe(ServiceCrashedEvent.class, this::onCrash);
        }
        if (config.serviceStarts() || config.serviceStops()) {
            driver.events().subscribe(ServiceStateChangedEvent.class, this::onState);
        }
        if (config.wrapperDisconnects()) {
            driver.events().subscribe(WrapperDisconnectedEvent.class, event -> webhook.post(
                    "Wrapper disconnected: " + event.wrapper().name(),
                    "Its services are gone until it reconnects; groups will refill elsewhere if there is room.",
                    Webhook.RED));
        }
        if (config.backupFailures()) {
            driver.events().subscribe(BackupCompletedEvent.class, event -> {
                if (!event.success()) {
                    webhook.post("Backup failed: " + event.serviceName(),
                            "On " + event.wrapperName() + ": " + event.message(), Webhook.ORANGE);
                }
            });
        }
        if (config.lowTps() > 0) {
            driver.events().subscribe(ServiceMetricsEvent.class, event -> onMetrics(event.service()));
            driver.events().subscribe(ServiceRemovedEvent.class, event -> lagReported.remove(event.service().name()));
        }
        LOGGER.info("Sending alerts to Discord");
    }

    @Override
    public void onDisable() {
        if (webhook != null) {
            webhook.close();
        }
    }

    private void onCrash(ServiceCrashedEvent event) {
        StringBuilder description = new StringBuilder()
                .append("Group **").append(event.groupName()).append("**, exit code ").append(event.exitCode());
        List<String> lines = event.lastLines();
        if (lines != null && !lines.isEmpty()) {
            String log = String.join("\n", lines.subList(Math.max(0, lines.size() - 25), lines.size()))
                    .replace("```", "'''");
            // Keep the end of the log, which is where the reason is.
            if (log.length() > 3500) {
                log = "…" + log.substring(log.length() - 3500);
            }
            description.append("\n```\n").append(log).append("\n```");
        }
        webhook.post("Service crashed: " + event.serviceName(), description.toString(), Webhook.RED);
    }

    private void onState(ServiceStateChangedEvent event) {
        ServiceInfo service = event.service();
        if (event.current() == ServiceState.RUNNING && config.serviceStarts()) {
            webhook.post("Service started: " + service.name(),
                    "On " + service.wrapperName() + ", port " + service.port() + ".", Webhook.GREEN);
        } else if (event.current() == ServiceState.STOPPED && config.serviceStops()) {
            webhook.post("Service stopped: " + service.name(),
                    "After " + service.uptimeMillis() / 60_000 + " minute(s).", Webhook.GREY);
        }
    }

    private void onMetrics(ServiceInfo service) {
        double tps = service.tps();
        if (tps < 0 || tps >= config.lowTps() || service.heapMaxMb() <= 0) {
            return;
        }
        // Worlds are still loading for the first minute; the average says nothing yet.
        if (service.uptimeMillis() < 120_000) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lagReported.get(service.name());
        if (last != null && now - last < config.lowTpsCooldownMinutes() * 60_000L) {
            return;
        }
        lagReported.put(service.name(), now);
        webhook.post("Low TPS on " + service.name(),
                String.format(Locale.ROOT, "%.1f TPS, %.1f ms per tick, %d players, heap %d/%d MB.",
                        tps, service.mspt(), service.playerCount(), service.heapUsedMb(), service.heapMaxMb()),
                Webhook.ORANGE);
    }
}
