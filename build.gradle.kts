plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    id("xcode-conventions") apply false
}

repositories {
    mavenCentral()
}