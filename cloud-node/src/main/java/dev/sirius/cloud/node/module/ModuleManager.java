package dev.sirius.cloud.node.module;

import com.google.gson.JsonSyntaxException;
import dev.sirius.cloud.api.driver.CloudDriver;
import dev.sirius.cloud.api.event.EventManager;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.module.CloudModule;
import dev.sirius.cloud.api.module.ModuleContext;
import dev.sirius.cloud.api.module.ModuleDescription;
import dev.sirius.cloud.driver.config.JsonConfig;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Loads, enables and unloads everything in {@code node/modules/}.
 *
 * <p>This is what makes milestone 4 additive: sign walls, a REST API, a panel
 * and permissions are modules against {@code CloudDriver} rather than changes
 * to the node. Without it each of those becomes core code, and the event bus —
 * which has carried {@code unsubscribeAll(ClassLoader)} unused since the first
 * commit, waiting for exactly this — stays decoration.
 *
 * <p>A module failing is never fatal. A bad jar, a missing manifest, a
 * constructor that throws: each is reported and skipped, because a cloud full
 * of players must not fail to start over an optional extra.
 */
public final class ModuleManager {

    private static final CloudLogger LOGGER = CloudLogger.of("Modules");

    private static final String MANIFEST = "module.json";

    private final Path directory;
    private final CloudDriver driver;
    private final EventManager events;

    /** Keyed by lowercased id, so commands are not case-sensitive. */
    private final Map<String, LoadedModule> modules = new ConcurrentHashMap<>();

    public ModuleManager(Path directory, CloudDriver driver, EventManager events) {
        this.directory = directory;
        this.driver = driver;
        this.events = events;
    }

    /** Discovers and enables every module jar in the directory. */
    public void loadAll() throws IOException {
        Files.createDirectories(directory);

        List<Path> jars;
        try (Stream<Path> files = Files.list(directory)) {
            jars = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        }

        if (jars.isEmpty()) {
            LOGGER.debug("No modules in {}", directory);
            return;
        }

        for (Path jar : jars) {
            load(jar).ifPresent(this::enable);
        }

        long enabled = modules.values().stream().filter(LoadedModule::enabled).count();
        LOGGER.info("{} of {} module(s) enabled", enabled, modules.size());
    }

    /**
     * Reads a jar and instantiates its main class, without enabling it.
     *
     * @return the module, or empty if the jar could not be used
     */
    public Optional<LoadedModule> load(Path jar) {
        ModuleDescription description;
        try {
            description = readDescription(jar);
        } catch (IOException | JsonSyntaxException exception) {
            LOGGER.warn("Skipping {}: {}", jar.getFileName(), exception.getMessage());
            return Optional.empty();
        }

        if (modules.containsKey(key(description.id()))) {
            LOGGER.warn("Skipping {}: a module with id '{}' is already loaded",
                    jar.getFileName(), description.id());
            return Optional.empty();
        }

        ModuleClassLoader classLoader = null;
        try {
            classLoader = new ModuleClassLoader(
                    description.id(),
                    jar.toUri().toURL(),
                    getClass().getClassLoader());

            Class<?> mainClass = Class.forName(description.main(), true, classLoader);
            if (!CloudModule.class.isAssignableFrom(mainClass)) {
                throw new IllegalStateException(
                        description.main() + " does not implement CloudModule");
            }

            CloudModule instance = (CloudModule) mainClass.getDeclaredConstructor().newInstance();

            LoadedModule module = new LoadedModule(description, jar, classLoader, instance);
            modules.put(key(description.id()), module);
            LOGGER.debug("Loaded {} from {}", description, jar.getFileName());
            return Optional.of(module);

        } catch (Exception exception) {
            LOGGER.warn("Could not load {}: {}", jar.getFileName(), rootMessage(exception));
            closeQuietly(classLoader);
            return Optional.empty();
        }
    }

    /** Calls {@code onEnable}. A module that throws is left loaded but disabled. */
    public boolean enable(LoadedModule module) {
        if (module.enabled()) {
            return true;
        }

        Path dataDirectory = directory.resolve(module.id());
        try {
            Files.createDirectories(dataDirectory);

            // The context is the entire surface: the driver, the module's own
            // description, and a directory to keep state in. Nothing else.
            module.instance().onEnable(new ModuleContext(driver, module.description(), dataDirectory));

            module.enabled(true);
            module.failure(null);
            LOGGER.info("Enabled {} v{}", module.id(), module.description().version());
            return true;

        } catch (Throwable throwable) {
            // Throwable, not Exception: a module compiled against a different
            // API version fails with NoSuchMethodError, and that has to be
            // reported as a bad module rather than taking the node down.
            module.failure(rootMessage(throwable));
            LOGGER.error("Module '" + module.id() + "' failed to enable", asException(throwable));
            return false;
        }
    }

    /**
     * Calls {@code onDisable} and drops what the module registered.
     *
     * <p>The event unsubscription is not a courtesy: listeners hold the module's
     * class loader, so leaving them would keep every class of an unloaded module
     * alive and deliver events into code that has been shut down.
     */
    public boolean disable(LoadedModule module) {
        if (!module.enabled()) {
            return false;
        }
        try {
            module.instance().onDisable();
        } catch (Throwable throwable) {
            LOGGER.error("Module '" + module.id() + "' threw while disabling", asException(throwable));
        } finally {
            module.enabled(false);
            events.unsubscribeAll(module.classLoader());
            LOGGER.info("Disabled {}", module.id());
        }
        return true;
    }

    /** Disables a module and releases its class loader entirely. */
    public boolean unload(String id) {
        LoadedModule module = modules.get(key(id));
        if (module == null) {
            return false;
        }
        disable(module);
        modules.remove(key(id));
        closeQuietly(module.classLoader());
        return true;
    }

    /** Disables everything, for node shutdown. */
    public void disableAll() {
        List<LoadedModule> loaded = new ArrayList<>(modules.values());
        loaded.forEach(this::disable);
        loaded.forEach(module -> closeQuietly(module.classLoader()));
        modules.clear();
    }

    public Optional<LoadedModule> byId(String id) {
        return Optional.ofNullable(modules.get(key(id)));
    }

    public Collection<LoadedModule> all() {
        return List.copyOf(modules.values());
    }

    /** Reads {@code module.json} out of a jar without keeping the jar open. */
    private static ModuleDescription readDescription(Path jar) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(MANIFEST);
            if (entry == null) {
                throw new IOException("no " + MANIFEST + " in the jar");
            }
            try (Reader reader = new InputStreamReader(
                    jarFile.getInputStream(entry), StandardCharsets.UTF_8)) {
                ModuleDescription description = JsonConfig.gson()
                        .fromJson(reader, ModuleDescription.class);
                if (description == null || !description.isValid()) {
                    throw new IOException(MANIFEST + " needs at least 'id' and 'main'");
                }
                return description;
            }
        }
    }

    private static void closeQuietly(ModuleClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            classLoader.close();
        } catch (IOException exception) {
            LOGGER.debug("Could not close the class loader for {}: {}",
                    classLoader.moduleId(), exception.getMessage());
        }
    }

    private static String key(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    private static Exception asException(Throwable throwable) {
        return throwable instanceof Exception exception
                ? exception
                : new RuntimeException(throwable);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
