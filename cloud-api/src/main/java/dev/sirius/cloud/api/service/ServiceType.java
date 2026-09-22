package dev.sirius.cloud.api.service;

/**
 * What kind of process a service is. Determines which template directory is
 * used, which plugin is injected, and which command shuts it down gracefully.
 */
public enum ServiceType {

    /** A Minecraft server (Paper). Stopped with {@code stop}. */
    SERVER("stop", "plugins"),

    /** A proxy (Velocity). Stopped with {@code end}. Milestone 2. */
    PROXY("end", "plugins");

    private final String shutdownCommand;
    private final String pluginDirectory;

    ServiceType(String shutdownCommand, String pluginDirectory) {
        this.shutdownCommand = shutdownCommand;
        this.pluginDirectory = pluginDirectory;
    }

    /**
     * The console command that makes this service type save and exit cleanly.
     *
     * <p>This matters more than it looks: {@code Process.destroy()} maps to
     * {@code TerminateProcess} on Windows, which kills the JVM outright and can
     * leave chunks unsaved. Writing the shutdown command to stdin is the only
     * graceful path that behaves identically on Linux and Windows.
     */
    public String shutdownCommand() {
        return shutdownCommand;
    }

    public String pluginDirectory() {
        return pluginDirectory;
    }
}
