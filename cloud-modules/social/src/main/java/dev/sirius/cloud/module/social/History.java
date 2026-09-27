package dev.sirius.cloud.module.social;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.network.NetworkCommand;
import dev.sirius.cloud.api.network.Text;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.api.player.PlayerProfile;
import dev.sirius.cloud.module.common.Commands;
import dev.sirius.cloud.module.common.Durations;
import dev.sirius.cloud.module.common.Players;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** {@code /seen} and {@code /playtime}, from the profiles the node keeps. */
final class History {

    private final CloudDriver driver;

    History(CloudDriver driver) {
        this.driver = driver;
    }

    void register(Consumer<NetworkCommand> register) {
        register.accept(Commands.named("seen")
                .description("When a player was last online")
                .executes((sender, args) -> {
                    if (args.length < 1) {
                        sender.sendMessage("<red>Usage: /seen <player>");
                        return;
                    }
                    Optional<PlayerProfile> profile = driver.players().profile(args[0]).join();
                    if (profile.isEmpty()) {
                        sender.sendMessage("<red>Nobody called " + Text.escape(args[0]) + " has played here.");
                        return;
                    }
                    Optional<CloudPlayer> online = driver.players().cachedPlayer(profile.get().uniqueId());
                    String names = profile.get().nameHistory().size() > 1
                            ? "\n<gray>Previously: <white>" + Text.escape(String.join(", ",
                                    profile.get().nameHistory().subList(0, profile.get().nameHistory().size() - 1)))
                            : "";
                    sender.sendMessage(online.isPresent()
                            ? "<green>" + profile.get().name() + " is online" + online.get().serverName()
                                    .map(server -> " on " + server).orElse("") + "." + names
                            : "<gray>" + profile.get().name() + " was last seen " + Durations.ago(profile.get().lastSeen())
                                    + (profile.get().lastServer().isBlank() ? "" : " on " + profile.get().lastServer())
                                    + "." + names);
                })
                .suggests((sender, args) -> Commands.matching(Players.onlineNames(driver), args))
                .build());

        register.accept(Commands.named("playtime")
                .aliases("pt")
                .description("How long a player has played on the network")
                .executes((sender, args) -> {
                    String name = args.length > 0 ? args[0] : sender.name();
                    Optional<PlayerProfile> profile = driver.players().profile(name).join();
                    if (profile.isEmpty()) {
                        sender.sendMessage("<red>Nobody called " + Text.escape(name) + " has played here.");
                        return;
                    }
                    sender.sendMessage("<gray>" + profile.get().name() + " has played <white>"
                            + Durations.describe(Duration.ofMillis(profile.get().playtimeMillis()))
                            + "<gray>, first joined " + Durations.ago(profile.get().firstJoin()) + ".");
                })
                .suggests((sender, args) -> args.length <= 1
                        ? Commands.matching(Players.onlineNames(driver), args) : List.of())
                .build());
    }
}
