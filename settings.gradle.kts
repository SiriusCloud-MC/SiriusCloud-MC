rootProject.name = "SiriusCloud"

include("cloud-api")
include("cloud-protocol")
include("cloud-driver")
include("cloud-node")
include("cloud-wrapper")
include("cloud-plugins:paper")
include("cloud-plugins:velocity")
include("cloud-plugins:permissions")
include("cloud-modules:rest")
include("cloud-modules:notify")
include("cloud-modules:permissions")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
