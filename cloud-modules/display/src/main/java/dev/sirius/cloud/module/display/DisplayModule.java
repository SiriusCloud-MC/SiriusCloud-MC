package dev.sirius.cloud.module.display;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.module.CloudModule;
import dev.sirius.cloud.api.module.ModuleContext;
import dev.sirius.cloud.api.network.CommandSender;
import dev.sirius.cloud.api.network.LoginAttempt;
import dev.sirius.cloud.api.network.LoginFilter;
import dev.sirius.cloud.api.network.ProxyDisplay;
import dev.sirius.cloud.api.network.Text;
import dev.sirius.cloud.module.common.Commands;
import dev.sirius.cloud.module.common.ModuleConfigs;
import dev.sirius.cloud.module.common.Players;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What players see before they join, and whether they may.
 *
 * <p>Owns the server list entry and the tab list on every proxy, and a
 * network-wide maintenance mode: a different server list entry, a red
 * "Maintenance" where the version would be, and a login filter turning away
 * everyone not whitelisted or holding {@code siriuscloud.maintenance.bypass}.
 *
 * <p>Turning maintenance on does not kick anyone. Staff are usually the ones
 * online when it is switched on, and the node cannot see who holds the bypass
 * permission - only proxies can - so kicking "everyone not whitelisted" would
 * throw out exactly the people doing the maintenance.
 */
public final class DisplayModule implements CloudModule {

    private static final CloudLogger LOGGER = CloudLogger.of("Display");

    static final String MANAGE = "siriuscloud.maintenance";
    static final String BYPASS = "siriuscloud.maintenance.bypass";

    private CloudDriver driver;
    private Path configFile;
    private DisplayConfig config;

    private final LoginFilter maintenanceGate = new LoginFilter() {
        @Override
        public Optional<String> check(LoginAttempt attempt) {
            DisplayConfig.Maintenance maintenance = config.maintenance();
            if (!maintenance.enabled()
                    || attempt.hasPermission(BYPASS)
                    || maintenance.whitelist().contains(attempt.name().toLowerCase(Locale.ROOT))) {
                return Optional.empty();
            }
            return Optional.of(maintenance.kickMessage());
        }

        @Override
        public List<String> permissions() {
            return List.of(BYPASS);
        }
    };

    @Override
    public void onEnable(ModuleContext context) {
        this.driver = context.driver();
        this.configFile = context.dataDirectory().resolve("config.json");
        try {
            this.config = ModuleConfigs.load(configFile, DisplayConfig.class, DisplayConfig::new);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + configFile + ": " + exception.getMessage());
        }

        driver.network().registerLoginFilter(maintenanceGate);
        driver.network().registerCommand(Commands.named("maintenance")
                .aliases("netmaintenance")
                .permission(MANAGE)
                .description("Network-wide maintenance mode")
                .executes(this::maintenance)
                .suggests(this::suggest)
                .build());

        apply();
        LOGGER.info("Serving the MOTD and tab list{}",
                config.maintenance().enabled() ? " - maintenance is ON" : "");
    }

    @Override
    public void onDisable() {
        driver.network().unregisterCommand("maintenance");
        driver.network().unregisterLoginFilter(maintenanceGate);
        driver.network().display(null);
    }

    /** Pushes whichever display is current to every proxy. */
    private void apply() {
        DisplayConfig.Maintenance maintenance = config.maintenance();
        driver.network().display(maintenance.enabled()
                ? new ProxyDisplay(maintenance.motd(), maintenance.versionText(), true,
                        config.tablistHeader(), config.tablistFooter())
                : new ProxyDisplay(config.motd(), "", false, config.tablistHeader(), config.tablistFooter()));
    }

    private void maintenance(CommandSender sender, String[] args) {
        DisplayConfig.Maintenance maintenance = config.maintenance();
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        switch (action) {
            case "on", "off" -> {
                boolean on = action.equals("on");
                if (maintenance.enabled() == on) {
                    sender.sendMessage("<yellow>Maintenance is already " + action + ".");
                    return;
                }
                maintenance.enabled(on);
                save(sender);
                apply();
                LOGGER.info("{} turned maintenance {}", sender.name(), action);
                driver.players().broadcastRich(on
                        ? "<red>Maintenance is on. New players cannot join; nobody online was removed."
                        : "<green>Maintenance is off. The network is open again.", MANAGE);
                if (sender.isConsole()) {
                    sender.sendMessage("Maintenance is " + action + ".");
                }
            }
            case "add", "remove" -> {
                if (args.length < 2) {
                    sender.sendMessage("<red>Usage: /maintenance " + action + " <player>");
                    return;
                }
                String name = args[1].toLowerCase(Locale.ROOT);
                boolean changed = action.equals("add")
                        ? !maintenance.whitelist().contains(name) && maintenance.whitelist().add(name)
                        : maintenance.whitelist().remove(name);
                if (!changed) {
                    sender.sendMessage("<yellow>" + Text.escape(args[1]) + " is "
                            + (action.equals("add") ? "already" : "not") + " on the maintenance whitelist.");
                    return;
                }
                save(sender);
                sender.sendMessage("<green>" + Text.escape(args[1]) + (action.equals("add")
                        ? " may join during maintenance." : " is off the maintenance whitelist."));
            }
            case "list" -> sender.sendMessage(maintenance.whitelist().isEmpty()
                    ? "<gray>Nobody is on the maintenance whitelist."
                    : "<gray>Maintenance whitelist: <white>" + Text.escape(String.join(", ", maintenance.whitelist())));
            case "status" -> sender.sendMessage("<gray>Maintenance is "
                    + (maintenance.enabled() ? "<red>ON" : "<green>off")
                    + "<gray>. Whitelisted: " + maintenance.whitelist().size()
                    + ". Players with <white>" + BYPASS + "<gray> may always join.");
            default -> sender.sendMessage("<red>Usage: /maintenance <on|off|add|remove|list>");
        }
    }

    private List<String> suggest(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return Commands.matching(List.of("on", "off", "add", "remove", "list", "status"), args);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            return Commands.matching(Players.onlineNames(driver), args);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            return Commands.matching(config.maintenance().whitelist(), args);
        }
        return List.of();
    }

    private void save(CommandSender sender) {
        try {
            ModuleConfigs.save(configFile, config);
        } catch (IOException exception) {
            // The change still applies; it just will not survive a restart.
            sender.sendMessage("<red>Applied, but could not be saved: " + Text.escape(exception.getMessage()));
        }
    }
}
