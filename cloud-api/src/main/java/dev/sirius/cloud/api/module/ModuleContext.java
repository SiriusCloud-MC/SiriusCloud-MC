package dev.sirius.cloud.api.module;

import dev.sirius.cloud.api.driver.CloudDriver;

import java.nio.file.Path;

/**
 * What a module is given when it is enabled.
 *
 * <p>Deliberately narrow: the cloud is reached through {@link CloudDriver} and
 * nothing else, so a module cannot reach into node internals and a feature
 * written as a module would work just as well written as a plugin.
 *
 * @param driver        the node's own driver, identical in shape to a plugin's
 * @param description   this module's parsed {@code module.json}
 * @param dataDirectory {@code node/modules/<id>/}, created before enabling, for
 *                      the module's own configuration and state
 */
public record ModuleContext(CloudDriver driver, ModuleDescription description, Path dataDirectory) {
}
