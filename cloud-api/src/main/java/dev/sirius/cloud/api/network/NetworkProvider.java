package dev.sirius.cloud.api.network;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What players meet at the edge of the cloud: commands, the login gate, the
 * server list, the tab list, and who may chat.
 *
 * <p>This is how a feature like parties or bans is written once, as a node
 * module, and appears on every proxy without a proxy plugin of its own. The
 * proxies are generic: they expose whatever commands are registered here,
 * forward each use to the node, and render what the node tells them to.
 *
 * <p>Commands, login filters and the display are registered on the node only -
 * that is where the module holding their state lives. On a remote driver
 * those methods throw. Chat restrictions work from anywhere.
 */
public interface NetworkProvider {

    /** Exposes a command on every proxy. Replaces one of the same name. */
    void registerCommand(NetworkCommand command);

    void unregisterCommand(String name);

    /** Adds a check every login passes through. The first to refuse wins. */
    void registerLoginFilter(LoginFilter filter);

    void unregisterLoginFilter(LoginFilter filter);

    /** Sets what every proxy shows in the server list and the tab list. Null restores the defaults. */
    void display(ProxyDisplay display);

    Optional<ProxyDisplay> display();

    /**
     * Stops a player chatting on every server until a point in time.
     *
     * <p>Enforced by the servers rather than the proxy: signed chat cannot be
     * cancelled on a proxy without disconnecting the player, so the proxy is
     * the wrong place to stop a message.
     *
     * @param untilMillis epoch millis, or 0 for indefinitely
     */
    CompletableFuture<Void> restrictChat(UUID player, long untilMillis, String reason);

    CompletableFuture<Void> liftChatRestriction(UUID player);

    /**
     * Whether a player may not chat right now.
     *
     * <p>Node-side only, like commands. For a module relaying chat of its own -
     * private messages, party chat - which must refuse a muted player exactly
     * as the servers do, or a mute would only cover public chat.
     */
    boolean isChatRestricted(UUID player);
}
