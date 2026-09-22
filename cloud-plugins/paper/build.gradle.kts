plugins {
    java
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":cloud-api"))
    implementation(project(":cloud-protocol"))
    implementation(project(":cloud-driver"))

    compileOnly(libs.paper.api)
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("cloud-plugin-paper")

    // Paper ships its own Netty and Gson on the server classpath. Loading a
    // second, differently-versioned copy of either into that classpath is a
    // reliable source of NoSuchMethodError at runtime, so both are relocated
    // into our own namespace and can no longer collide with anything.
    relocate("io.netty", "dev.sirius.cloud.libs.netty")
    relocate("com.google.gson", "dev.sirius.cloud.libs.gson")

    mergeServiceFiles()
    minimize {
        // Both of these are reached reflectively at runtime, so the reachability
        // analysis behind `minimize` cannot see the classes they need: Netty
        // resolves pipeline handlers by name, and Gson builds adapters from the
        // model classes it is handed. Stripping either breaks only at runtime.
        exclude(dependency("io.netty:.*:.*"))
        exclude(dependency("com.google.code.gson:.*:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
