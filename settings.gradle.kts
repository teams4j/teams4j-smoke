rootProject.name = "teams4j-smoke"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // Published artifacts only, never an included build: the POM is part of what is tested.
        mavenLocal()
        mavenCentral()
    }
}

include(":java-cli")
include(":kotlin-cli")
include(":boot-app")
