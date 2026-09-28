package dev.sirius.cloud.driver.update;

import dev.sirius.cloud.api.logging.CloudLogger;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Looks for updates on a schedule and acts on what the settings allow.
 *
 * <p>What happens once a release is staged is the component's decision -
 * {@link #onStaged} - because only it knows when restarting is harmless:
 * a node can restart while every server keeps running, a wrapper cannot.
 */
public final class UpdateService {

    /** Set by the start scripts, which install staged updates and restart on request. */
    public static final String LAUNCHER_VARIABLE = "SIRIUSCLOUD_LAUNCHER";

    /** Exit code asking the start scripts to start the process again. */
    public static final int RESTART_EXIT_CODE = 75;

    private static final CloudLogger LOGGER = CloudLogger.of("Updates");

    private final Updater updater;
    private final UpdateSettings settings;
    private final Consumer<Version> onStaged;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sirius-updates");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Version announced;
    private volatile String lastResult = "not checked yet";

    public UpdateService(Updater updater, UpdateSettings settings, Consumer<Version> onStaged) {
        this.updater = updater;
        this.settings = settings;
        this.onStaged = onStaged;
    }

    /** Whether this process was started by the start scripts, so a staged update will be installed. */
    public static boolean hasLauncher() {
        return System.getenv(LAUNCHER_VARIABLE) != null;
    }

    public void start() {
        if (settings.mode() == UpdateSettings.Mode.OFF) {
            return;
        }
        updater.staged().ifPresent(version -> {
            if (version.isNewerThan(updater.current())) {
                LOGGER.info("SiriusCloud {} is downloaded and installs on the next start", version);
            }
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                check();
            } catch (RuntimeException exception) {
                LOGGER.warn("Update check failed: {}", exception.getMessage());
            }
        }, 1, settings.checkEveryHours() * 60L, TimeUnit.MINUTES);
    }

    public void close() {
        scheduler.shutdownNow();
    }

    public Version current() {
        return updater.current();
    }

    public Optional<Version> staged() {
        return updater.staged().filter(version -> version.isNewerThan(updater.current()));
    }

    public String lastResult() {
        return lastResult;
    }

    /** Checks now. Returns what happened, in a sentence. */
    public synchronized String check() {
        Optional<Updater.Release> release;
        try {
            release = updater.newest();
        } catch (IOException exception) {
            lastResult = "could not reach GitHub: " + exception.getMessage();
            LOGGER.debug("Update check failed: {}", exception.getMessage());
            return lastResult;
        }
        if (release.isEmpty()) {
            lastResult = "up to date (" + updater.current() + ")";
            return lastResult;
        }
        Updater.Release newest = release.get();
        if (staged().filter(version -> version.compareTo(newest.version()) >= 0).isPresent()) {
            lastResult = newest.version() + " is downloaded and waiting for a restart";
            onStaged.accept(newest.version());
            return lastResult;
        }
        if (settings.mode() == UpdateSettings.Mode.NOTIFY) {
            if (!newest.version().equals(announced)) {
                announced = newest.version();
                LOGGER.info("SiriusCloud {} is available: {}", newest.version(), newest.page());
            }
            lastResult = newest.version() + " is available: " + newest.page();
            return lastResult;
        }
        try {
            LOGGER.info("Downloading SiriusCloud {}...", newest.version());
            updater.stage(newest);
        } catch (IOException exception) {
            lastResult = "downloading " + newest.version() + " failed: " + exception.getMessage();
            LOGGER.warn("Could not download SiriusCloud {}: {}", newest.version(), exception.getMessage());
            return lastResult;
        }
        LOGGER.info("SiriusCloud {} is downloaded and verified; it installs on the next start", newest.version());
        if (!hasLauncher()) {
            LOGGER.warn("Installing it needs the start scripts (start-node / start-wrapper), which were not used"
                    + " to start this process.");
        }
        lastResult = newest.version() + " downloaded, installs on the next start";
        onStaged.accept(newest.version());
        return lastResult;
    }
}
