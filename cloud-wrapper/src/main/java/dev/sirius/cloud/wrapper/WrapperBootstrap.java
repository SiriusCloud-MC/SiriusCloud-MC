package dev.sirius.cloud.wrapper;

import dev.sirius.cloud.api.logging.CloudLogger;

import java.nio.file.Path;

/** Entry point for {@code cloud-wrapper.jar}. */
public final class WrapperBootstrap {

    public static void main(String[] args) {
        Path workingDirectory = Path.of(args.length > 0 ? args[0] : "").toAbsolutePath().normalize();

        try {
            new CloudWrapper(workingDirectory).start();
        } catch (Exception exception) {
            CloudLogger.of("Bootstrap").error("The wrapper failed to start", exception);
            System.exit(1);
        }
        System.exit(0);
    }

    private WrapperBootstrap() {
    }
}
