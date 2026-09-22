rootProject.name = "SiriusCloud"

include("cloud-api")
include("cloud-protocol")
include("cloud-driver")
include("cloud-node")
include("cloud-wrapper")
include("cloud-plugins:paper")
include("cloud-plugins:velocity")
include("cloud-modules:rest")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
