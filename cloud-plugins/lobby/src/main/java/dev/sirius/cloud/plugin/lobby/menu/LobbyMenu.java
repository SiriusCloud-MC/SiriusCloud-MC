package dev.sirius.cloud.plugin.lobby.menu;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.plugin.lobby.LobbyPlugin;
import dev.sirius.cloud.plugin.lobby.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Every server of this lobby's group: how full each is, and a click to go there. */
public final class LobbyMenu extends Menu {

    private final LobbyPlugin plugin;

    public LobbyMenu(LobbyPlugin plugin) {
        super(rowsFor(plugin), Text.render(plugin.getConfig().getString("lobbies.title", "Lobbies")));
        this.plugin = plugin;
    }

    private static int rowsFor(LobbyPlugin plugin) {
        int lobbies = plugin.network().servicesOf(plugin.lobbyGroup()).size();
        return Math.max(1, Math.min(6, (lobbies + 8) / 9));
    }

    @Override
    public boolean live() {
        return true;
    }

    @Override
    public void render() {
        clear();
        String self = plugin.lobbyName();
        List<ServiceInfo> lobbies = plugin.network().servicesOf(plugin.lobbyGroup());
        int slot = 0;
        for (ServiceInfo lobby : lobbies) {
            if (slot >= getInventory().getSize()) {
                break;
            }
            boolean here = lobby.name().equalsIgnoreCase(self);
            boolean running = lobby.state() == ServiceState.RUNNING;
            boolean full = lobby.playerCount() >= lobby.maxPlayers();
            Material icon = here ? Material.LIME_DYE : !running ? Material.GRAY_DYE : full ? Material.RED_DYE
                    : Material.LIGHT_BLUE_DYE;

            List<Component> lore = new ArrayList<>();
            lore.add(Text.item("<gray>Players: <white>{players}<gray>/{max}",
                    Map.of("players", lobby.playerCount(), "max", lobby.maxPlayers())));
            lore.add(Component.empty());
            lore.add(Text.item(here ? "<green>You are here" : !running ? "<yellow>Starting..."
                    : full ? "<red>Full" : "<aqua>▶ Click to join"));

            var item = item(icon, Text.item((here ? "<green>" : "<white>") + "{name}", Map.of("name", lobby.name())),
                    lore, here);
            item.setAmount(Math.max(1, Math.min(64, lobby.serviceId().ordinal())));
            set(slot++, item, player -> join(player, lobby, here, running));
        }
    }

    private void join(Player player, ServiceInfo lobby, boolean here, boolean running) {
        if (here) {
            plugin.message(player, "already-here", Map.of());
            return;
        }
        if (!running) {
            return;
        }
        player.closeInventory();
        plugin.message(player, "connecting", Map.of("server", lobby.name()));
        CloudDriver.instance().players().connect(player.getUniqueId(), lobby.name()).exceptionally(error -> {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            plugin.message(player, "failed", Map.of("reason", String.valueOf(cause.getMessage())));
            return null;
        });
    }
}
