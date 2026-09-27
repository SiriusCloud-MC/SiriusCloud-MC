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

    /**
     * Whether players may be sent here on join or by {@code /hub}.
     *
     * <p>Lives on the group rather than in proxy configuration so that adding
     * a lobby group is one decision in one place, and the proxy needs no
     * knowledge of what the groups are called.
     */
    private boolean fallback = false;

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

    /**
     * How long a service may sit in {@code PREPARED} or {@code STARTING} before
     * it is killed and counted as a failed start.
     *
     * <p>Without a limit, a server that spawns but never becomes ready - a
     * deadlocked plugin, a world that will not load - holds its name, port and
     * memory forever, and nothing ever backs off because nothing ever fails.
     */
    private int startTimeoutSeconds = 180;

    /** Grow and shrink with player load; see {@link Autoscale}. */
    private Autoscale autoscale = new Autoscale();

    /**
     * Replace services older than this, one at a time, 0 to never.
     *
     * <p>Minecraft servers degrade over long uptimes, and restarting on a
     * schedule is standard practice. Done as a rolling replacement - start the
     * new one, move players across, stop the old - so the group never drops
     * below what it is serving.
     */
    private int maxUptimeMinutes = 0;

    /**
     * Roll the group when one of its template files changes on a wrapper.
     *
     * <p>Otherwise an edit to a template only reaches new services, and a
     * long-lived group can run on the old files for days without anybody
     * noticing the change never took effect.
     */
    private boolean rolloutOnTemplateChange = true;

    /**
     * Automatic scaling. SERVER groups only: a proxy's address is fixed, and a
     * second one on the next port along is no help to anybody.
     */
    public static final class Autoscale {

        private boolean enabled = true;

        /** Start another service when the group's running services are this full. */
        private int scaleUpAtPercent = 80;

        /** Stop a service that has had nobody on it for this long, if above the minimum. */
        private int scaleDownAfterEmptySeconds = 300;

        public boolean enabled() {
            return enabled;
        }

        public void enabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int scaleUpAtPercent() {
            return scaleUpAtPercent < 1 || scaleUpAtPercent > 100 ? 80 : scaleUpAtPercent;
        }

        public void scaleUpAtPercent(int scaleUpAtPercent) {
            this.scaleUpAtPercent = scaleUpAtPercent;
        }

        public int scaleDownAfterEmptySeconds() {
            return Math.max(30, scaleDownAfterEmptySeconds);
        }

        public void scaleDownAfterEmptySeconds(int scaleDownAfterEmptySeconds) {
            this.scaleDownAfterEmptySeconds = scaleDownAfterEmptySeconds;
        }
    }

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

    public boolean fallback() {
        return fallback;
    }

    public void fallback(boolean fallback) {
        this.fallback = fallback;
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

    public void build(String build) {
        this.build = build;
    }

    /** Empty when the wrapper's configured JVM should be used. */
    public String javaExecutable() {
        return javaExecutable == null ? "" : javaExecutable;
    }

    public void javaExecutable(String javaExecutable) {
        this.javaExecutable = javaExecutable;
    }

    public int startTimeoutSeconds() {
        return startTimeoutSeconds < 10 ? 180 : startTimeoutSeconds;
    }

    public void startTimeoutSeconds(int startTimeoutSeconds) {
        this.startTimeoutSeconds = startTimeoutSeconds;
    }

    public Autoscale autoscale() {
        if (autoscale == null) {
            autoscale = new Autoscale();
        }
        return autoscale;
    }

    public int maxUptimeMinutes() {
        return Math.max(0, maxUptimeMinutes);
    }

    public void maxUptimeMinutes(int maxUptimeMinutes) {
        this.maxUptimeMinutes = maxUptimeMinutes;
    }

    public boolean rolloutOnTemplateChange() {
        return rolloutOnTemplateChange;
    }

    public void rolloutOnTemplateChange(boolean rolloutOnTemplateChange) {
        this.rolloutOnTemplateChange = rolloutOnTemplateChange;
    }

    @Override
    public String toString() {
        return name + "{" + type + " " + minServiceCount + "-" + maxServiceCount + " " + memory + "MB}";
    }
}
