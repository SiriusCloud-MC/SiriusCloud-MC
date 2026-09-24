package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.service.ServiceManager;
import dev.sirius.cloud.node.service.ServiceRegistry;

import java.util.List;
import java.util.Optional;

/**
 * Stops a service and starts a fresh one of the same group.
 *
 * <p>Not a "restart" in the sense of the same process coming back: the node
 * allocates a new service, which may land on a different machine and a
 * different port. That is the honest behaviour for a cloud, and it is why the
 * new service is announced by name rather than pretending nothing changed.
 */
public final class RestartCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Console");

    /** How long the old service gets to disappear before we give up waiting. */
    private static final int STOP_TIMEOUT_SECONDS = 60;

    private final ServiceRegistry services;
    private final ServiceManager serviceManager;

    public RestartCommand(ServiceRegistry services, ServiceManager serviceManager) {
        this.services = services;
        this.serviceManager = serviceManager;
    }

    @Override
    public String name() {
        return "restart";
    }

    @Override
    public String usage() {
        return "restart <service> [--force]";
    }

    @Override
    public String description() {
        return "Stops a service and starts a replacement of its group";
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 1) {
            LOGGER.warn("Usage: {}", usage());
            return;
        }

        Optional<ServiceInfo> found = services.byName(args[0]);
        if (found.isEmpty()) {
            LOGGER.warn("No service named '{}'. Try 'services'.", args[0]);
            return;
        }

        ServiceInfo service = found.get();
        boolean force = args.length > 1 && args[1].equalsIgnoreCase("--force");
        String group = service.groupName();

        LOGGER.info("Restarting {}{}", service.name(), force ? " (forced)" : "");
        serviceManager.stop(service.uniqueId(), force);

        // Waiting on a virtual thread rather than blocking the console: a
        // graceful stop takes as long as the world takes to save, and the
        // operator should be able to keep typing meanwhile.
        Thread.ofVirtual().name("restart-" + service.name()).start(() -> {
            long deadline = System.currentTimeMillis() + STOP_TIMEOUT_SECONDS * 1000L;

            while (services.byId(service.uniqueId()).isPresent()) {
                if (System.currentTimeMillis() > deadline) {
                    LOGGER.warn("{} did not stop within {}s; not starting a replacement.",
                            service.name(), STOP_TIMEOUT_SECONDS);
                    LOGGER.warn("Use 'restart {} --force' if it is wedged.", service.name());
                    return;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            serviceManager.start(group).whenComplete((started, error) -> {
                if (error != null) {
                    LOGGER.warn("{} stopped, but starting a replacement failed: {}",
                            service.name(), rootMessage(error));
                } else {
                    LOGGER.info("{} replaced by {}", service.name(), started.name());
                }
            });
        });
    }

    @Override
    public List<String> complete(String[] args) {
        if (args.length <= 1) {
            return services.all().stream()
                    .filter(service -> !service.state().isTerminal())
                    .map(ServiceInfo::name)
                    .toList();
        }
        return List.of("--force");
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
