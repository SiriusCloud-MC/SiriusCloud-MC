package dev.sirius.cloud.wrapper;

import dev.sirius.cloud.api.logging.CloudLogger;

import java.nio.file.Path;

/** Entry point for {@code cloud-wrapper.jar}. */
public final class WrapperBootstrap {

    public static void main(String[] args) {
        Path workingDirectory = Path.of(args.length > 0 ? args[0] : "").toAbsolutePath().normalize();

        int exitCode = 0;
        try {
            CloudWrapper wrapper = new CloudWrapper(workingDirectory);
            wrapper.start();
            exitCode = wrapper.exitCode();
        } catch (java.nio.channels.OverlappingFileLockException | java.io.IOException exception) {
            // Almost always "already running here" - a stack trace would bury
            // a message the operator can act on directly.
            CloudLogger.of("Bootstrap").error("The wrapper cannot start: {}", exception.getMessage());
            System.exit(1);
        } catch (Exception exception) {
            CloudLogger.of("Bootstrap").error("The wrapper failed to start", exception);
            System.exit(1);
        }
        System.exit(exitCode);
    }

    private WrapperBootstrap() {
    }
}
