package dev.sirius.cloud.api.event;

/**
 * Marker for everything posted on the {@link EventManager}.
 *
 * <p>The event bus exists from the first commit on purpose: modules subscribe
 * to it instead of patching core code, so every feature added later is additive.
 */
public interface Event {
}
