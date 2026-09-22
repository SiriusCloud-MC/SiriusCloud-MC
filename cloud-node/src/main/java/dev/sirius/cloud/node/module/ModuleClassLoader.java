package dev.sirius.cloud.node.module;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * One class loader per module, so unloading one really does release its classes.
 *
 * <p>Deliberately <strong>parent-first</strong>, which is the opposite of what
 * most plugin systems do. A module must see the <em>same</em>
 * {@code CloudDriver}, {@code ServiceInfo} and event classes the node does: if
 * it loaded its own copies, {@code CloudDriver.instance()} would hand back an
 * object of a class the module cannot cast, and every event subscription would
 * silently match nothing. Child-first isolation is the right default when
 * plugins bring conflicting library versions; here the shared API is the whole
 * point of the module system.
 *
 * <p>The consequence is that a module bundling its own copy of a library the
 * node already has will use the node's. That is a real limitation and the
 * correct trade: a module's job is to use {@code CloudDriver}, not to run a
 * different stack beside it.
 */
final class ModuleClassLoader extends URLClassLoader {

    static {
        // Lets two modules load classes concurrently rather than serialising on
        // one lock. Safe because this loader adds no locking of its own.
        registerAsParallelCapable();
    }

    private final String moduleId;

    ModuleClassLoader(String moduleId, URL jar, ClassLoader parent) {
        super("module:" + moduleId, new URL[]{jar}, parent);
        this.moduleId = moduleId;
    }

    String moduleId() {
        return moduleId;
    }
}
