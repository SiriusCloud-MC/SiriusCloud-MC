plugins {
    java
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":cloud-api"))
    implementation(project(":cloud-protocol"))
    implementation(project(":cloud-driver"))
    implementation(libs.bundles.jline)
}

application {
    mainClass.set("dev.sirius.cloud.node.NodeBootstrap")
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes(
            "Main-Class" to "dev.sirius.cloud.node.NodeBootstrap",
            "Implementation-Version" to project.version,
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
}
