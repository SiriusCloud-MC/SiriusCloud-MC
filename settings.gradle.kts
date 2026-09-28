rootProject.name = "SiriusCloud"

include("cloud-api")
include("cloud-protocol")
include("cloud-driver")
include("cloud-node")
include("cloud-wrapper")
include("cloud-plugins:paper")
include("cloud-plugins:velocity")
include("cloud-plugins:permissions")
include("cloud-plugins:lobby")
include("cloud-modules:rest")
include("cloud-modules:notify")
include("cloud-modules:permissions")
include("cloud-modules:common")
include("cloud-modules:display")
include("cloud-modules:social")
include("cloud-modules:moderation")
include("cloud-modules:matchmaking")
include("cloud-modules:metrics")
include("cloud-modules:discord")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
