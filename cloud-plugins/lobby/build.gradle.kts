/**
 * The lobby plugin: menus, hotbar, sidebar and protection for lobby servers.
 *
 * <p>Like the permissions plugin, nothing is shaded: the core cloud plugin
 * already provides cloud-api on the server, and a second copy would give this
 * plugin a CloudDriver class it cannot use.
 */
plugins {
    java
}

dependencies {
    compileOnly(project(":cloud-api"))
    compileOnly(libs.paper.api)
    compileOnly(libs.gson)
}

tasks.jar {
    archiveBaseName.set("cloud-plugin-lobby")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}
