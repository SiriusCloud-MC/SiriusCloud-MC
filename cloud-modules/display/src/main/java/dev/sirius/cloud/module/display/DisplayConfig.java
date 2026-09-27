package dev.sirius.cloud.module.display;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code node/modules/display/config.json}.
 *
 * <p>All MiniMessage. Placeholders: {@code {online}}, {@code {max}},
 * {@code {proxy}}, {@code {servers}}, and in the tab list {@code {player}},
 * {@code {server}}, {@code {ping}}.
 */
public final class DisplayConfig {

    private List<String> motd = new ArrayList<>(List.of(
            "<gradient:#5e9cff:#b06cff><bold>SiriusCloud</bold></gradient>",
            "<gray>{online} players online"));

    private String tablistHeader = "\n<gradient:#5e9cff:#b06cff><bold>SiriusCloud</bold></gradient>\n";
    private String tablistFooter = "\n<gray>{server} <dark_gray>| <gray>{online}/{max} online "
            + "<dark_gray>| <gray>{ping}ms\n";

    private Maintenance maintenance = new Maintenance();

    public static final class Maintenance {

        private boolean enabled = false;

        /** Names, lower-cased, who may join while it is on. */
        private List<String> whitelist = new ArrayList<>();

        private List<String> motd = new ArrayList<>(List.of(
                "<red><bold>Maintenance</bold>",
                "<gray>We will be back shortly."));

        /** Shown in red where the version normally is, so a client sees at a glance it cannot join. */
        private String versionText = "Maintenance";

        private String kickMessage = "<red><bold>Maintenance</bold>\n\n<gray>The network is being worked on.\n"
                + "<gray>Please try again shortly.";

        public boolean enabled() {
            return enabled;
        }

        public void enabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> whitelist() {
            if (whitelist == null) {
                whitelist = new ArrayList<>();
            }
            return whitelist;
        }

        public List<String> motd() {
            return motd == null ? List.of() : motd;
        }

        public String versionText() {
            return versionText == null ? "Maintenance" : versionText;
        }

        public String kickMessage() {
            return kickMessage == null ? "<red>Maintenance" : kickMessage;
        }
    }

    public List<String> motd() {
        return motd == null ? List.of() : motd;
    }

    public String tablistHeader() {
        return tablistHeader == null ? "" : tablistHeader;
    }

    public String tablistFooter() {
        return tablistFooter == null ? "" : tablistFooter;
    }

    public Maintenance maintenance() {
        if (maintenance == null) {
            maintenance = new Maintenance();
        }
        return maintenance;
    }
}
