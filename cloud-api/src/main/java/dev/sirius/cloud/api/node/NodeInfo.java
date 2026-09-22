package dev.sirius.cloud.api.node;

/**
 * A snapshot of the control plane itself.
 *
 * <p>Everything here was previously reachable only from inside the node, which
 * meant a module could describe every service in the cloud but not the thing
 * running them. Passed over the wire as JSON, so this stays a plain mutable
 * POJO.
 */
public final class NodeInfo {

    private String name;
    private String platform;

    /** What services and wrappers are told to dial back on. */
    private String connectAddress;
    private int port;

    private int maxMemory;
    private int committedMemory;

    private int serviceCount;
    private int wrapperCount;
    private int playerCount;

    private long startedAt;

    /** Required by the JSON codec. */
    @SuppressWarnings("unused")
    NodeInfo() {
    }

    public NodeInfo(String name, String platform, String connectAddress, int port, long startedAt) {
        this.name = name;
        this.platform = platform;
        this.connectAddress = connectAddress;
        this.port = port;
        this.startedAt = startedAt;
    }

    public String name() {
        return name;
    }

    public String platform() {
        return platform;
    }

    public String connectAddress() {
        return connectAddress;
    }

    public int port() {
        return port;
    }

    public int maxMemory() {
        return maxMemory;
    }

    public void maxMemory(int maxMemory) {
        this.maxMemory = maxMemory;
    }

    public int committedMemory() {
        return committedMemory;
    }

    public void committedMemory(int committedMemory) {
        this.committedMemory = committedMemory;
    }

    public int serviceCount() {
        return serviceCount;
    }

    public void serviceCount(int serviceCount) {
        this.serviceCount = serviceCount;
    }

    public int wrapperCount() {
        return wrapperCount;
    }

    public void wrapperCount(int wrapperCount) {
        this.wrapperCount = wrapperCount;
    }

    public int playerCount() {
        return playerCount;
    }

    public void playerCount(int playerCount) {
        this.playerCount = playerCount;
    }

    public long startedAt() {
        return startedAt;
    }

    public long uptimeMillis() {
        return System.currentTimeMillis() - startedAt;
    }

    @Override
    public String toString() {
        return name + "{" + committedMemory + "/" + maxMemory + "MB, "
                + serviceCount + " services, " + wrapperCount + " wrappers}";
    }
}
