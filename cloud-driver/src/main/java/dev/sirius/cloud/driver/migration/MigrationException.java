package dev.sirius.cloud.driver.migration;

import java.io.IOException;

/**
 * Startup cannot continue on this data. An {@link IOException} so the
 * bootstraps print the message on its own instead of a stack trace: every one
 * of these is written for the operator to act on.
 */
public final class MigrationException extends IOException {

    public MigrationException(String message) {
        super(message);
    }

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
