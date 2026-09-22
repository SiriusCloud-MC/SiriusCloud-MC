plugins {
    java
    `java-library`
}

dependencies {
    api(project(":cloud-api"))
    api(libs.bundles.netty)
    api(libs.gson)
}
