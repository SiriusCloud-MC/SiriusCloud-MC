package dev.sirius.cloud.wrapper.config;

/** {@code wrapper/config.json}. */
public final class WrapperConfig {

    private String name = "wrapper-1";

    private String nodeHost = "127.0.0.1";
    private int nodePort = 1420;

    /** Must match the node's {@code secret}. */
    private String secret = "";

    /** Memory this machine will commit to services, in MB. */
    private int maxMemory = 4096;

    /**
     * Minimum Java feature version used to run services.
     *
     * <p>Deliberately not "whatever JVM the wrapper happens to be running on".
     * Minecraft's minimum moves with its releases — 26.x refuses to start on
     * anything below 25 — while the cloud itself targets 21, so the two are
     * genuinely different requirements. The wrapper locates a qualifying JDK on
     * this machine at startup and fails with install instructions if there
     * isn't one, rather than spawning servers that exit instantly.
     */
    private int serviceJavaVersion = 25;

    /**
     * Explicit JVM path for services, bypassing detection entirely.
     *
     * <p>Leave empty to auto-detect {@link #serviceJavaVersion}. A group may
     * override this again for its own services.
     */
    private String javaExecutable = "";

    /** Extra JVM flags applied to every service, before the group's own. */
    private String[] defaultJvmArguments = {
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=50",
            "-Dfile.encoding=UTF-8",
            "-Dcom.mojang.eula.agree=true",
    };

    private boolean debug = false;

    public String name() {
        return name;
    }

    public String nodeHost() {
        return nodeHost;
    }

    public int nodePort() {
        return nodePort;
    }

    public String secret() {
        return secret;
    }

    public int maxMemory() {
        return maxMemory;
    }

    public int serviceJavaVersion() {
        return serviceJavaVersion < 1 ? 25 : serviceJavaVersion;
    }

    /** Empty when the JVM should be detected rather than pinned. */
    public String javaExecutable() {
        return javaExecutable == null ? "" : javaExecutable;
    }

    public String[] defaultJvmArguments() {
        return defaultJvmArguments == null ? new String[0] : defaultJvmArguments;
    }

    public boolean debug() {
        return debug;
    }
}
