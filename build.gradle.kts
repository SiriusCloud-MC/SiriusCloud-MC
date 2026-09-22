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

    dependsOn(nodeJar, wrapperJar, paperPlugin)

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
    from("scripts") {
        into(".")
    }
}
