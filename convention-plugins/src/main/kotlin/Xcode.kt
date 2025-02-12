/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import org.jetbrains.kotlin.konan.KonanExternalToolFailure
import org.jetbrains.kotlin.konan.MissingXcodeException
import org.jetbrains.kotlin.konan.exec.Command
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.target.Xcode
import org.jetbrains.kotlin.konan.target.XcodeVersion

internal class ExtendedXcode : Xcode {

    override val toolchain by lazy {
        val ldPath = xcrun("-f", "ld") // = $toolchain/usr/bin/ld
        File(ldPath).parentFile.parentFile.absolutePath
    }

    override val additionalTools: String by lazy {
        val bitcodeBuildToolPath = xcrun("-f", "bitcode-build-tool")
        File(bitcodeBuildToolPath).parentFile.parentFile.absolutePath
    }

    override val simulatorRuntimes: String by lazy {
        Command("/usr/bin/xcrun", "simctl", "list", "runtimes", "-j").getOutputLines().joinToString(separator = "\n")
    }
    override val macosxSdk by lazy { getSdkPath("macosx") }
    override val iphoneosSdk by lazy { getSdkPath("iphoneos") }
    override val iphonesimulatorSdk by lazy { getSdkPath("iphonesimulator") }
    override val appletvosSdk by lazy { getSdkPath("appletvos") }
    override val appletvsimulatorSdk by lazy { getSdkPath("appletvsimulator") }
    override val watchosSdk: String by lazy { getSdkPath("watchos") }
    override val watchsimulatorSdk: String by lazy { getSdkPath("watchsimulator") }

    /* override */ val macosxSdkPlatform by lazy { getSdkPlatformPath("macosx") }
    /* override */ val iphoneosSdkPlatform by lazy { getSdkPlatformPath("iphoneos") }
    /* override */ val iphonesimulatorSdkPlatform by lazy { getSdkPlatformPath("iphonesimulator") }
    /* override */ val appletvosSdkPlatform by lazy { getSdkPlatformPath("appletvos") }
    /* override */ val appletvsimulatorSdkPlatform by lazy { getSdkPlatformPath("appletvsimulator") }
    /* override */ val watchosSdkPlatform: String by lazy { getSdkPlatformPath("watchos") }
    /* override */ val watchsimulatorSdkPlatform: String by lazy { getSdkPlatformPath("watchsimulator") }

    internal val xcodebuildVersion: XcodeVersion
        get() = xcrun("xcodebuild", "-version")
            .removePrefix("Xcode ")
            .parseXcodeVersion()

    internal val bundleVersion: XcodeVersion
        get() = bash("""/usr/libexec/PlistBuddy "$(xcode-select -print-path)/../Info.plist" -c "Print :CFBundleShortVersionString"""")
            .parseXcodeVersion()

    override val version by lazy {
        try {
            bundleVersion
        } catch (e: KonanExternalToolFailure) {
            xcodebuildVersion
        }
    }

    private fun xcrun(vararg args: String): String = try {
        Command("/usr/bin/xcrun", *args).getOutputLines().first()
    } catch (e: KonanExternalToolFailure) {
        // TODO: we should make the message below even more clear and actionable.
        //  Maybe add a link to the documentation.
        //  See https://youtrack.jetbrains.com/issue/KT-50923.
        val message = """
                An error occurred during an xcrun execution. Make sure that Xcode and its command line tools are properly installed.
                Failed command: /usr/bin/xcrun ${args.joinToString(" ")}
                Try running this command in Terminal and fix the errors by making Xcode (and its command line tools) configuration correct.
            """.trimIndent()
        throw MissingXcodeException(message, e)
    }

    private fun bash(command: String): String = Command("/bin/bash", "-c", command).getOutputLines().joinToString("\n")

    private fun getSdkPath(sdk: String) = xcrun("--sdk", sdk, "--show-sdk-path")

    private fun getSdkPlatformPath(sdk: String) = xcrun("--sdk", sdk, "--show-sdk-platform-path")

    private fun String.parseXcodeVersion(): XcodeVersion {
        return XcodeVersion.parse(this) ?: throw MissingXcodeException("Couldn't parse Xcode version from '$this'")
    }
}