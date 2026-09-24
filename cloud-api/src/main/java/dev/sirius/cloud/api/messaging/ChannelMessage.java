package dev.sirius.cloud.api.messaging;

/**
 * One message published on a channel.
 *
 * @param channel       the channel it was published on
 * @param payload       the message body, opaque to the cloud
 * @param sourceService name of the service that published it, or {@code "node"}
 */
public record ChannelMessage(String channel, String payload, String sourceService) {
}
