package dev.sirius.cloud.node.module;

import dev.sirius.cloud.api.module.CloudModule;
import dev.sirius.cloud.api.module.ModuleDescription;

import java.nio.file.Path;

/** A module the node has instantiated, enabled or not. */
public final class LoadedModule {

    private final ModuleDescription description;
    private final Path jar;
    private final ModuleClassLoader classLoader;
    private final CloudModule instance;

    private volatile boolean enabled;

    /** Set when enabling threw, so {@code modules} can show why without a log dive. */
    private volatile String failure;

    LoadedModule(ModuleDescription description, Path jar,
                 ModuleClassLoader classLoader, CloudModule instance) {
        this.description = description;
        this.jar = jar;
        this.classLoader = classLoader;
        this.instance = instance;
    }

    public ModuleDescription description() {
        return description;
    }

    public String id() {
        return description.id();
    }

    public Path jar() {
        return jar;
    }

    public boolean enabled() {
        return enabled;
    }

    public String failure() {
        return failure == null ? "" : failure;
    }

    /** {@code enabled}, {@code failed} or {@code disabled}, for display. */
    public String status() {
        if (enabled) {
            return "enabled";
        }
        return failure == null ? "disabled" : "failed";
    }

    ModuleClassLoader classLoader() {
        return classLoader;
    }

    CloudModule instance() {
        return instance;
    }

    void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    void failure(String failure) {
        this.failure = failure;
    }
}
