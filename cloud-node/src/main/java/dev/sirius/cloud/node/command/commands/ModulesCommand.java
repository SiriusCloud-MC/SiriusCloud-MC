package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.module.LoadedModule;
import dev.sirius.cloud.node.module.ModuleManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Lists and toggles loaded modules. */
public final class ModulesCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Modules");

    private final ModuleManager modules;

    public ModulesCommand(ModuleManager modules) {
        this.modules = modules;
    }

    @Override
    public String name() {
        return "modules";
    }

    @Override
    public List<String> aliases() {
        return List.of("module");
    }

    @Override
    public String usage() {
        return "modules [enable|disable <id>]";
    }

    @Override
    public String description() {
        return "Lists loaded modules, or enables and disables one";
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            list();
            return;
        }

        if (args.length < 2) {
            LOGGER.warn("Usage: {}", usage());
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        Optional<LoadedModule> found = modules.byId(args[1]);
        if (found.isEmpty()) {
            LOGGER.warn("No module with id '{}'. Try 'modules'.", args[1]);
            return;
        }
        LoadedModule module = found.get();

        switch (action) {
            case "enable" -> {
                if (module.enabled()) {
                    LOGGER.warn("{} is already enabled", module.id());
                } else {
                    modules.enable(module);
                }
            }
            case "disable" -> {
                if (!modules.disable(module)) {
                    LOGGER.warn("{} is not enabled", module.id());
                }
            }
            default -> LOGGER.warn("Usage: {}", usage());
        }
    }

    private void list() {
        Collection<LoadedModule> all = modules.all();
        if (all.isEmpty()) {
            CloudLogger.raw("No modules are loaded. Drop a jar into node/modules/ and restart.");
            return;
        }

        CloudLogger.raw(String.format("%-20s %-12s %-10s %s", "ID", "VERSION", "STATUS", "DESCRIPTION"));

        all.forEach(module -> CloudLogger.raw(String.format("%-20s %-12s %-10s %s",
                module.id(),
                module.description().version(),
                module.status(),
                // A failed module's reason matters more than its blurb: it is
                // the only thing that says what to fix.
                module.failure().isEmpty() ? module.description().description() : module.failure())));

        CloudLogger.raw(all.size() + " module(s).");
    }

    @Override
    public List<String> complete(String[] args) {
        if (args.length <= 1) {
            return List.of("enable", "disable");
        }
        if (args.length == 2) {
            List<String> ids = new ArrayList<>();
            modules.all().forEach(module -> ids.add(module.id()));
            return ids;
        }
        return List.of();
    }
}
