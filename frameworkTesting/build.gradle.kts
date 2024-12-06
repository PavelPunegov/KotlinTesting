import org.jetbrains.kotlin.konan.target.KonanTarget

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

kotlin {
    iosSimulatorArm64 {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    optIn.addAll(
                        "kotlinx.cinterop.ExperimentalForeignApi",
                        "kotlinx.cinterop.BetaInteropApi"
                    )

                    // Generate K/N test runner for kotlin.test @Test support
                    freeCompilerArgs.add("-tr")
                }
            }
        }

        binaries {
            // Export tests as a framework
            framework("KotlinTests") {
                linkerOpts = mutableListOf(
                    "-F", KonanTarget.IOS_SIMULATOR_ARM64.devFrameworksPathProvider().get()
                )
                binaryOption("bundleId", "KotlinTests")
                baseName = "KotlinTests"
                isStatic = true
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(project(":kotlin-test-native-xctest"))
        }
    }
}
