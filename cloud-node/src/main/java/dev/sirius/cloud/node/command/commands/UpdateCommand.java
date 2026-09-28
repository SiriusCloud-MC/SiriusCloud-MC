package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.driver.update.UpdateService;
import dev.sirius.cloud.node.command.Command;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** The node's version, and new ones: check, or install now. */
public final class UpdateCommand implements Command {

    private final UpdateService updates;
    private final Supplier<String> installNow;

    public UpdateCommand(UpdateService updates, Supplier<String> installNow) {
        this.updates = updates;
        this.installNow = installNow;
    }

    @Override
    public String name() {
        return "update";
    }

    @Override
    public String usage() {
        return "update [check|now]";
    }

    @Override
    public String description() {
        return "Shows the version; checks for or installs a new one";
    }

    @Override
    public void execute(String[] args) {
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "check" -> CloudLogger.raw(updates.check());
            case "now" -> CloudLogger.raw(installNow.get());
            default -> {
                CloudLogger.raw("Version    : " + updates.current());
                CloudLogger.raw("Downloaded : " + updates.staged().map(Object::toString).orElse("nothing waiting"));
                CloudLogger.raw("Last check : " + updates.lastResult());
                CloudLogger.raw("Launcher   : " + (UpdateService.hasLauncher()
                        ? "start script, so updates can be installed"
                        : "not the start scripts - updates cannot install themselves"));
                CloudLogger.raw("'update check' looks now; 'update now' installs straight away. Services keep running.");
            }
        }
    }

    @Override
    public List<String> complete(String[] args) {
        return args.length <= 1 ? List.of("check", "now") : List.of();
    }
}
