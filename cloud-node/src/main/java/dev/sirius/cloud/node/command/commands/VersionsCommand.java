package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServerSoftware;
import dev.sirius.cloud.driver.paper.VersionCatalog;
import dev.sirius.cloud.node.command.Command;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Lists the Minecraft versions PaperMC currently publishes.
 *
 * <p>The list is fetched, never hardcoded, so whatever Paper releases next is
 * usable the day it appears without touching this code.
 */
public final class VersionsCommand implements Command {

    private static final CloudLogger LOGGER = CloudLogger.of("Versions");

    /** Versions per printed row; the full list is long. */
    private static final int COLUMNS = 6;

    private final Map<ServerSoftware, VersionCatalog> catalogs;
    private final String minimumVersion;

    public VersionsCommand(Map<ServerSoftware, VersionCatalog> catalogs, String minimumVersion) {
        this.catalogs = catalogs;
        this.minimumVersion = minimumVersion;
    }

    @Override
    public String name() {
        return "versions";
    }

    @Override
    public String usage() {
        return "versions [paper|purpur|folia|fabric|velocity] [--all]";
    }

    @Override
    public String description() {
        return "Lists the versions available to groups, per server software";
    }

    @Override
    public void execute(String[] args) {
        boolean all = false;
        ServerSoftware software = ServerSoftware.PAPER;

        for (String arg : args) {
            if (arg.equalsIgnoreCase("--all")) {
                all = true;
            } else if (arg.equalsIgnoreCase("proxy")) {
                software = ServerSoftware.VELOCITY;
            } else {
                var named = ServerSoftware.byId(arg);
                if (named.isEmpty()) {
                    LOGGER.warn("Unknown software '{}'. Usage: {}", arg, usage());
                    return;
                }
                software = named.get();
            }
        }

        VersionCatalog target = catalogs.get(software);
        // Velocity's own version line is unrelated to Minecraft's, so the
        // Minecraft-version floor would filter it to nothing.
        boolean useFloor = !all && software.type() == dev.sirius.cloud.api.service.ServiceType.SERVER;

        // Off the console thread: the first call is an HTTP round trip and the
        // prompt should not freeze while it happens.
        Thread.ofVirtual().name("versions").start(() -> {
            try {
                List<String> versions = useFloor ? target.from(minimumVersion) : target.versions();

                CloudLogger.raw(target.project() + " versions available"
                        + (useFloor ? " (from " + minimumVersion + "; use '--all' for everything)" : "") + ":");

                for (int i = 0; i < versions.size(); i += COLUMNS) {
                    List<String> row = versions.subList(i, Math.min(i + COLUMNS, versions.size()));
                    StringBuilder builder = new StringBuilder("  ");
                    row.forEach(version -> builder.append(String.format("%-16s", version)));
                    CloudLogger.raw(builder.toString().stripTrailing());
                }

                String newest = target.newestPublished();
                String stable = target.latest();

                CloudLogger.raw(versions.size() + " version(s). Newest published: " + newest
                        + (newest.equals(stable) ? "" : "  (pre-release)"));
                CloudLogger.raw("'latest' resolves to " + stable + ", the newest stable release.");
                CloudLogger.raw("Set a group's version with 'edit <group> version <version>', or 'latest'.");

            } catch (IOException exception) {
                LOGGER.warn("Could not reach {}: {}", target.project(), exception.getMessage());
                LOGGER.warn("Groups can still start from a jar in the wrapper's local/jars/ directory.");
            }
        });
    }

    @Override
    public List<String> complete(String[] args) {
        if (args.length <= 1) {
            List<String> options = new java.util.ArrayList<>(
                    Arrays.stream(ServerSoftware.values()).map(ServerSoftware::id).toList());
            options.add("--all");
            return options;
        }
        return List.of("--all");
    }
}
