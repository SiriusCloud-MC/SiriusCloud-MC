package dev.sirius.cloud.wrapper.template;

import dev.sirius.cloud.api.logging.CloudLogger;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Notices when a template changes on disk, so the node can roll the group.
 *
 * <p>Reports a group only once its files have been quiet for a while. Copying a
 * plugin folder into a template is dozens of events over several seconds, and
 * an editor saving a config writes a temporary file and renames it; reporting
 * each would start a rollout per event.
 *
 * <p>The JDK's watch service is recursive on neither Linux nor Windows, so every
 * directory is registered individually, and new ones as they appear. Directory
 * creation and the README the wrapper writes itself do not count as changes -
 * otherwise creating a group would immediately roll it.
 */
public final class TemplateWatcher implements AutoCloseable {

    private static final CloudLogger LOGGER = CloudLogger.of("Templates");

    /** How long a group's files must be still before the change is reported. */
    private static final long QUIET_MILLIS = 10_000;

    private final Path root;
    private final Consumer<String> onChanged;
    private final Map<WatchKey, Path> keys = new ConcurrentHashMap<>();
    private final Map<String, Long> pending = new ConcurrentHashMap<>();

    private WatchService watcher;
    private ScheduledExecutorService flusher;

    public TemplateWatcher(Path root, Consumer<String> onChanged) {
        this.root = root;
        this.onChanged = onChanged;
    }

    public void start() {
        try {
            watcher = FileSystems.getDefault().newWatchService();
            registerAll(root);
        } catch (IOException exception) {
            // Losing automatic rollouts is not a reason to refuse to run
            // services; 'rollout' still works by hand.
            LOGGER.warn("Cannot watch templates for changes ({}); roll groups by hand", exception.getMessage());
            return;
        }

        Thread thread = new Thread(this::watch, "sirius-template-watcher");
        thread.setDaemon(true);
        thread.start();

        flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread flush = new Thread(runnable, "sirius-template-flush");
            flush.setDaemon(true);
            return flush;
        });
        flusher.scheduleWithFixedDelay(this::flush, 2, 2, TimeUnit.SECONDS);
    }

    private void watch() {
        while (true) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException | ClosedWatchServiceException exception) {
                return;
            }

            Path directory = keys.get(key);
            if (directory != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    handle(directory.resolve((Path) event.context()), event.kind());
                }
            }

            if (!key.reset()) {
                keys.remove(key);
            }
        }
    }

    private void handle(Path changed, WatchEvent.Kind<?> kind) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
            // A new directory is a place for files, not a change in itself -
            // but whatever lands in it later has to be seen.
            try {
                registerAll(changed);
            } catch (IOException exception) {
                LOGGER.debug("Cannot watch {}: {}", changed, exception.getMessage());
            }
            return;
        }
        if (changed.getFileName().toString().equalsIgnoreCase("README.txt")) {
            return;
        }

        Path relative = root.relativize(changed);
        if (relative.getNameCount() < 2) {
            // A file directly in the templates root belongs to no group.
            return;
        }
        pending.put(relative.getName(0).toString(), System.currentTimeMillis());
    }

    private void flush() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : List.copyOf(pending.entrySet())) {
            if (now - entry.getValue() >= QUIET_MILLIS && pending.remove(entry.getKey(), entry.getValue())) {
                LOGGER.info("Template of {} changed", entry.getKey());
                onChanged.accept(entry.getKey());
            }
        }
    }

    private void registerAll(Path start) throws IOException {
        if (!Files.isDirectory(start)) {
            return;
        }
        try (Stream<Path> directories = Files.walk(start)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                WatchKey key = directory.register(watcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                keys.put(key, directory);
            }
        }
    }

    @Override
    public void close() {
        if (flusher != null) {
            flusher.shutdownNow();
        }
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException ignored) {
                // Shutting down.
            }
        }
    }
}
