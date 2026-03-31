rootProject.name = "market-state-service"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(":application")
include(":infrastructure:app")
include(":infrastructure:kafka-adapter")
include(":infrastructure:binance-snapshot-adapter")
