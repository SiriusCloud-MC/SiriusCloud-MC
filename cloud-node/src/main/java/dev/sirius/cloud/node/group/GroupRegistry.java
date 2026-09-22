package dev.sirius.cloud.node.group;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.driver.config.JsonConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Loads, creates and persists group definitions from {@code node/groups/}.
 *
 * <p>The node owns the <em>definition</em> of a group; the template files
 * themselves live on the wrapper that runs the services. Node-side template
 * storage with push-to-wrapper deployment is a later milestone — until then
 * there is exactly one template directory and no question about which copy wins.
 */
public final class GroupRegistry {

    private static final CloudLogger LOGGER = CloudLogger.of(GroupRegistry.class);

    private final Path directory;
    private final Map<String, ServiceGroup> groups = new ConcurrentHashMap<>();

    public GroupRegistry(Path directory) {
        this.directory = directory;
    }

    public void load() throws IOException {
        Files.createDirectories(directory);

        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                try {
                    ServiceGroup group = JsonConfig.load(path, ServiceGroup.class);
                    if (group != null && group.name() != null) {
                        groups.put(group.name().toLowerCase(Locale.ROOT), group);
                        LOGGER.info("Loaded group {}", group);
                    }
                } catch (IOException exception) {
                    LOGGER.error("Could not read group file " + path.getFileName(), exception);
                }
            });
        }

    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    public ServiceGroup create(ServiceGroup group) throws IOException {
        groups.put(group.name().toLowerCase(Locale.ROOT), group);
        save(group);
        return group;
    }

    public void save(ServiceGroup group) throws IOException {
        JsonConfig.save(directory.resolve(group.name() + ".json"), group);
    }

    public Optional<ServiceGroup> byName(String name) {
        return Optional.ofNullable(groups.get(name.toLowerCase(Locale.ROOT)));
    }

    public Collection<ServiceGroup> all() {
        return List.copyOf(groups.values());
    }
}
