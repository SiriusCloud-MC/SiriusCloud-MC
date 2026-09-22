package dev.sirius.cloud.api.event;

import java.util.function.Consumer;

/**
 * Type-safe event bus. No annotations and no classpath scanning: subscription
 * is an ordinary method call, which keeps module loading cheap and makes
 * "who listens to this?" answerable by your IDE.
 */
public interface EventManager {

    <T extends Event> void subscribe(Class<T> eventType, Consumer<T> handler);

    <T extends Event> void subscribe(Class<T> eventType, int priority, Consumer<T> handler);

    /**
     * Delivers the event to every subscriber, highest priority first.
     *
     * @return the same event instance, for chaining
     */
    <T extends Event> T post(T event);

    /** Drops every subscriber registered by a module being unloaded. */
    void unsubscribeAll(ClassLoader owner);
}
