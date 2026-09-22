package dev.sirius.cloud.node.console;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * An active attachment to one service's console.
 *
 * @param serviceId   the service being watched
 * @param serviceName its display name, used for the prompt
 * @param input       receives every line typed while attached
 * @param onDetach    run when the attachment ends, however it ends
 */
public record ConsoleAttachment(UUID serviceId,
                                String serviceName,
                                Consumer<String> input,
                                Runnable onDetach) {
}
