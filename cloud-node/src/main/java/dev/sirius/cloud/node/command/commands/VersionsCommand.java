package dev.sirius.cloud.node.command.commands;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.driver.paper.PaperVersionCatalog;
import dev.sirius.cloud.node.command.Command;

import java.io.IOException;
import java.util.List;

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

    private final PaperVersionCatalog paper;
    private final PaperVersionCatalog velocity;
    private final String minimumVersion;

    public VersionsCommand(PaperVersionCatalog paper, PaperVersionCatalog velocity, String minimumVersion) {
        this.paper = paper;
        this.velocity = velocity;
        this.minimumVersion = minimumVersion;
    }

    @Override
    public String name() {
        return "versions";
    }

    @Override
    public String usage() {
        return "versions [paper|velocity] [--all]";
    }

    @Override
    public String description() {
        return "Lists the Paper or Velocity versions available to groups";
    }

    @Override
    public void execute(String[] args) {
        boolean all = false;
        PaperVersionCatalog catalog = paper;

        for (String arg : args) {
            if (arg.equalsIgnoreCase("--all")) {
                all = true;
            } else if (arg.equalsIgnoreCase("velocity") || arg.equalsIgnoreCase("proxy")) {
                catalog = velocity;
            }
        }

        // Velocity's own version line is unrelated to Minecraft's, so the
        // Minecraft-version floor would filter it to nothing.
        boolean useFloor = !all && catalog == paper;
        PaperVersionCatalog target = catalog;

        // Off the console thread: the first call is an HTTP round trip and the
        // prompt should not freeze while it happens.
        Thread.ofVirtual().name("paper-versions").start(() -> {
            try {
                List<String> versions = useFloor ? target.from(minimumVersion) : target.versions();

                CloudLogger.raw(target.project() + " versions available"
                        + (useFloor ? " (from " + minimumVersion + "; use '--all' for everything)" : "") + ":");

                for (int i = 0; i < versions.size(); i += COLUMNS) {
                    List<String> row = versions.subList(i, Math.min(i + COLUMNS, versions.size()));
                    StringBuilder builder = new StringBuilder("  ");
                    row.forEach(version -> builder.append(String.format("%-12s", version)));
                    CloudLogger.raw(builder.toString().stripTrailing());
                }

                String newest = target.newestPublished();
                String stable = target.latest();

                CloudLogger.raw(versions.size() + " version(s). Newest published: " + newest
                        + (newest.equals(stable) ? "" : "  (pre-release)"));
                CloudLogger.raw("'latest' resolves to " + stable + ", the newest stable release.");
                CloudLogger.raw("Set 'version' in a group's JSON to any of these, or 'latest'.");

            } catch (IOException exception) {
                LOGGER.warn("Could not reach PaperMC: {}", exception.getMessage());
                LOGGER.warn("Groups can still start from a jar in the wrapper's local/jars/ directory.");
            }
        });
    }

    @Override
    public List<String> complete(String[] args) {
        return args.length <= 1 ? List.of("paper", "velocity", "--all") : List.of("--all");
    }
}
