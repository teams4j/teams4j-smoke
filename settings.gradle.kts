rootProject.name = "teams4j-smoke"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    // From gradle.properties; -PkotlinVersion=<baseline> runs :kotlin-cli on the lowest supported Kotlin.
    plugins {
        kotlin("jvm") version (extra["kotlinVersion"] as String)
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
include(":bot-cli")
