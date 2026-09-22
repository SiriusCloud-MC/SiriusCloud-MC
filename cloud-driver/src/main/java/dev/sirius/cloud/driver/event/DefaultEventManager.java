package dev.sirius.cloud.driver.event;

import dev.sirius.cloud.api.event.Event;
import dev.sirius.cloud.api.event.EventManager;
import dev.sirius.cloud.api.logging.CloudLogger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Event bus shared by the node and every remote driver.
 *
 * <p>Subscriber lists are copy-on-write: posting is far more frequent than
 * subscribing, and a handler that subscribes while an event is being delivered
 * must not blow up the iteration.
 */
public final class DefaultEventManager implements EventManager {

    private static final CloudLogger LOGGER = CloudLogger.of(DefaultEventManager.class);

    private final Map<Class<?>, List<Subscription<?>>> subscriptions = new ConcurrentHashMap<>();

    @Override
    public <T extends Event> void subscribe(Class<T> eventType, Consumer<T> handler) {
        subscribe(eventType, 0, handler);
    }

    @Override
    public <T extends Event> void subscribe(Class<T> eventType, int priority, Consumer<T> handler) {
        List<Subscription<?>> handlers = subscriptions.computeIfAbsent(
                eventType, key -> new CopyOnWriteArrayList<>());

        handlers.add(new Subscription<>(handler, priority, handler.getClass().getClassLoader()));

        // Cheaper to sort on the rare subscribe than on every post.
        List<Subscription<?>> sorted = new ArrayList<>(handlers);
        sorted.sort(Comparator.comparingInt((Subscription<?> s) -> s.priority).reversed());
        handlers.clear();
        handlers.addAll(sorted);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> T post(T event) {
        List<Subscription<?>> handlers = subscriptions.get(event.getClass());
        if (handlers == null) {
            return event;
        }
        for (Subscription<?> subscription : handlers) {
            try {
                ((Consumer<T>) subscription.handler).accept(event);
            } catch (Exception exception) {
                // One misbehaving listener must not stop the rest, and must
                // never propagate into the caller that posted the event.
                LOGGER.error("Listener failed handling " + event.getClass().getSimpleName(), exception);
            }
        }
        return event;
    }

    @Override
    public void unsubscribeAll(ClassLoader owner) {
        subscriptions.values().forEach(handlers ->
                handlers.removeIf(subscription -> subscription.owner == owner));
    }

    private record Subscription<T extends Event>(Consumer<T> handler, int priority, ClassLoader owner) {
    }
}
