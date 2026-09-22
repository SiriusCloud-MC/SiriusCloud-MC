package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.console.ConsoleAttachment;
import dev.sirius.cloud.node.console.NodeConsole;
import dev.sirius.cloud.node.service.ServiceManager;
import dev.sirius.cloud.node.service.ServiceRegistry;

import java.util.List;
import java.util.Optional;

/**
 * Hands the console over to one service.
 *
 * <p>Service output does not appear in the node console otherwise. With more
 * than a couple of servers running, interleaved output from all of them buries
 * the node's own logs and is unreadable anyway — and the wrapper does not even
 * send it unless somebody attaches.
 */
public final class AttachCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Console");

    private final ServiceRegistry services;
    private final ServiceManager serviceManager;
    private final NodeConsole console;

    public AttachCommand(ServiceRegistry services, ServiceManager serviceManager, NodeConsole console) {
        this.services = services;
        this.serviceManager = serviceManager;
        this.console = console;
    }

    @Override
    public String name() {
        return "attach";
    }

    @Override
    public List<String> aliases() {
        return List.of("console", "screen");
    }

    @Override
    public String usage() {
        return "attach <service>";
    }

    @Override
    public String description() {
        return "Opens a service's console; #detach or Ctrl+C returns";
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
        if (service.state().isTerminal()) {
            LOGGER.warn("{} is {} and has no console", service.name(), service.state());
            return;
        }

        // Ask the wrapper to start streaming before switching the console over,
        // so the backlog arrives into an attachment that will display it.
        serviceManager.subscribeConsole(service.uniqueId(), true);

        console.attach(new ConsoleAttachment(
                service.uniqueId(),
                service.name(),
                command -> serviceManager.dispatchCommand(service.uniqueId(), command),
                () -> serviceManager.subscribeConsole(service.uniqueId(), false)));
    }

    @Override
    public List<String> complete(String[] args) {
        return args.length <= 1
                ? services.all().stream()
                        .filter(service -> !service.state().isTerminal())
                        .map(ServiceInfo::name)
                        .toList()
                : List.of();
    }
}
