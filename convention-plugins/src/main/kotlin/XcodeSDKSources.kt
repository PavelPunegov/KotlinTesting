/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

abstract class XcodeSDKSource {
    private val xcode = ExtendedXcode()

    protected fun KonanTarget.sdkPlatform(): String {
        check(HostManager.hostIsMac) {
            "Getting path to Xcode SDK is only applicable to macOS hosts."
        }

        return when (this) {
            KonanTarget.MACOS_ARM64, KonanTarget.MACOS_X64 -> xcode.macosxSdkPlatform
            KonanTarget.IOS_SIMULATOR_ARM64, KonanTarget.IOS_X64 -> xcode.iphonesimulatorSdkPlatform
            KonanTarget.IOS_ARM64 -> xcode.iphoneosSdkPlatform
            else -> error("Target $this is not supported")
        }
    }
}

/**
 * Values source that provides a path to Xcode Developer Frameworks
 */
abstract class DevFrameworkPathValueSource : ValueSource<String, DevFrameworkPathValueSource.Parameters>,
        XcodeSDKSource() {
    interface Parameters : ValueSourceParameters {
        val konanTarget: Property<KonanTarget>
    }

    override fun obtain(): String {
        val devFrameworkPath = "${parameters.konanTarget.get().sdkPlatform()}/Developer/Library/Frameworks/"
        check(File(devFrameworkPath).exists()) {
            "Developer frameworks path wasn't found at $devFrameworkPath. Check configuration and Xcode installation"
        }
        return devFrameworkPath
    }

}

abstract class XCTestPathValueSource : ValueSource <String, XCTestPathValueSource.Parameters>, XcodeSDKSource() {
    interface Parameters : ValueSourceParameters {
        val konanTarget: Property<KonanTarget>
    }

    override fun obtain(): String {
        val xcTestPath = parameters.konanTarget.get().sdkPlatform() + "/Developer/Library/Xcode/Agents/xctest"
        check(File(xcTestPath).exists()) {
            "XCTest path wasn't found at $xcTestPath. Check configuration and Xcode installation"

        }
        return xcTestPath
    }
}
