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

    /** Heap size in megabytes handed to the spawned JVM. */
    private int memory = 1024;

    private List<String> jvmArguments = new ArrayList<>();
    private List<String> templates = new ArrayList<>();

    /** Static services keep their working directory between restarts. */
    private boolean staticService = false;

    /** While true the provisioning loop leaves this group alone. */
    private boolean maintenance = false;

    /** First port of this group's range; services take the next free one. */
    private int startPort = 41000;

    /** Minecraft version, e.g. {@code 1.21.4}. */
    private String version = "1.21.4";

    /** Paper build number, or {@code latest}. */
    private String build = "latest";

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

    public String version() {
        return version;
    }

    public String build() {
        return build == null || build.isBlank() ? "latest" : build;
    }

    @Override
    public String toString() {
        return name + "{" + type + " " + minServiceCount + "-" + maxServiceCount + " " + memory + "MB}";
    }
}
