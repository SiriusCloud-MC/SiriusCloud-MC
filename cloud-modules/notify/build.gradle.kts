/**
 * Cloud event notifications, as a module.
 *
 * <p>Everything it needs is on the node's classpath at runtime, so the
 * dependencies are compileOnly and the jar stays tiny.
 */
plugins {
    java
}

dependencies {
    compileOnly(project(":cloud-api"))
    compileOnly(libs.gson)
}

tasks.jar {
    archiveBaseName.set("cloud-module-notify")
}
