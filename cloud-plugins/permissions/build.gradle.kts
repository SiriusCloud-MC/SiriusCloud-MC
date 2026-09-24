/**
 * The permissions plugin, deliberately separate from the core cloud plugin.
 *
 * <p>Separate because permissions are opt-in: a server already running
 * LuckPerms should not have a second thing attaching permissions to its
 * players, and the core plugin has to stay installable everywhere.
 *
 * <p><strong>Nothing is shaded, and that is load-bearing.</strong> The core
 * plugin already shades {@code cloud-api} into the server, and Bukkit shares
 * classes between plugins, so this one finds {@code CloudDriver} there. Shading
 * a second copy would give this plugin its own {@code CloudDriver} class, and
 * {@code CloudDriver.instance()} would return an object it could not cast -
 * the failure looking like a ClassCastException between two identically named
 * types. Gson comes from Paper, which ships it.
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
    archiveBaseName.set("cloud-plugin-permissions")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
