package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.node.command.Command;
import dev.sirius.cloud.node.command.CommandManager;

import java.util.List;

public final class HelpCommand implements Command {

    private final CommandManager commands;

    public HelpCommand(CommandManager commands) {
        this.commands = commands;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public List<String> aliases() {
        return List.of("?");
    }

    @Override
    public String description() {
        return "Lists every command";
    }

    @Override
    public void execute(String[] args) {
        CloudLogger.raw("Commands:");
        int width = commands.all().stream().mapToInt(command -> command.usage().length()).max().orElse(20);
        commands.all().forEach(command ->
                CloudLogger.raw("  " + pad(command.usage(), width) + "  " + command.description()));
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }
}
