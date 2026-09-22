package dev.sirius.cloud.node;

import dev.sirius.cloud.api.logging.CloudLogger;

import java.nio.file.Path;

/** Entry point for {@code cloud-node.jar}. */
public final class NodeBootstrap {

    public static void main(String[] args) {
        // The node's state lives beside the jar, so the working directory is
        // whatever the launcher script sets it to.
        Path workingDirectory = Path.of(args.length > 0 ? args[0] : "").toAbsolutePath().normalize();

        try {
            new CloudNode(workingDirectory).start();
        } catch (java.nio.channels.OverlappingFileLockException | java.io.IOException exception) {
            // Almost always "already running here" - a stack trace would bury
            // a message the operator can act on directly.
            CloudLogger.of("Bootstrap").error("The node cannot start: {}", exception.getMessage());
            System.exit(1);
        } catch (Exception exception) {
            CloudLogger.of("Bootstrap").error("The node failed to start", exception);
            System.exit(1);
        }
        System.exit(0);
    }

    private NodeBootstrap() {
    }
}
