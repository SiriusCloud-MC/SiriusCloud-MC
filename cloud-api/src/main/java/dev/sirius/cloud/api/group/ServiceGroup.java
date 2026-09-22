package dev.sirius.cloud.api.group;

import dev.sirius.cloud.api.service.ServiceType;

import java.util.ArrayList;
import java.util.List;

/**
 * A template for services: the node keeps {@link #minServiceCount()} of these
 * online at all times and will not exceed {@link #maxServiceCount()}.
 *
 * <p>Persisted as one JSON file per group under {@code node/groups/}.
 */
public final class ServiceGroup {

    private String name;
    private ServiceType type = ServiceType.SERVER;

    private int minServiceCount = 1;
    private int maxServiceCount = 5;
    private int maxPlayers = 50;

    /** Maximum heap in megabytes ({@code -Xmx}), and what the node budgets for. */
    private int memory = 1024;

    /**
     * Initial heap in megabytes ({@code -Xms}). Zero means "same as {@link #memory}".
     *
     * <p>Equal minimum and maximum is the usual advice for Minecraft: it stops
     * the heap growing during play, which is when a resize hurts most. Kept
     * configurable because packing many small servers onto one machine is the
     * case where starting low genuinely helps.
     */
    private int minMemory = 0;

    private List<String> jvmArguments = new ArrayList<>();
    private List<String> templates = new ArrayList<>();

    /** Static services keep their working directory between restarts. */
    private boolean staticService = false;

    /** While true the provisioning loop leaves this group alone. */
    private boolean maintenance = false;

    /** First port of this group's range; services take the next free one. */
    private int startPort = 41000;

    /**
     * Minecraft version, e.g. {@code 1.21.4}, or {@code latest}.
     *
     * <p>Whatever PaperMC currently publishes is valid — the list is fetched
     * from their API rather than hardcoded, so new releases work without a
     * code change. Run {@code versions} in the node console to see the list.
     */
    private String version = "latest";

    /** Paper build number, or {@code latest}. */
    private String build = "latest";

    /**
     * JVM to run this group's services with. Empty uses the wrapper's default.
     *
     * <p>Exists because the supported version range spans Minecraft releases
     * with different minimum Java versions: a group pinned to an older release
     * and a group on the newest one may genuinely need different JDKs on the
     * same machine.
     */
    private String javaExecutable = "";

    /** Required by the JSON codec. */
    @SuppressWarnings("unused")
    ServiceGroup() {
    }

    public ServiceGroup(String name, ServiceType type) {
        this.name = name;
        this.type = type;
        this.templates.add("default");
    }

    public String name() {
        return name;
    }

    public ServiceType type() {
        return type;
    }

    public int minServiceCount() {
        return minServiceCount;
    }

    public void minServiceCount(int minServiceCount) {
        this.minServiceCount = minServiceCount;
    }

    public int maxServiceCount() {
        return maxServiceCount;
    }

    public void maxServiceCount(int maxServiceCount) {
        this.maxServiceCount = maxServiceCount;
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    public int memory() {
        return memory;
    }

    public void memory(int memory) {
        this.memory = memory;
    }

    /** Initial heap, falling back to {@link #memory()} when unset or invalid. */
    public int minMemory() {
        return minMemory <= 0 || minMemory > memory ? memory : minMemory;
    }

    public void minMemory(int minMemory) {
        this.minMemory = minMemory;
    }

    public List<String> jvmArguments() {
        return jvmArguments == null ? List.of() : jvmArguments;
    }

    public List<String> templates() {
        return templates == null || templates.isEmpty() ? List.of("default") : templates;
    }

    public boolean staticService() {
        return staticService;
    }

    public boolean maintenance() {
        return maintenance;
    }

    public void maintenance(boolean maintenance) {
        this.maintenance = maintenance;
    }

    public int startPort() {
        return startPort;
    }

    public void startPort(int startPort) {
        this.startPort = startPort;
    }

    public void staticService(boolean staticService) {
        this.staticService = staticService;
    }

    public String version() {
        return version == null || version.isBlank() ? "latest" : version;
    }

    public void version(String version) {
        this.version = version;
    }

    public void maxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public String build() {
        return build == null || build.isBlank() ? "latest" : build;
    }

    /** Empty when the wrapper's configured JVM should be used. */
    public String javaExecutable() {
        return javaExecutable == null ? "" : javaExecutable;
    }

    @Override
    public String toString() {
        return name + "{" + type + " " + minServiceCount + "-" + maxServiceCount + " " + memory + "MB}";
    }
}
