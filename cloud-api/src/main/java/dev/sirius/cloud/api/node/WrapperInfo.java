package dev.sirius.cloud.api.node;

/**
 * A machine capable of running services. In a single-node deployment there is
 * exactly one of these; the protocol places no limit on the count, which is
 * what makes adding machine number two a configuration change rather than a
 * rewrite.
 */
public final class WrapperInfo {

    private String name;
    private String host;
    private int maxMemory;
    private int usedMemory;
    private String platform;

    /** Required by the JSON codec. */
    @SuppressWarnings("unused")
    WrapperInfo() {
    }

    public WrapperInfo(String name, String host, int maxMemory, String platform) {
        this.name = name;
        this.host = host;
        this.maxMemory = maxMemory;
        this.platform = platform;
    }

    public String name() {
        return name;
    }

    public String host() {
        return host;
    }

    public int maxMemory() {
        return maxMemory;
    }

    public int usedMemory() {
        return usedMemory;
    }

    public void usedMemory(int usedMemory) {
        this.usedMemory = usedMemory;
    }

    public int freeMemory() {
        return Math.max(0, maxMemory - usedMemory);
    }

    public String platform() {
        return platform;
    }

    @Override
    public String toString() {
        return name + "{" + host + " " + usedMemory + "/" + maxMemory + "MB " + platform + "}";
    }
}
