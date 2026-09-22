plugins {
    java
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":cloud-api"))
    implementation(project(":cloud-protocol"))
    implementation(project(":cloud-driver"))
    implementation(libs.snakeyaml)
}

application {
    mainClass.set("dev.sirius.cloud.wrapper.WrapperBootstrap")
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes(
            "Main-Class" to "dev.sirius.cloud.wrapper.WrapperBootstrap",
            "Implementation-Version" to project.version,
        )
    }
}
