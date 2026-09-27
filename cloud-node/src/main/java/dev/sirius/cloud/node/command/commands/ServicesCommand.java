package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceProperties;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.service.ServiceRegistry;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;

public final class ServicesCommand implements Command {

    private final ServiceRegistry services;

    public ServicesCommand(ServiceRegistry services) {
        this.services = services;
    }

    @Override
    public String name() {
        return "services";
    }

    @Override
    public List<String> aliases() {
        return List.of("list", "ls");
    }

    @Override
    public String description() {
        return "Lists every known service";
    }

    @Override
    public void execute(String[] args) {
        Collection<ServiceInfo> all = services.all();
        if (all.isEmpty()) {
            CloudLogger.raw("No services are running.");
            return;
        }

        CloudLogger.raw(String.format("%-20s %-9s %-22s %-8s %-6s %-11s %-10s %s",
                "NAME", "STATE", "ADDRESS", "PLAYERS", "TPS", "HEAP", "UPTIME", "WRAPPER"));

        all.stream()
                .sorted(Comparator.comparing(ServiceInfo::name))
                .forEach(service -> CloudLogger.raw(String.format("%-20s %-9s %-22s %-8s %-6s %-11s %-10s %s",
                        service.name() + (service.property(ServiceProperties.DRAINING).isPresent() ? "*" : ""),
                        service.state(),
                        service.host() + ":" + service.port(),
                        service.playerCount() + "/" + service.maxPlayers(),
                        service.tps() < 0 ? "-" : String.format(Locale.ROOT, "%.1f", service.tps()),
                        service.heapMaxMb() <= 0 ? "-" : service.heapUsedMb() + "/" + service.heapMaxMb() + "M",
                        formatUptime(service.uptimeMillis()),
                        service.wrapperName())));

        if (all.stream().anyMatch(service -> service.property(ServiceProperties.DRAINING).isPresent())) {
            CloudLogger.raw("* draining: being emptied before a planned restart.");
        }

        CloudLogger.raw(all.size() + " service(s).");
    }

    /** Shared with the player commands, which show the same kind of duration. */
    static String formatUptime(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m" + (seconds % 60) + "s";
        }
        return (seconds / 3600) + "h" + ((seconds % 3600) / 60) + "m";
    }
}
