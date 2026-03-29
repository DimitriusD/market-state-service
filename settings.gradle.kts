rootProject.name = "hexagonal-service-template"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(":application")
include(":infrastructure:app")
include(":infrastructure:rest-api")
include(":infrastructure:jdbc-storage-adapter")
include(":infrastructure:event-adapter")
