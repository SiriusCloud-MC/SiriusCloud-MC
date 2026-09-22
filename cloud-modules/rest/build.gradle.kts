/**
 * The REST API, as a module rather than part of the node.
 *
 * <p>Everything it needs is already on the node's classpath at runtime, so all
 * dependencies are compileOnly and the jar stays a few tens of kilobytes. That
 * is the point of parent-first module loading: the module uses the node's
 * `CloudDriver` and its Gson, rather than shipping second copies that could
 * never be the same classes anyway.
 *
 * The HTTP server is `com.sun.net.httpserver` from the JDK, so this adds no
 * dependency at all — consistent with a project whose API module deliberately
 * has none.
 */
plugins {
    java
}

dependencies {
    compileOnly(project(":cloud-api"))
    compileOnly(libs.gson)
}

tasks.jar {
    archiveBaseName.set("cloud-module-rest")
}
