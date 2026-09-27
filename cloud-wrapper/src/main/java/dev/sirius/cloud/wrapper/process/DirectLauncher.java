package dev.sirius.cloud.wrapper.process;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs the JVM as a child process of the wrapper. The default. */
public final class DirectLauncher implements ServiceLauncher {

    private static final CloudLogger LOGGER = CloudLogger.of("Service");

    /** How long {@code destroy()} gets before {@code destroyForcibly()}. */
    private static final int TERMINATE_SECONDS = 10;

    @Override
    public boolean containerised() {
        return false;
    }

    @Override
    public List<String> command(ServiceInfo info, ServiceGroup group, Path directory, List<String> javaCommand) {
        return javaCommand;
    }

    /**
     * {@code destroy()} then {@code destroyForcibly()}.
     *
     * <p>Only ever reached after the graceful stdin shutdown was ignored: on
     * Windows {@code destroy()} is already {@code TerminateProcess}, which no
     * process can catch, so it is the last resort there, never the first move.
     */
    @Override
    public void kill(ServiceInfo info, Process process) {
        process.destroy();
        try {
            if (!process.waitFor(TERMINATE_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("{} survived termination, killing it", info.name());
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    @Override
    public String nodeHostFor(String nodeHost) {
        return nodeHost;
    }
}
