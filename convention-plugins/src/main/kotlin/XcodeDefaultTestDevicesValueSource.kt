/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import javax.inject.Inject

abstract class XcodeDefaultTestDevicesValueSource : ValueSource<Map<Family, String>, ValueSourceParameters.None> {

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): Map<Family, String> {
        if (!HostManager.hostIsMac) {
            return emptyMap()
        }

        val osRegex = "-- .* --".toRegex()
        val deviceRegex = """[0-9A-F]{8}-([0-9A-F]{4}-){3}[0-9A-F]{12}""".toRegex()

        val output = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(listOf("/usr/bin/xcrun", "simctl", "list", "devices", "available"))
            standardOutput = output
        }
        val out = String(output.toByteArray(), Charset.defaultCharset())

        val result = mutableMapOf<Family, String>()
        var os: Family? = null
        out.lines().forEach { s ->
            val osFound = osRegex.find(s)?.value
            if (osFound != null) {
                val osName = osFound.split(" ")[1]
                os = try {
                    Family.valueOf(osName.toUpperCaseAsciiOnly())
                } catch (e: Exception) {
                    null
                }
            } else {
                val currentOs = os
                if (currentOs != null) {
                    val deviceFound = deviceRegex.find(s)?.value
                    if (deviceFound != null) {
                        result[currentOs] = deviceFound
                        os = null
                    }
                }
            }
        }

        return result
    }

    private fun String.toUpperCaseAsciiOnly(): String {
        val builder = StringBuilder(length)
        for (c in this) {
            builder.append(if (c in 'a'..'z') c.uppercaseChar() else c)
        }
        return builder.toString()
    }
}