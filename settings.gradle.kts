rootProject.name = "SiriusCloud"

include("cloud-api")
include("cloud-protocol")
include("cloud-driver")
include("cloud-node")
include("cloud-wrapper")
include("cloud-plugins:paper")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
