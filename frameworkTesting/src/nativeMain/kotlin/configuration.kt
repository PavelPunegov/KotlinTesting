/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
import kotlinx.cinterop.ExperimentalForeignApi
import platform.XCTest.XCTestSuite

// TODO: create a configuration setup procedure with test selection and reporting
@OptIn(ExperimentalForeignApi::class)
fun testSuite(): XCTestSuite = setupXCTestSuite()