plugins {
    java
    alias(libs.plugins.shadow) apply false
}

allprojects {
    group = "dev.sirius.cloud"
    version = "1.0.0-SNAPSHOT"
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

    dependsOn(nodeJar, wrapperJar, paperPlugin, velocityPlugin, restModule, notifyModule)

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
    from("scripts") {
        into(".")
    }
}
