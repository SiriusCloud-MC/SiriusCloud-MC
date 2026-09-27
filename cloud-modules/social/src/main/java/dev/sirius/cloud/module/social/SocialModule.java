package dev.sirius.cloud.module.social;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.module.CloudModule;
import dev.sirius.cloud.api.module.ModuleContext;
import dev.sirius.cloud.api.network.CommandSender;
import dev.sirius.cloud.module.common.ModuleConfigs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Parties, friends and private messages, across every server and proxy.
 *
 * <p>All of it lives here on the node, which is the only place that sees every
 * player at once. That is what lets a party follow its leader from a lobby on
 * one machine to a game on another, and a {@code /msg} reach somebody behind a
 * different proxy: none of the servers involved has to know the other exists.
 */
public final class SocialModule implements CloudModule {

    private static final CloudLogger LOGGER = CloudLogger.of("Social");

    private CloudDriver driver;
    private final List<String> registered = new ArrayList<>();
    private Parties parties;

    @Override
    public void onEnable(ModuleContext context) {
        this.driver = context.driver();
        Path configFile = context.dataDirectory().resolve("config.json");
        SocialConfig config;
        try {
            config = ModuleConfigs.load(configFile, SocialConfig.class, SocialConfig::new);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + configFile + ": " + exception.getMessage());
        }

        List<String> enabled = new ArrayList<>();
        if (config.parties()) {
            parties = new Parties(driver, config);
            parties.register(this::register);
            enabled.add("parties");
        }
        if (config.friends()) {
            new Friends(driver, config).register(this::register);
            enabled.add("friends");
        }
        if (config.messages()) {
            new Messages(driver).register(this::register);
            enabled.add("messages");
        }
        if (config.history()) {
            new History(driver).register(this::register);
            enabled.add("history");
        }
        LOGGER.info("Serving {}", enabled.isEmpty() ? "nothing (all switched off)" : String.join(", ", enabled));
    }

    @Override
    public void onDisable() {
        registered.forEach(driver.network()::unregisterCommand);
        registered.clear();
        if (parties != null) {
            parties.clear();
        }
    }

    private void register(dev.sirius.cloud.api.network.NetworkCommand command) {
        driver.network().registerCommand(command);
        registered.add(command.name());
    }

    /** The sender as a player, telling a console sender why not. */
    static Optional<UUID> player(CommandSender sender) {
        if (sender.uniqueId().isEmpty()) {
            sender.sendMessage("Only players can use this.");
        }
        return sender.uniqueId();
    }
}
