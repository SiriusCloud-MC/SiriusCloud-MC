plugins {
    java
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":cloud-api"))
    implementation(project(":cloud-protocol"))
    implementation(project(":cloud-driver"))

    compileOnly(libs.paper.api)

    // LuckPerms is an optional integration: present at compile time for the
    // messenger, absent at runtime on servers that do not run it. Every touch
    // of it is guarded, so the plugin works either way.
    compileOnly(libs.luckperms.api)
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
        // This plugin is what provides cloud-api to every other cloud plugin
        // on the server: Bukkit shares classes between plugins, and a separate
        // plugin like the permissions one compiles against the API without
        // shading its own copy, precisely so that CloudDriver.instance()
        // returns something it can cast. Minimising the API to what this
        // plugin happens to use strips the rest, and the other plugin fails at
        // runtime with a NoClassDefFoundError for a type it can see at compile
        // time. The whole public contract has to ship.
        exclude(project(":cloud-api"))

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
