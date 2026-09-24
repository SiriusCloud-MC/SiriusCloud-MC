package dev.sirius.cloud.api.messaging;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Publish/subscribe across the whole cloud, with the node as the broker.
 *
 * <p>The cloud already knows every service and holds an authenticated
 * connection to each, so it can carry small messages between them without a
 * Redis to run or a plugin-message channel to fight the proxy over. This is the
 * one primitive both of those needs, exposed once:
 *
 * <ul>
 *   <li>a node module publishing "Lobby-2 crashed" to every server that has
 *       somebody with permission to hear it,
 *   <li>LuckPerms syncing a permission change to every backend the instant it
 *       is made, through its own messenger abstraction.
 * </ul>
 *
 * <p>Payloads are opaque strings and the cloud never interprets them. Delivery
 * is best-effort and unordered: a service that is not connected at the moment
 * of publication does not receive the message, and nothing is queued for it.
 * That is the right trade for permission syncs and chat notices, and the wrong
 * one for anything that must not be missed.
 *
 * <p>A publisher does <strong>not</strong> receive its own message back.
 * Echoing would make the common "apply locally, then tell everyone else"
 * pattern apply twice.
 */
public interface MessagingProvider {

    /** Sends a message to every other connected service, and to the node. */
    CompletableFuture<Void> publish(String channel, String payload);

    /**
     * Registers a handler for a channel.
     *
     * <p>Several handlers may share a channel; all of them are called. Handlers
     * run on a network thread, so anything slow belongs on another one.
     */
    void subscribe(String channel, Consumer<ChannelMessage> handler);

    /** Drops every handler registered for a channel. */
    void unsubscribe(String channel);
}
