package dev.sirius.cloud.wrapper.template;

import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.wrapper.util.FileUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Copies template directories into a service's working directory.
 *
 * <p>Layout:
 * <pre>
 *   local/templates/global/server/    applied to every SERVER service
 *   local/templates/global/proxy/     applied to every PROXY service
 *   local/templates/&lt;group&gt;/&lt;name&gt;/    applied to that group, in listed order
 * </pre>
 *
 * <p>Plain recursive copies rather than symlinks or hard links: creating a
 * symlink on Windows needs either developer mode or elevation, so a link-based
 * design would work on Linux and fail on half the Windows installs out there.
 */
public final class TemplateManager {

    private static final CloudLogger LOGGER = CloudLogger.of(TemplateManager.class);

    private final Path templatesDirectory;

    public TemplateManager(Path templatesDirectory) {
        this.templatesDirectory = templatesDirectory;
    }

    /** Creates the directories for a group so the user has somewhere to put files. */
    public void prepare(ServiceGroup group) throws IOException {
        Files.createDirectories(globalTemplate(group.type()));

        for (String template : group.templates()) {
            Path path = templatesDirectory.resolve(group.name()).resolve(template);
            if (Files.notExists(path)) {
                Files.createDirectories(path);
                FileUtil.writeString(path.resolve("README.txt"),
                        "Everything in this directory is copied into each " + group.name()
                                + " service when it starts.\n"
                                + "Put plugins/, world/, server.properties overrides and so on here.\n");
                LOGGER.info("Created template directory {}", templatesDirectory.relativize(path));
            }
        }
    }

    /** Copies the global template and then each of the group's, in order. */
    public void apply(ServiceGroup group, Path serviceDirectory) throws IOException {
        FileUtil.copyDirectory(globalTemplate(group.type()), serviceDirectory);

        for (String template : group.templates()) {
            Path path = templatesDirectory.resolve(group.name()).resolve(template);
            if (Files.isDirectory(path)) {
                FileUtil.copyDirectory(path, serviceDirectory);
            }
        }

        // A convenience file, never something the user should edit in place.
        Files.deleteIfExists(serviceDirectory.resolve("README.txt"));
    }

    private Path globalTemplate(ServiceType type) {
        return templatesDirectory.resolve("global").resolve(type.name().toLowerCase(Locale.ROOT));
    }
}
