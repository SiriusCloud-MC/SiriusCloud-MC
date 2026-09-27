package dev.sirius.cloud.api.network;

import java.util.List;

/**
 * What proxies show: the server list entry and the tab list.
 *
 * <p>Every string is MiniMessage and may use placeholders, filled in by the
 * proxy at the moment it renders:
 * <ul>
 *   <li>{@code {online}}, {@code {max}} - players in the whole cloud, and its capacity
 *   <li>{@code {proxy}} - this proxy's name
 *   <li>{@code {servers}} - how many servers are reachable
 *   <li>tab list only: {@code {player}}, {@code {server}}, {@code {ping}}
 * </ul>
 *
 * @param motd          up to two lines; empty keeps the proxy's own
 * @param versionText   shown in place of the version, e.g. "Maintenance"; empty keeps it
 * @param versionBlocked whether the version reads as incompatible (red), which is how
 *                      a client is told at a glance that it cannot join
 * @param tablistHeader empty for none
 * @param tablistFooter empty for none
 */
public record ProxyDisplay(List<String> motd,
                           String versionText,
                           boolean versionBlocked,
                           String tablistHeader,
                           String tablistFooter) {

    public ProxyDisplay {
        motd = motd == null ? List.of() : List.copyOf(motd);
        versionText = versionText == null ? "" : versionText;
        tablistHeader = tablistHeader == null ? "" : tablistHeader;
        tablistFooter = tablistFooter == null ? "" : tablistFooter;
    }
}
