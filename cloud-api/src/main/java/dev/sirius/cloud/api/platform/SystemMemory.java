package dev.sirius.cloud.api.platform;

import java.lang.management.ManagementFactory;

/**
 * Physical memory on this machine, used to suggest sensible defaults during
 * setup rather than making the operator guess at a number.
 */
public final class SystemMemory {

    /** Held back for the OS, the node/wrapper themselves, and page cache. */
    private static final int RESERVED_MB = 2048;

    private SystemMemory() {
    }

    /**
     * Total physical RAM in MB, or -1 if it cannot be determined.
     *
     * <p>Reached through the {@code com.sun.management} extension of the
     * platform bean, which is part of the JDK rather than a dependency. Guarded
     * anyway, because a JVM is not obliged to provide it.
     */
    public static int totalMegabytes() {
        try {
            java.lang.management.OperatingSystemMXBean bean =
                    ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
                long bytes = extended.getTotalMemorySize();
                if (bytes > 0) {
                    return (int) (bytes / (1024 * 1024));
                }
            }
        } catch (Throwable ignored) {
            // No extended bean on this JVM; fall through to the default.
        }
        return -1;
    }

    /**
     * A reasonable default budget for services: physical RAM less a reserve,
     * rounded down to a whole gigabyte.
     *
     * @return the suggestion, or {@code fallback} when RAM cannot be read
     */
    public static int suggestedBudgetMegabytes(int fallback) {
        int total = totalMegabytes();
        if (total <= 0) {
            return fallback;
        }
        int usable = total - RESERVED_MB;
        if (usable < 1024) {
            // A small machine: leave a little, but suggest something workable.
            return Math.max(512, total / 2);
        }
        return (usable / 1024) * 1024;
    }

    /** Human-readable total, for showing alongside the suggestion. */
    public static String describeTotal() {
        int total = totalMegabytes();
        return total <= 0 ? "unknown" : total + "MB (" + String.format("%.1f", total / 1024.0) + "GB)";
    }
}
