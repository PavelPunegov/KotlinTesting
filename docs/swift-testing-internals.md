# Swift Testing internals: bundles, discovery, execution and XCTest interop

*Companion to the [framework comparison](testing-frameworks-comparison.md) and the [development plan](development-plan.md). State as of October 2026: Swift 6.4, Xcode 27, swift-corelibs-xctest 6.4.*

**How to read this document.** Statements are tagged by confidence:

* Untagged statements are confirmed by public sources: swift-evolution proposals, swift-testing release notes and PRs, Swift Forums posts by the maintainers, Apple release notes, and stack traces quoted on the Apple Developer Forums.
* **[inferred]** marks behaviour reconstructed from symbol names, PR titles or observed behaviour. It is not documented. This applies mainly to the proprietary Xcode XCTest.
* **[unverified detail]** marks exact names or layouts recalled from the source code but not re-checked against the current revision. Check them in `swiftlang/swift-testing` before relying on them in code.

---

## 1. Short answer

| Question | XCTest | Swift Testing |
|---|---|---|
| What is the test product? | A `.xctest` bundle on Apple platforms, a `.xctest`‑named executable on Linux | **The same product.** Swift Testing has no bundle format of its own. It reuses the XCTest bundle in Xcode and SwiftPM on Darwin, and the same test executable on Linux and Windows. |
| How are tests found? | **At run time, through the Objective‑C runtime.** `objc_copyClassList` returns all classes; XCTest keeps the `XCTestCase` subclasses; each class lists its zero‑argument `test…` methods as `NSInvocation`s. On Linux, there is no ObjC runtime, so SwiftPM generates `allTests` lists from the index store at build time. | **At compile time, with metadata the runtime reads back.** The `@Test`/`@Suite` macros emit *test content records* into a dedicated linker section (`__DATA_CONST,__swift5_tests` on Mach‑O). At run time, the library walks the loaded images, reads that section and calls each record's accessor to get a `Test` value. No ObjC runtime, no reflection and no naming conventions are involved. |
| Who starts the run? | `_XCTestMain` → `XCTestDriver` inside the test host. The host is the `xctest` tool, an app, or XCTRunner for UI tests. | A runner calls the library's entry point. Tools use the JSON ABI entry point `swt_abiv0_getEntryPoint()`, or `ABI.v0.entryPoint` through SPI. SwiftPM uses `__swiftPMEntryPoint()`. In Xcode, the XCTest‑side runner in the same host process makes the call **[inferred]**. |
| Parallelism | Across processes: simulator clones or several runner processes, one test at a time per process | Inside one process: Swift concurrency tasks, parallel by default. This also works on physical devices, because no clones are needed. |
| Integration | — | Both frameworks share one bundle and one host process. Results flow back into the same `.xcresult`. Since Swift 6.4 / Xcode 27, assertion failures cross between frameworks through a *fallback event handler* in a tiny shared library, `_TestingInterop` (ST‑0021). |

---

## 2. Test products: Swift Testing reuses XCTest's packaging

### 2.1 Xcode

* A test target has the same product type whether it contains XCTest or Swift Testing tests. The type is `com.apple.product-type.bundle.unit-test`, and the output is a `.xctest` bundle. Since Xcode 16, the *Unit Testing Bundle* template defaults to Swift Testing. One bundle can mix both frameworks, which allows incremental migration.
* The bundle links `Testing.framework` dynamically. On devices and simulators it loads from the platform's developer frameworks, for example `/System/Developer/Library/Frameworks/Testing.framework`. That directory also holds XCTest. A missing framework is reported as a bundle load failure.
* At compile time, the `@Test`/`@Suite`/`#expect` macros expand through the `TestingMacros` compiler plugin. That plugin ships with the toolchain **[unverified detail: `usr/lib/swift/host/plugins/testing/libTestingMacros.dylib`]**.
* Nothing in the bundle's `Info.plist` lists tests for either framework. XCTest finds tests through the ObjC runtime. Swift Testing finds tests in a binary section of the bundle's Mach‑O image.

### 2.2 SwiftPM on macOS

* `swift build --build-tests` produces `<Package>PackageTests.xctest`, which is again a bundle.
* A bundle cannot be executed directly, so `swift test` runs each framework in a separate pass:
  * XCTest runs under `xcrun xctest <bundle>`.
  * Swift Testing runs under **`swiftpm-testing-helper`**:

    ```
    swiftpm-testing-helper --test-bundle-path <Pkg>PackageTests.xctest/Contents/MacOS/<Pkg>PackageTests \
                           --testing-library swift-testing [--filter …]
    ```

    The helper `dlopen`s the bundle executable and calls Swift Testing's entry point.
* Flags `--enable/--disable-swift-testing` and `--enable/--disable-xctest` select the passes.

### 2.3 SwiftPM on Linux, Windows and FreeBSD

* The test product is a **real executable**. SwiftPM generates its `main`.
* `swift test` **invokes the binary twice**:
  1. The first run calls `XCTMain(...)` with the generated `allTests` lists.
  2. The second run passes `--testing-library swift-testing`, and the generated `main` calls Swift Testing's entry point instead.

  The swift-testing debugging guide calls this the "double‑invocation gotcha". To debug Swift Testing under LLDB, run the binary yourself with `--testing-library swift-testing`.
* XCTest discovery on Linux is *static*. Without an ObjC runtime, SwiftPM reads the compiler's index store at build time and generates the `__allTests` manifests. This is automatic since Swift 5.4; earlier versions used `--enable-test-discovery` or a hand‑written `LinuxMain.swift`. Swift Testing needs none of this, because its discovery works the same way on every platform.

### 2.4 Other build systems

CMake and Bazel need no bundle at all. Any executable or loadable image that contains test content records can be run by a small `main` that calls the ABI entry point. The swift-testing repository documents this for CMake, and the Swift Forums thread "Using Swift Testing with CMake" shows the pattern.

---

## 3. XCTest discovery: the Objective‑C runtime path

Backtraces from Xcode 7.3 and Xcode 15, quoted on the Apple Developer Forums, show the call chain, innermost call first:

```
objc_copyClassList
+[XCTestCase(RuntimeUtilities) _allSubclasses]
+[XCTestSuite suitesForBundlesIncludingEmptySuites:]      // groups classes by bundle
-[XCTestSuite _initWithTestConfiguration:]
+[XCTestSuite testSuiteForTestConfiguration:]
-[XCTTestRunSession initWithTestConfiguration:error:]
-[XCTestDriver _runTests]
_XCTestMain
```

* **Classes.** `objc_copyClassList` realizes *every* class in the process, not only the test classes. This side effect explains crashes "before any test runs" when some unrelated class cannot be realized. Setting `OBJC_PRINT_CLASS_SETUP=YES` shows which class fails.
* **Methods.** `+[XCTestCase testInvocations]` returns one `NSInvocation` for each instance method that starts with `test`, takes no arguments and returns void. Swift methods qualify because `XCTestCase` inherits from `NSObject`, so they are exposed to ObjC.
* **Override points:**
  * `+defaultTestSuite` builds the suite by hand. It became a class property in the iOS 11 / macOS 10.13 SDKs.
  * `+testInvocations` adds synthetic selectors. Quick uses it, and so does this repository's `XCTestCaseWrapper`.
  * `+testCaseWithInvocation:` creates an instance for a given selector.
* **Selection.** Xcode passes an `XCTestConfiguration` to the host, containing `XCTTestIdentifier`s that come from `-only-testing` or the test plan. `XCTestSuite` filters the discovered tree against it.
* **Principal class.** `NSPrincipalClass` in the bundle's `Info.plist` is instantiated when the bundle loads. It is not used for discovery. It is the place to register `XCTestObservation` observers or run global setup.

This is the mechanism `kotlin-test-native-xctest` relies on. It adds one method per Kotlin test to `KotlinNativeTest` with `class_addMethod` and returns matching `NSInvocation`s from `testInvocations`. The Swift host then grafts the resulting suite into `defaultTestSuite`.

---

## 4. Swift Testing discovery: compile‑time records in a linker section

### 4.1 What the macros emit

For every `@Test` function and every `@Suite` type, the macro expansion adds a static, constant‑initialized record placed in a named section:

```swift
// simplified shape of the expansion (names abbreviated)
#if objectFormat(MachO)
@_section("__DATA_CONST,__swift5_tests")
#elseif objectFormat(ELF) || objectFormat(Wasm)
@_section("swift5_tests")
#elseif objectFormat(COFF)
@_section(".sw5test$B")
#endif
@_used
private static let $s…__testContentRecord: Testing.__TestContentRecord = (
    kind: 0x74657374,          // FourCC 'test'
    reserved1: 0,
    accessor: { outValue, type, hint, reserved in … },   // C-convention thunk
    context: 0,                // e.g. flags such as "is suite"
    reserved2: 0
)
```

* `@_section` and `@_used` are the underscored forms of **SE‑0492 "Section Placement Control"**, which shipped in Swift 6.3 as `@section`/`@used`. `@used` keeps the record alive through dead‑code stripping. Static initialization guarantees that no code runs when the image loads.
* The record is a tuple, roughly `(kind: UInt32, reserved1: UInt32, accessor: AccessorFn?, context: UInt, reserved2: UInt)` **[unverified detail]**. The release‑notes printout confirms a `'test'` kind of `0x74657374` and a `context` field.
* Known kinds:
  * `'test'` covers tests and suites.
  * `'exit'` covers exit tests **[unverified detail: `0x65786974`]**.

  The kind field is a FourCC namespace. The maintainers designed the section so that *other* testing libraries can put their own records in it. They mentioned Quick as a candidate.
* The **accessor** is a C‑convention function. When asked for a matching type, it initializes a value at `outValue` and returns `true`. Its signature is roughly `(outValue: UnsafeMutableRawPointer, type: UnsafeRawPointer, hint: UnsafeRawPointer?, reserved: UInt) -> CBool` **[unverified detail]**.
  * For `'test'` records, the value is an async generator that produces a `Testing.Test`. Building a `Test` involves Swift types, traits and closures, so it is not cheap. It only happens when the runner asks for it.
  * For `'exit'` records, the `hint` carries the exit test's ID, so the child process can find exactly one closure without building the others.
* The specification lives in `Documentation/ABI/TestContent.md` in swift-testing. It is still being extended, most recently for Embedded Swift.

### 4.2 How the runtime reads the records

* The `_TestDiscovery` module (in the swift-testing repo) enumerates every loaded image and finds the section bounds in each one:
  * Darwin uses dyld image enumeration.
  * ELF platforms use `dl_iterate_phdr` or the runtime's metadata section registry.
  * Windows looks up the COFF `.sw5test` section directly. A swiftlang/swift issue notes this is done without `swift_enumerateAllMetadataSections()`.

  For statically linked binaries, it looks in exactly one section. Recent PRs record the image base address so that records can be printed and traced.
* Every image that is loaded is scanned: executables, `.xctest` bundles, dylibs and frameworks. Discovery is therefore **independent of the product type**. A test compiled into a framework that the test bundle links is discovered too.
* The library calls each `'test'` accessor, awaits the generators, and builds a `Runner.Plan`:
  * It groups tests under their suites by type name.
  * It applies traits, such as conditions, `.serialized`, tags and time limits.
  * It applies the configuration filter, which comes from `--filter`/`--skip`, Xcode identifiers or tags.
  * It expands parameterized tests into test cases at run time, from their argument collections.

### 4.3 Legacy discovery (Swift 6.0 and still compiled as a fallback)

* Before the section existed, the macros generated a type with a deliberately unusual name. The maintainer's example was `…__🟠$test_container__`. The type conformed to the hidden protocol `__TestContainer` and had a `static var __tests: [Test] { get async }`.
* At run time, the library scanned Swift's **type metadata / protocol conformance section** (`__swift5_types`) for such types. The maintainers said this was a stop‑gap. They prefixed the related public symbols with `__` to discourage anyone from depending on them.
* Legacy scanning is still compiled in, except where `SWT_NO_LEGACY_TEST_DISCOVERY` is defined, which is the case for Embedded Swift. Test content records are the primary path since the change "Discover test content stored in the test content section of loaded images" (swift-testing #893).

### 4.4 Identity of a test

* `Test.ID` is built from the module name, the type path and the function name with argument labels, for example `MyTests.Suite.add(_:to:)`. Free functions get a source‑location component.
* Xcode identifiers keep the same `Target/Suite/function()` shape as XCTest but **end in parentheses**. `-only-testing` and `-skip-testing` accept them since Xcode 16.3, which also added `-only-testing-tags` and `-skip-testing-tags`. Before 16.3, users needed workarounds such as `foo()()`.
* `xcodebuild test -enumerate-tests` lists both frameworks in the same tree.
* Test cases of a parameterized test can be re‑run individually only when their arguments can be encoded. Encodable arguments are `Codable`, `RawRepresentable`, `Identifiable` or `CustomTestArgumentEncodable`.

### 4.5 Static (source‑level) discovery for IDEs

Before anything runs, tests are found from source:

* **SourceKit‑LSP**, used by VS Code and other editors, answers `workspace/tests` and `textDocument/tests`. These are being renamed to `sourcekit/…`. It combines a fast **SwiftSyntax scan** for `@Test`/`@Suite` and `XCTestCase` methods with the **semantic index** (IndexStoreDB), which confirms things like inheritance from `XCTestCase`. Recent releases prefer syntactic results over semantic ones (sourcekit-lsp #2500).
* **Xcode's test navigator** fills in as soon as a project is opened, which suggests the same kind of index‑ or syntax‑based discovery **[inferred]**. Parameterized test cases appear only after a run.

---

## 5. Execution paths

### 5.1 The tool‑integration ABI (all hosts)

* `ABI.v0.entryPoint` is SPI (`@_spi(ForToolsIntegrationOnly)`). It is also exported as the C symbol **`swt_abiv0_getEntryPoint()`**, which tools find with `dlsym`. The function returns a pointer that the caller casts to:

  ```swift
  @convention(thin) @Sendable (
      _ configurationJSON: UnsafeRawBufferPointer?,
      _ recordHandler: @escaping @Sendable (_ recordJSON: UnsafeRawBufferPointer) -> Void
  ) async throws -> Bool          // true = all tests passed
  ```

* The configuration JSON carries filters, tags, parallelism and `listTests`, among other settings. Results stream back as JSON records, one per event or test, following the schema in `Documentation/ABI/JSON.md`. The schema is versioned. ST‑0019 adds tags, bugs and time limits, and a 2026 pitch adds issue fields. `swift test --event-stream-output-path` and VS Code consume the same stream.
* The older `swt_copyABIEntryPoint_v0()` was removed in Swift 6.3 (swift-testing #1240). New exported symbols use the `swift_testing_` prefix.

### 5.2 SwiftPM

`swift test` → `swiftpm-testing-helper` on Darwin, or the generated `main` elsewhere → `__swiftPMEntryPoint()`. That function parses the CLI flags, such as `--filter`, `--skip`, `--xunit-output`, `--event-stream-output-path`, `--maximum-repetitions` and `--repeat-until`. It then runs the same `Runner` that the ABI entry point uses.

### 5.3 xcodebuild / Xcode

1. `xcodebuild test` (or Xcode) asks `testmanagerd` to launch the **test host**. The host is the `xctest` agent for logic tests, the app for hosted tests, or XCTRunner for UI tests. Xcode then sends the host an `XCTestConfiguration` with the selected test identifiers.
2. The host loads the `.xctest` bundle. `_XCTestMain` starts the XCTest driver, which discovers `XCTestCase` classes through the ObjC runtime (§3).
3. In the same process, XCTest hands the Swift Testing identifiers from the configuration (the ones ending in `()`) to Swift Testing. Swift Testing reads its records from the section (§4) and runs the tests as concurrent tasks in that process. Its events are translated into the test session's results, so both frameworks appear in one `.xcresult` and in the test navigator **[inferred: the exact hand‑off API inside the proprietary XCTest is undocumented. The ABI entry point is the documented way for a host to drive Swift Testing.]**
4. The test plan's *Parallelization* setting applies to both frameworks. When it is off, Swift Testing runs serially as well. XCTest parallelism is off by default, while Swift Testing parallelism is on by default.

### 5.4 Exit tests: why they need a re‑launchable host

* `#expect(processExitsWith:)` spawns **a new process from the same executable** with `posix_spawn` or `CreateProcessW`. The child uses the exit test's ID to find the matching `'exit'` record and runs only that closure, outside any test context. The parent compares the exit condition and, if asked, the captured stdout and stderr.
* Captured values must be `Sendable & Codable` (Swift 6.3+), because they are serialized into the child. How the host re‑launches is defined by `Configuration.exitTestHandler`, which SwiftPM pre‑configures. The ID and a result back‑channel go through environment variables and file descriptors **[unverified detail: `SWT_…`‑prefixed variables, names have changed between releases]**.
* Exit tests are compiled out (`SWT_NO_EXIT_TESTS`) on iOS, watchOS, tvOS, visionOS, Android, WASI and Embedded Swift, because those platforms cannot spawn and wait for a copy of the process.

---

## 6. How the two frameworks integrate

### 6.1 Coexistence (Xcode 16 onwards)

* Both frameworks share the bundle, the host process, the test plan and the `.xcresult`.
* They do **not** share discovery, lifecycle or assertions:
  * An `XCTestCase` is never seen by Swift Testing.
  * A `@Test` inside an `XCTestCase` subclass is rejected at compile time.
  * `setUp` does not run for `@Test` functions.
* Before ST‑0021, an `XCTAssert` inside a `@Test` was silently ignored, and so was an `#expect` inside an `XCTestCase`. Each framework only recorded issues against its own "current test".

### 6.2 Targeted interoperability (ST‑0021: Xcode 26.4 limited mode, Swift 6.4 / Xcode 27 full)

* The proposal governs **swift-corelibs-xctest**. Apple's proprietary XCTest ships equivalent behaviour in Xcode, and WWDC26 described the same modes.
* **Mechanism.** A small library, **`_TestingInterop`**, owned by swift-testing, holds a process‑global *fallback event handler*. It is linked by both frameworks, and neither framework has to link the other.
  * XCTest installs its handler at startup (corelibs-xctest #525).
  * If Swift Testing records an issue while **no Swift Testing configuration is active**, which means it was called from inside an XCTest test, it encodes the event and sends it to the fallback handler (swift-testing #1439). XCTest turns the event into `recordIssue` on the current `XCTestCase` (corelibs-xctest #538).
  * In the other direction, when XCTest is **not** the active test library, which means an `XCTAssert` was reached from a `@Test`, it emits an interop event (corelibs-xctest #542). Swift Testing records the event as an issue on `Test.current`.
  * The record type for these events is defined jointly (corelibs-xctest #537). It follows the JSON ABI event shape **[inferred]**.
  * A C++ re‑implementation of `_TestingInterop` is in progress (swift-testing #1622).
* **Modes.** Modes are set with `SWIFT_TESTING_XCTEST_INTEROP_MODE`, or with the test plan's *Test Execution* setting in Xcode:

  | Mode | Behaviour |
  |---|---|
  | `none` | No interop; the old silent behaviour |
  | `limited` | Cross‑framework failures become warnings. This is the default for older tools versions and test plans created before Xcode 27. |
  | `complete` | Cross‑framework failures are errors. This is the default for new projects and swift-tools-version 6.4+. |
  | `strict` | XCTest API used from Swift Testing causes a fatal error at the call site. Use it to find leftover XCTest calls during migration. |

* **What crosses over.** All XCTest assertions, `#expect`/`#require`, `withKnownIssue`, and `Test.cancel()`, which can skip an XCTest case.
* **What does not cross over.** Lifecycle (`setUp`, suite `init`), parallelism policy, traits and discovery. The frameworks still run as two separate engines in one process.

---

## 7. Consequences for KotlinTesting

1. **The current XCTest backend stays valid.** Xcode still discovers and runs `XCTestCase`s through the ObjC runtime, alongside Swift Testing. Our dynamic `XCTestCase` subclass and `testInvocations` approach is the supported integration point for non‑Swift code. With interop in Xcode 27, Swift helper code that uses `#expect`, called from a Kotlin test that runs as an `XCTestCase`, reports its failures correctly.
2. **A Swift Testing backend is technically possible, but not without Swift.** The *record format* is documented and open to other producers, but a `'test'` record's accessor must produce a Swift `Testing.Test` value. Kotlin/Native cannot build that value without a Swift shim. There are two realistic routes:
   * **Generated Swift shim.** At build time, generate one Swift `@Test` function per Kotlin test, each calling an exported Kotlin entry point, and compile it into the test bundle with the Testing macros. Swift Testing then handles discovery, parameterization display, tags and in‑process parallelism. Kotlin failures must be reported through `Issue.record`, from Swift or from an exported callback.
   * **Own record kind plus our runner.** Emit Kotlin‑specific FourCC records into `__swift5_tests` so that both our runner and future tools can enumerate Kotlin tests the same way. Execution still goes through XCTest or our runner, because Swift Testing ignores unknown kinds.
3. **Use the same discovery idea for Kotlin.** Compile‑time records in a named section, enumerated per loaded image, are what lets Swift Testing find tests in any image type, with no reflection. KotlinTesting's compiler‑plugin registry should follow that model. On Apple targets, a registry placed in a dedicated Mach‑O section, rather than a single generated global like `GeneratedSuites`, would let one runner find tests across several Kotlin frameworks loaded into one host.
4. **Copy the tool ABI shape.** A C‑exported entry point that takes configuration JSON and streams JSON events is how Swift Testing serves Xcode, SwiftPM, VS Code and CMake from one engine. The plan's "JSON event stream" item should adopt this model and stay close to `ABI/JSON.md`, so that the same tooling can read Kotlin results.
5. **Identifier format matters for `-only-testing`.** Kotlin tests exposed through XCTest should keep the `Target/Suite/test` selector shape so that xcodebuild filtering and `-enumerate-tests` work. A Swift Testing route would require the `function()` shape.
6. **Exit tests and parallelism are platform‑bound.** Neither Swift Testing nor KotlinTesting can offer exit tests on iOS. In‑process parallelism is the way to get parallel runs on physical devices. XCTest's clone‑based parallelism does not reach them.

---

## 8. Sources

* [SE‑0492 Section placement control](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0492-section-control.md) · [SE‑0492 review thread (shows the `@Test` expansion with per‑platform sections)](https://forums.swift.org/t/se-0492-section-placement-control/82289)
* [How does swift-testing test discovery work? (Swift Forums)](https://forums.swift.org/t/how-does-swift-testing-test-discovery-work/72771) · [Discovering and registering programmatically generated tests (legacy `__TestContainer`, plan for `swift5_tests`)](https://forums.swift.org/t/discovering-and-registering-programmatically-generated-tests-with-swift-testing/72923)
* [swift-testing repository](https://github.com/swiftlang/swift-testing) · [swift-testing releases (#893 test content section, #1240 removal of `swt_copyABIEntryPoint_v0`)](https://github.com/apple/swift-testing/releases) · [swift-6.3 release notes](https://newreleases.io/project/github/swiftlang/swift-testing/release/swift-6.3-RELEASE) · [PR #1892 static‑link record discovery](https://github.com/swiftlang/swift-testing/pull/1892) · [Command‑line debugging guide (swiftpm-testing-helper, double invocation)](https://github.com/swiftlang/swift-testing/blob/main/Documentation/CommandlineDebugging.md) · [DeepWiki overview](https://deepwiki.com/swiftlang/swift-testing)
* [Using Swift Testing with CMake (ABI entry point)](https://forums.swift.org/t/using-swift-testing-with-cmake/79651) · [swift#77673 `@_extern(c)` entry point typing](https://github.com/swiftlang/swift/issues/77673) · [Issue #506 list tests via JSON](https://github.com/apple/swift-testing/issues/506) · [swift#87650 COFF `.sw5test` section](https://github.com/swiftlang/swift/issues/87650)
* [ST‑0008 Exit tests](https://github.com/swiftlang/swift-evolution/blob/main/proposals/testing/0008-exit-tests.md) · [ST‑0021 proposal](https://github.com/swiftlang/swift-evolution/blob/main/proposals/testing/0021-targeted-interoperability-swift-testing-and-xctest.md) · [ST‑0021 review](https://forums.swift.org/t/st-0021-targeted-interoperability-between-swift-testing-and-xctest/84965) · [ST‑0021 amendment](https://forums.swift.org/t/amended-st-0021-targeted-interoperability-between-swift-testing-and-xctest/86479) · [swift-corelibs-xctest 6.4.0 release (interop PRs)](https://github.com/swiftlang/swift-corelibs-xctest/releases/tag/swift-6.4.0-RELEASE)
* [Swift Testing and the compatibility with xcodebuild (identifier format)](https://trinhngocthuyen.com/posts/tech/swift-testing-and-xcodebuild/) · [Xcode 16.3 release notes (`-only-testing` for Swift Testing, tag filters)](https://gist.github.com/applch/a60207defe6f8086d057c1ec67706a52) · [In search of test discovery solutions in iOS](https://trinhngocthuyen.com/posts/tech/in-search-of-test-discovery/)
* [Apple Developer Forums: XCTest discovery backtrace (Xcode 7.3)](https://developer.apple.com/forums/thread/43279) · [Apple Developer Forums: `objc_copyClassList` class realization crash](https://developer.apple.com/forums/thread/738957) · [Keeping XCTest in sync on Linux](https://oleb.net/blog/2017/03/keeping-xctest-in-sync/) · [Parameterized XCTest with the ObjC runtime](https://briancoyner.github.io/articles/2015-11-28-swift-parameterized-xctestcase/)
* [Running tests serially or in parallel (Swift Forums)](https://forums.swift.org/t/running-tests-serially-or-in-parallel/72935) · [WWDC24 Go further with Swift Testing](https://developer.apple.com/videos/play/wwdc2024/10195/) · [WWDC26 Migrate to Swift Testing](https://developer.apple.com/videos/play/wwdc2026/267/)
* [SourceKit‑LSP releases (syntactic vs semantic test discovery)](https://github.com/swiftlang/sourcekit-lsp/releases) · [`sourcekit/` method prefix discussion](https://forums.swift.org/t/prefixing-sourcekit-to-lsp-bsp-extension-methods/86394)
