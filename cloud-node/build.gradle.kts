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

    // Every backend ships in the node jar, so switching from JSON files to a
    // real database is a config change and never a hunt for the right driver.
    // slf4j-nop because Hikari and the Mongo driver log through SLF4J and would
    // otherwise print a warning about a missing binding on every start.
    implementation(libs.bundles.database)
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
