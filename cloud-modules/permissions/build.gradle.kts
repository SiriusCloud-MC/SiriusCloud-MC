/**
 * Cloud-wide permissions, as a node module.
 *
 * <p>The node owns the data; the separate permissions plugins apply it. Keeping
 * the store here rather than in a plugin is what makes it cloud-wide: a change
 * made on one server is the same change everywhere, including on servers that
 * currently have nobody on them.
 */
plugins {
    java
}

dependencies {
    compileOnly(project(":cloud-api"))
    compileOnly(libs.gson)
}

tasks.jar {
    archiveBaseName.set("cloud-module-permissions")
}
