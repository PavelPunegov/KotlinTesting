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
 * Registers a task to copy the XCTest framework to the build directory for the specified [KonanTarget].
 *
 * @param target The [KonanTarget] for which the copy framework task should be registered.
 * @return The [TaskProvider] representing the registered copy framework task.
 */
fun registerCopyFrameworkTask(target: KonanTarget): TaskProvider<Sync> =
    tasks.register<Sync>("${target}FrameworkCopy") {
        into(layout.buildDirectory.dir("$target/Frameworks"))
        from(
            providers.of(DevFrameworkPathValueSource::class) {
                parameters {
                    konanTarget = target
                }
            }
        ) {
            include("XCTest.framework/**")
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
            val copyTask = registerCopyFrameworkTask(it.konanTarget)
            it.compilations.all {
                cinterops {
                    register("XCTest") {
                        compilerOpts("-iframework", copyTask.map { it.destinationDir }.get().absolutePath)
                        // cinterop task should depend on the framework copy task
                        tasks.named(interopProcessingTaskName).configure {
                            dependsOn(copyTask)
                        }
                    }
                }
                compileTaskProvider.configure {
                    compilerOptions {
                        freeCompilerArgs.add("-Xdont-warn-on-error-suppression")
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
