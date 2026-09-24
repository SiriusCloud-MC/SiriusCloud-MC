import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    java
    alias(libs.plugins.shadow)
}

/**
 * Velocity 4's API is compiled for Java 25, so reading it requires a Java 25
 * compiler - hence the toolchain override. Gradle finds the toolchain itself;
 * a Java 25 JDK must be installed, which is already required to run any modern
 * Minecraft server.
 *
 * Our own classes still target 21, and the two below are why that needs saying
 * out loud:
 *
 *  - Gradle matches dependency variants on JVM version, so a 21 target would
 *    make the Velocity 4 artifact simply unresolvable. The attribute override
 *    says "this classpath may contain Java 25 artifacts" without claiming our
 *    output is one.
 *  - The shading step rewrites our bytecode, and its bytecode library does not
 *    read class file version 69 yet.
 *
 * Targeting 21 is correct regardless: the plugin only has to *run* on the
 * proxy's Java 25, not be built for it.
 */
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

listOf(configurations.compileClasspath, configurations.annotationProcessor).forEach { configuration ->
    configuration.configure {
        attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }
}

dependencies {
    implementation(project(":cloud-api"))
    implementation(project(":cloud-protocol"))
    implementation(project(":cloud-driver"))

    compileOnly(libs.velocity.api)

    // Optional at runtime; see the Paper plugin's build file.
    compileOnly(libs.luckperms.api)
    // Generates velocity-plugin.json from the @Plugin annotation.
    annotationProcessor(libs.velocity.api)
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("cloud-plugin-velocity")

    // Velocity ships its own Netty and Gson. A second copy of either on that
    // classpath is a reliable NoSuchMethodError at runtime, so both move into
    // our namespace where they cannot collide.
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

        // Both are reached reflectively, so reachability analysis cannot see
        // what they need: Netty resolves handlers by name, Gson builds
        // adapters from the model classes it is handed.
        exclude(dependency("io.netty:.*:.*"))
        exclude(dependency("com.google.code.gson:.*:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
