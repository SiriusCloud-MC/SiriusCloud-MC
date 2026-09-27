/**
 * Helpers shared by the bundled modules: config files, durations, commands,
 * player lookup.
 *
 * <p>Bundled into each module jar rather than loaded once. Every module has its
 * own class loader, so a copy per module is isolated from the others and can
 * never be the wrong version for the module it ships in.
 */
plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":cloud-api"))
    compileOnly(libs.gson)
}
