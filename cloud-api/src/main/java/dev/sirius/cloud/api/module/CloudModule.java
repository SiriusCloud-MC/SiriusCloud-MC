package dev.sirius.cloud.api.module;

/**
 * Something loaded from {@code node/modules/} at runtime.
 *
 * <p>This is the extension point the roadmap is built around: features are
 * added as modules rather than as changes to the node, so the core stays the
 * part that decides what runs where and nothing else. A module is handed a
 * {@link ModuleContext} and, through it, {@code CloudDriver} — the same
 * interface a plugin inside a running server compiles against. It is given no
 * access to the node's registries, which is what keeps a module from depending
 * on internals that are free to change.
 *
 * <p>Implementations need a public no-argument constructor; the loader
 * instantiates them reflectively.
 */
public interface CloudModule {

    /**
     * Called once when the module is enabled.
     *
     * <p>Throwing from here leaves the module disabled and the rest of the node
     * running: one broken module must not stop a cloud from starting.
     */
    void onEnable(ModuleContext context);

    /**
     * Called when the module is disabled, on node shutdown or by command.
     *
     * <p>Everything started in {@link #onEnable} has to be stopped here —
     * threads, sockets, open files. Event subscriptions are the exception: the
     * loader drops them by class loader, so a module cannot leak listeners by
     * forgetting.
     */
    default void onDisable() {
    }
}
