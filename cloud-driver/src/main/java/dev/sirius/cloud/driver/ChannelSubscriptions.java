package dev.sirius.cloud.driver;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.messaging.ChannelMessage;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The subscriber side of a {@link dev.sirius.cloud.api.messaging.MessagingProvider},
 * shared by the local and remote implementations so they cannot drift.
 *
 * <p>Copy-on-write for the same reason the event bus is: delivery is far more
 * frequent than subscription, and a handler that subscribes while a message is
 * being delivered must not break the iteration.
 */
public final class ChannelSubscriptions {

    private static final CloudLogger LOGGER = CloudLogger.of("Messaging");

    private final Map<String, List<Consumer<ChannelMessage>>> handlers = new ConcurrentHashMap<>();

    public void subscribe(String channel, Consumer<ChannelMessage> handler) {
        handlers.computeIfAbsent(key(channel), key -> new CopyOnWriteArrayList<>()).add(handler);
    }

    public void unsubscribe(String channel) {
        handlers.remove(key(channel));
    }

    /** Whether anything is listening, so a broker can skip work nobody wants. */
    public boolean hasSubscribers(String channel) {
        List<Consumer<ChannelMessage>> registered = handlers.get(key(channel));
        return registered != null && !registered.isEmpty();
    }

    public void deliver(ChannelMessage message) {
        List<Consumer<ChannelMessage>> registered = handlers.get(key(message.channel()));
        if (registered == null) {
            return;
        }
        for (Consumer<ChannelMessage> handler : registered) {
            try {
                handler.accept(message);
            } catch (Exception exception) {
                // One bad subscriber must not stop the others, and must never
                // propagate back into the network thread that delivered this.
                LOGGER.error("Subscriber failed handling a message on " + message.channel(), exception);
            }
        }
    }

    private static String key(String channel) {
        return channel == null ? "" : channel.toLowerCase(Locale.ROOT);
    }
}
