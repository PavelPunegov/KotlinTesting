pluginManagement {
    includeBuild("convention-plugins")

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "KotlinTesting"

include("kotlin-test-native-xctest")
include("frameworkTesting")
