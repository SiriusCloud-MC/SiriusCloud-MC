package dev.sirius.cloud.wrapper.config;

import dev.sirius.cloud.api.platform.Platform;

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
     * JVM used to run services. Empty means "the one running this wrapper",
     * resolved through {@link Platform#javaExecutable()} so the {@code .exe}
     * suffix is handled without the config having to care.
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

    public String javaExecutable() {
        return javaExecutable == null || javaExecutable.isBlank()
                ? Platform.javaExecutable()
                : javaExecutable;
    }

    public String[] defaultJvmArguments() {
        return defaultJvmArguments == null ? new String[0] : defaultJvmArguments;
    }

    public boolean debug() {
        return debug;
    }
}
