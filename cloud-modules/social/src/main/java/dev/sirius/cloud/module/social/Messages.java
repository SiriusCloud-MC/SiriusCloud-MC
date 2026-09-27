package dev.sirius.cloud.module.social;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.event.events.PlayerDisconnectEvent;
import dev.sirius.cloud.api.network.CommandSender;
import dev.sirius.cloud.api.network.NetworkCommand;
import dev.sirius.cloud.api.network.Text;
import dev.sirius.cloud.api.player.CloudPlayer;
import dev.sirius.cloud.module.common.Commands;
import dev.sirius.cloud.module.common.Players;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Private messages that reach anyone on the network, whichever server or proxy
 * they are on.
 *
 * <p>A muted player cannot send them. The mute is enforced by the servers for
 * public chat, and a private message is relayed here instead - so without this
 * check a mute would only ever have covered half of chat.
 */
final class Messages {

    private final CloudDriver driver;

    /** Who each player last talked with, for /r. */
    private final Map<UUID, UUID> lastPartner = new ConcurrentHashMap<>();

    Messages(CloudDriver driver) {
        this.driver = driver;
    }

    void register(Consumer<NetworkCommand> register) {
        register.accept(Commands.named("msg")
                .aliases("tell", "whisper", "w", "m")
                .description("Message a player anywhere on the network")
                .executes((sender, args) -> {
                    if (args.length < 2) {
                        sender.sendMessage("<red>Usage: /msg <player> <message>");
                        return;
                    }
                    SocialModule.player(sender).ifPresent(self ->
                            send(self, sender.name(), driver.players().cachedPlayer(args[0]), args[0],
                                    Commands.rest(args, 1)));
                })
                .suggests((sender, args) -> args.length <= 1
                        ? Commands.matching(Players.onlineNames(driver), args) : List.of())
                .build());

        register.accept(Commands.named("reply")
                .aliases("r")
                .description("Answer the last private message")
                .executes((sender, args) -> SocialModule.player(sender).ifPresent(self -> {
                    UUID partner = lastPartner.get(self);
                    if (partner == null) {
                        sender.sendMessage("<red>Nobody to reply to.");
                        return;
                    }
                    send(self, sender.name(), driver.players().cachedPlayer(partner), "That player",
                            Commands.rest(args, 0));
                }))
                .build());

        driver.events().subscribe(PlayerDisconnectEvent.class, event -> lastPartner.remove(event.player().uniqueId()));
    }

    private void send(UUID self, String selfName, Optional<CloudPlayer> target, String typedName, String message) {
        if (message.isBlank()) {
            tell(self, "<red>Say something.");
            return;
        }
        if (target.isEmpty()) {
            tell(self, "<red>" + Text.escape(typedName) + " is not online.");
            return;
        }
        if (target.get().uniqueId().equals(self)) {
            tell(self, "<red>Talking to yourself?");
            return;
        }
        if (driver.network().isChatRestricted(self)) {
            tell(self, "<red>You are muted.");
            return;
        }

        String body = Text.escape(message);
        tell(self, "<dark_gray>[<gray>You <dark_gray>→ <gray>" + target.get().name() + "<dark_gray>] <white>" + body);
        tell(target.get().uniqueId(), "<dark_gray>[<gray>" + selfName + " <dark_gray>→ <gray>You<dark_gray>] <white>"
                + body);
        lastPartner.put(self, target.get().uniqueId());
        lastPartner.put(target.get().uniqueId(), self);
    }

    private void tell(UUID player, String message) {
        driver.players().sendRichMessage(player, message).exceptionally(error -> null);
    }
}
