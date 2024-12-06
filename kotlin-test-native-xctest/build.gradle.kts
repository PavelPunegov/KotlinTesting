import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.*

description = "XCTest wrapper of Native kotlin.test"

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("xcode-conventions")
}

repositories {
    mavenCentral()
}

/**
 * Returns [Provider] with the path to Xcode Developers frameworks for the specified [KonanTarget].
 */
fun KonanTarget.devFrameworksPathProvider(): Provider<String> =
    providers.of(DevFrameworkPathValueSource::class) {
        parameters {
            konanTarget = this@devFrameworksPathProvider
        }
    }

// region Kotlin Multiplatform build configuration

val nativeTargets = mutableListOf<KotlinNativeTarget>()

val hostManager = HostManager()
fun MutableList<KotlinNativeTarget>.addIfEnabledOnHost(target: KotlinNativeTarget) {
    if (hostManager.isEnabled(target.konanTarget)) add(target)
}

kotlin {
    with(nativeTargets) {
        addIfEnabledOnHost(macosX64())
        addIfEnabledOnHost(macosArm64())
        addIfEnabledOnHost(iosX64())
        addIfEnabledOnHost(iosArm64())
        addIfEnabledOnHost(iosSimulatorArm64())

        forEach {
            it.compilations.all {
                cinterops {
                    register("XCTest") {
                        compilerOpts("-iframework", it.konanTarget.devFrameworksPathProvider().get())
                    }
                }
            }
        }
    }
    sourceSets.all {
        languageSettings.apply {
            // Oh, yeah! So much experimental, so wow!
            optIn("kotlinx.cinterop.BetaInteropApi")
            optIn("kotlinx.cinterop.ExperimentalForeignApi")
            optIn("kotlin.experimental.ExperimentalNativeApi")
        }
    }
}

// endregion
