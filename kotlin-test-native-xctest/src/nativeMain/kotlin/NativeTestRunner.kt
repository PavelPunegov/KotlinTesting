/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

import kotlinx.cinterop.*
import kotlin.native.internal.test.*
import platform.Foundation.*
import platform.Foundation.NSError
import platform.Foundation.NSInvocation
import platform.Foundation.NSString
import platform.Foundation.NSMethodSignature
import platform.UniformTypeIdentifiers.UTTypeSourceCode
import platform.XCTest.*
import platform.objc.*

/**
 * An XCTest equivalent of the K/N TestCase.
 *
 * Wraps the [TestCase] that runs it with a special bridge method created by adding it to a class.
 * The idea is to make XCTest invoke them by the created invocation and show the selector as a test name.
 * This selector is created as `class.method` that is than naturally represented in XCTest reports including XCode.
 */
@ExportObjCClass(name = "KotlinNativeTest")
class XCTestCaseWrapper(invocation: NSInvocation, val testCase: TestCase) : XCTestCase(invocation) {
    // Sets XCTest to continue running after failure to match Kotlin Test
    override fun continueAfterFailure(): Boolean = true

    private val ignored = testCase.ignored || testCase.suite.ignored

    private val testName = testCase.fullName

    fun run() {
        check (!ignored) {
            // In general, to skip the test `XCTSkip()` should be used,
            // but it is not possible due to the KT-43719 and not implemented exception importing.
            // To overcome this issue, we use a native `skipImplementation` method that returns an implementation
            // that invokes `XCTSkip()`
            """
                Test $testName should be ignored and not run with this method.
                The test machinery failed to replace runner with the special method.
                Debug info:
                - selector: ${invocation?.selector}
                - signature: ${invocation?.methodSignature}
            """.trimIndent()
        }
        try {
            testCase.doRun()
        } catch (throwable: Throwable) {
            val stackTrace = throwable.getStackTrace()
            val failedStackLine = stackTrace.first {
                // try to filter out kotlin.Exceptions and kotlin.test.Assertion inits
                // to point to the failed stack and line
                !it.contains("kfun:kotlin.")
            }
            // Find path and line number to create a source location
            val matchResult = Regex("^\\d+ +.* \\((.*):(\\d+):.*\\)$").find(failedStackLine)
            val sourceLocation = if (matchResult != null) {
                val (file, line) = matchResult.destructured
                XCTSourceCodeLocation(file, line.toLong())
            } else {
                // No debug info to get the path. Still have to record location
                XCTSourceCodeLocation(testCase.suite.name, 0L)
            }

            // Make stack trace attachment, encoding it as source code.
            // This makes it appear as an attachment in the XCode test results for the failed test.
            @Suppress("CAST_NEVER_SUCCEEDS")
            val stackAsPayload = (stackTrace.joinToString("\n") as? NSString)?.dataUsingEncoding(NSUTF8StringEncoding)
            val stackTraceAttachment = XCTAttachment.attachmentWithUniformTypeIdentifier(
                identifier = UTTypeSourceCode.identifier,
                name = "Kotlin stacktrace (full)",
                payload = stackAsPayload,
                userInfo = null
            )

            val type = when (throwable) {
                is AssertionError -> XCTIssueTypeAssertionFailure
                else -> XCTIssueTypeUncaughtException
            }

            // Finally, create and record an issue with all gathered data
            recordIssue(
                XCTIssue(
                    type = type,
                    compactDescription = throwable.toString(),
                    detailedDescription = buildString {
                        append("Test '${this@XCTestCaseWrapper.testName}'")
                        appendLine("from '${this@XCTestCaseWrapper.testCase.suite.name}' failed with $throwable")
                        throwable.cause?.let { appendLine("(caused by ${throwable.cause})") }
                    },
                    sourceCodeContext = XCTSourceCodeContext(
                        callStackAddresses = throwable.getStackTraceAddresses(),
                        location = sourceLocation
                    ),
                    // pass the error through the XCTest to the NativeTestObserver
                    associatedError = NSErrorWithKotlinException(throwable),
                    attachments = listOf(stackTraceAttachment)
                )
            )
        }
    }

    override fun setUp() {
        if (!ignored) testCase.doBefore()
    }

    override fun tearDown() {
        if (!ignored) testCase.doAfter()
    }

    override fun description(): String = buildString {
        append(testName)
        if (ignored) append("(ignored)")
    }

    override fun name() = testName

    companion object : XCTestCaseMeta() {
        /**
         * This method is invoked by the XCTest when it discovered XCTestCase instance
         * that contains test method.
         *
         * This method should not be called with the current idea and assumptions.
         */
        override fun testCaseWithInvocation(invocation: NSInvocation?): XCTestCase {
            error(
                """
                This should not happen by default.
                Got invocation: ${invocation?.description}
                with selector @sel(${NSStringFromSelector(invocation?.selector)})
                """.trimIndent()
            )
        }

        /**
         * Creates and adds a method to the metaclass with an implementation block
         * that gets an [XCTestCase] instance as self to be run.
         */
        private fun createRunMethod(selector: SEL, isIgnored: Boolean = false) {
            val imp = if (isIgnored) {
                // A special implementation that makes current test execution to skip a test case
                skipImplementation()
            } else {
                imp_implementationWithBlock(this::runner)
            }

            val result = class_addMethod(
                cls = this.`class`(),
                name = selector,
                imp = imp,
                types = "v@:" // Obj-C type encodings: v (returns void), @ (id self), : (SEL sel)
            )
            check(result) {
                "Internal error: was unable to add method with selector $selector"
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun runner(testCaseWrapper: XCTestCaseWrapper, sel: SEL) = testCaseWrapper.run()

        /**
         * Creates Test invocations for each test method to make them resolvable by the XCTest machinery.
         *
         * For each kotlin-test's test case make an [NSInvocation] with an appropriate selector
         * that represents test name:
         * - Create [SEL] selector from the given test name.
         * - Create implementation method with block for runner method.
         *   This method accepts the instance of the [XCTestCaseWrapper] to run the actual test code.
         * - Create [NSInvocation] from the selector using [NSMethodSignature].
         *
         * Then this [NSInvocation] should be used to create an instance of [XCTestCaseWrapper]
         * that implements [XCTestCase].
         * When XCTest runs this instance, it invokes this invocation
         * that passes Wrapper's instance to the `runner(...)` method.
         *
         * @see createRunMethod
         * @see runner
         * @see XCTestCaseWrapper.run
         */
        override fun testInvocations(): List<NSInvocation> = testCases.map {
            val name = it.fullName
            val selector = NSSelectorFromString(name)

            createRunMethod(selector, isIgnored = it.ignored)

            this.instanceMethodSignatureForSelector(selector)?.let { signature ->
                @Suppress("CAST_NEVER_SUCCEEDS")
                val invocation = NSInvocation.invocationWithMethodSignature(signature as NSMethodSignature)
                invocation.setSelector(selector)
                invocation
            } ?: error("Was unable to create NSInvocation for method $name")
        }
    }
}

private typealias SEL = COpaquePointer?

/**
 * This is a NSError-wrapper of Kotlin exception used to pass it through the [XCTIssue]
 * to the [XCTestObservation] protocol implementation [NativeTestObserver].
 * See [NativeTestObserver.testCase] for the usage.
 */
internal class NSErrorWithKotlinException(val kotlinException: Throwable) :
    NSError(NSCocoaErrorDomain, NSValidationErrorMinimum, null)

/**
 * XCTest equivalent of K/N TestSuite.
 */
class XCTestSuiteWrapper(val testSuite: TestSuite) : XCTestSuite(testSuite.name) {
    private val ignoredSuite: Boolean
        get() = testSuite.ignored || testSuite.testCases.all { it.value.ignored }

    override fun setUp() {
        if (!ignoredSuite) testSuite.doBeforeClass()
    }

    override fun tearDown() {
        if (!ignoredSuite) testSuite.doAfterClass()
    }
}
