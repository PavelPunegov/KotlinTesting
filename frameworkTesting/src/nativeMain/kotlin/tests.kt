/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
import kotlinx.cinterop.*
import platform.Foundation.*
import kotlin.test.*
import platform.UIKit.UIApplication

@Test
fun ensureUIApplication() {
    // Should pass the test if run properly with UI available
    assertNotNull(UIApplication.sharedApplication)
}

@Test
fun simpleTest() {
    assertEquals(1, 1)
}

@Test
fun testThatFails() {
    assertEquals(1, 2, "Unexpected result and failure")
}

@Test
@Ignore
fun ignoredTest() {
    error("This test should be ignored")
}


@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Test
fun request() {
    val response = memScoped {
        val request = NSURLRequest(NSURL(string = "https://cache-redirector.jetbrains.com/"))
        val responseRef = alloc<ObjCObjectVar<NSURLResponse?>>()
        val errorRef = alloc<ObjCObjectVar<NSError?>>()

        NSURLConnection.sendSynchronousRequest(request, responseRef.ptr, errorRef.ptr)
            ?: throw Error(errorRef.value?.toString() ?: "")

        responseRef.value!! as NSHTTPURLResponse
    }

    assertEquals(200, response.statusCode)
}
