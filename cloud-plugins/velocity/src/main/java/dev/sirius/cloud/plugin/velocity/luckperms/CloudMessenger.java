package dev.sirius.cloud.plugin.velocity.luckperms;

import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.messaging.ChannelMessage;
import net.luckperms.api.messenger.IncomingMessageConsumer;
import net.luckperms.api.messenger.Messenger;
import net.luckperms.api.messenger.message.OutgoingMessage;

/**
 * Carries LuckPerms' own sync messages over the cloud's connections.
 *
 * <p>LuckPerms needs a way to tell every other server "this player's
 * permissions changed, drop your cache". Out of the box that means standing up
 * Redis or RabbitMQ, or falling back to {@code pluginmsg}, which cannot reach a
 * server that currently has no players on it — exactly the server that will be
 * wrong when somebody joins it next.
 *
 * <p>The cloud already holds an authenticated connection to every running
 * service, including empty ones, so it is a strictly better transport and one
 * less daemon to run. LuckPerms encodes its own messages to a string and
 * decodes them itself; nothing here interprets them.
 */
public final class CloudMessenger implements Messenger {

    /** Shared by every service in the cloud, and by the proxy. */
    static final String CHANNEL = "luckperms:update";

    private final IncomingMessageConsumer consumer;

    CloudMessenger(IncomingMessageConsumer consumer) {
        this.consumer = consumer;
        CloudDriver.instance().messaging().subscribe(CHANNEL, this::receive);
    }

    @Override
    public void sendOutgoingMessage(OutgoingMessage outgoingMessage) {
        CloudDriver.instance().messaging()
                .publish(CHANNEL, outgoingMessage.asEncodedString());
    }

    private void receive(ChannelMessage message) {
        // LuckPerms deduplicates by the message id embedded in the payload, so
        // a message arriving twice is harmless. It never sees its own, because
        // the node does not echo to the publisher.
        consumer.consumeIncomingMessageAsString(message.payload());
    }

    @Override
    public void close() {
        // Unsubscribing by channel drops every handler on it, which is correct
        // here: LuckPerms obtains exactly one messenger per server.
        if (CloudDriver.isAvailable()) {
            CloudDriver.instance().messaging().unsubscribe(CHANNEL);
        }
    }
}
