package dev.sirius.cloud.module.common;

import dev.sirius.cloud.api.network.CommandSender;
import dev.sirius.cloud.api.network.NetworkCommand;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * A shorter way to declare a {@link NetworkCommand}.
 *
 * <pre>{@code
 * Commands.named("find").permission("siriuscloud.find")
 *         .description("Where a player is")
 *         .executes((sender, args) -> ...)
 *         .build();
 * }</pre>
 */
public final class Commands {

    private final String name;
    private List<String> aliases = List.of();
    private String permission;
    private String description = "";
    private List<String> extraPermissions = List.of();
    private BiConsumer<CommandSender, String[]> execute = (sender, args) -> { };
    private BiFunction<CommandSender, String[], List<String>> suggest = (sender, args) -> List.of();

    private Commands(String name) {
        this.name = name;
    }

    public static Commands named(String name) {
        return new Commands(name);
    }

    public Commands aliases(String... aliases) {
        this.aliases = List.of(aliases);
        return this;
    }

    public Commands permission(String permission) {
        this.permission = permission;
        return this;
    }

    public Commands description(String description) {
        this.description = description;
        return this;
    }

    public Commands extraPermissions(String... permissions) {
        this.extraPermissions = List.of(permissions);
        return this;
    }

    public Commands executes(BiConsumer<CommandSender, String[]> execute) {
        this.execute = execute;
        return this;
    }

    public Commands suggests(BiFunction<CommandSender, String[], List<String>> suggest) {
        this.suggest = suggest;
        return this;
    }

    public NetworkCommand build() {
        String builtName = name;
        List<String> builtAliases = aliases;
        String builtPermission = permission;
        String builtDescription = description;
        List<String> builtExtras = extraPermissions;
        BiConsumer<CommandSender, String[]> builtExecute = execute;
        BiFunction<CommandSender, String[], List<String>> builtSuggest = suggest;
        return new NetworkCommand() {
            @Override
            public String name() {
                return builtName;
            }

            @Override
            public List<String> aliases() {
                return builtAliases;
            }

            @Override
            public String permission() {
                return builtPermission;
            }

            @Override
            public String description() {
                return builtDescription;
            }

            @Override
            public List<String> extraPermissions() {
                return builtExtras;
            }

            @Override
            public void execute(CommandSender sender, String[] args) {
                builtExecute.accept(sender, args);
            }

            @Override
            public List<String> suggest(CommandSender sender, String[] args) {
                return builtSuggest.apply(sender, args);
            }
        };
    }

    /** Everything from {@code from} on, joined back into one string. */
    public static String rest(String[] args, int from) {
        return from >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    /** Entries starting with what has been typed so far, ignoring case. */
    public static List<String> matching(List<String> options, String[] args) {
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }
}
