plugins {
    java
}

dependencies {
    // On the node's classpath at runtime, so compileOnly and the jar stays small.
    compileOnly(project(":cloud-api"))
    compileOnly(libs.gson)
    implementation(project(":cloud-modules:common"))
}

val common = project(":cloud-modules:common")

tasks.jar {
    archiveBaseName.set("cloud-module-display")
    // The shared helpers ship inside this jar; see cloud-modules/common.
    dependsOn(common.tasks.named("jar"))
    from(common.tasks.named<Jar>("jar").map { zipTree(it.archiveFile) })
}
