plugins {
    java
    alias(libs.plugins.shadow) apply false
}

allprojects {
    group = "dev.sirius.cloud"
    // Releases pass -PreleaseVersion=1.2.3 (the release workflow takes it from
    // the tag). It is what the jars report, and what the updater compares.
    version = (findProperty("releaseVersion") as String?)?.removePrefix("v") ?: "1.0.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    // Catalog accessors are not generated inside subprojects {}, so the
    // coordinates are looked up by name from the root's catalog.
    val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies {
        "testImplementation"(catalog.findLibrary("junit").get())
        "testRuntimeOnly"(catalog.findLibrary("junit-launcher").get())
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("file.encoding", "UTF-8")
    }
}

/**
 * Assembles a runnable layout under build/dist that works identically on
 * Linux and Windows. Shell and batch launchers are both generated.
 */
val dist by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Builds a runnable node + wrapper layout into build/dist"

    // Copy, not Sync: the output directory is also where the node and wrapper
    // keep their state at runtime - config.json with the generated secret, the
    // template directories, the cached server jars. Sync deletes everything it
    // did not put there, so rebuilding would silently wipe all of it.

    val nodeJar = project(":cloud-node").tasks.named("shadowJar")
    val wrapperJar = project(":cloud-wrapper").tasks.named("shadowJar")
    val paperPlugin = project(":cloud-plugins:paper").tasks.named("shadowJar")
    val velocityPlugin = project(":cloud-plugins:velocity").tasks.named("shadowJar")
    val restModule = project(":cloud-modules:rest").tasks.named("jar")
    val notifyModule = project(":cloud-modules:notify").tasks.named("jar")
    val permissionsModule = project(":cloud-modules:permissions").tasks.named("jar")
    val permissionsPlugin = project(":cloud-plugins:permissions").tasks.named("jar")
    val lobbyPlugin = project(":cloud-plugins:lobby").tasks.named("jar")
    // The network feature modules. Each carries its own copy of the shared
    // helpers in cloud-modules/common, which is therefore not shipped itself.
    val featureModules = listOf("display", "social", "moderation", "matchmaking", "metrics", "discord")
            .associateWith { project(":cloud-modules:$it").tasks.named("jar") }

    dependsOn(nodeJar, wrapperJar, paperPlugin, velocityPlugin, restModule, notifyModule,
            permissionsModule, permissionsPlugin, lobbyPlugin)
    dependsOn(featureModules.values)

    into(layout.buildDirectory.dir("dist"))

    from(nodeJar) {
        into("node")
        rename { "cloud-node.jar" }
    }
    from(wrapperJar) {
        into("wrapper")
        rename { "cloud-wrapper.jar" }
    }
    // The wrapper injects this into every service it starts.
    from(paperPlugin) {
        into("wrapper/plugins")
        rename { "cloud-plugin-paper.jar" }
    }
    from(velocityPlugin) {
        into("wrapper/plugins")
        rename { "cloud-plugin-velocity.jar" }
    }
    // Modules are loaded from here at node startup. Shipped enabled, because a
    // control plane you cannot see into is not much of a control plane; the
    // API binds to loopback and needs its token either way.
    from(restModule) {
        into("node/modules")
        rename { "cloud-module-rest.jar" }
    }
    from(notifyModule) {
        into("node/modules")
        rename { "cloud-module-notify.jar" }
    }
    from(permissionsModule) {
        into("node/modules")
        rename { "cloud-module-permissions.jar" }
    }
    // Every feature can be switched off in its module's config.json, or the
    // whole module by deleting its jar - worth doing where a network already
    // runs its own plugin for bans or private messages.
    featureModules.forEach { (name, jar) ->
        from(jar) {
            into("node/modules")
            rename { "cloud-module-$name.jar" }
        }
    }
    // Not injected into services automatically, unlike the core plugin:
    // permissions are opt-in, and a server already running LuckPerms must not
    // have a second thing attaching permissions to its players. Copy it into a
    // group's template to use it.
    from(permissionsPlugin) {
        into("wrapper/optional-plugins")
        rename { "cloud-plugin-permissions.jar" }
    }
    // The lobby plugin, for lobby groups' templates only.
    from(lobbyPlugin) {
        into("wrapper/optional-plugins")
        rename { "cloud-plugin-lobby.jar" }
    }
    from("scripts") {
        into(".")
    }
}
