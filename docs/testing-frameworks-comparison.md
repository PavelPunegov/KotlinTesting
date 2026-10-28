# Swift Testing vs XCTest vs kotlin.test vs JUnit 5/6 — a capability comparison

*Status of the ecosystem as of October 2026.*
*Versions compared: Swift Testing as shipped with Swift 6.4 / Xcode 27; XCTest as shipped with Xcode 27 (plus swift-corelibs-xctest on Linux); `kotlin.test` as shipped with Kotlin 2.4.20; JUnit Jupiter 6.1.x (with the JUnit 5.x lineage noted where a feature arrived).*

This document is the research input for the [KotlinTesting development plan](development-plan.md). It answers four questions:

1. How do the four APIs compare in flexibility, covered areas and features?
2. Which features does `kotlin.test` lack compared to the others?
3. What does `kotlin.test` actually provide on each Kotlin Multiplatform (KMP) target?
4. Which other Kotlin testing libraries exist, and what do they add on top of `kotlin.test`?

Legend used in the tables: **✅** built in · **🟡** partial / limited / experimental · **🔌** available only through an external library or tool · **❌** not available.

---

## 1. The four frameworks at a glance

| | Swift Testing | XCTest | kotlin.test | JUnit 5 / 6 (Jupiter) |
|---|---|---|---|---|
| Owner, first release | Apple, open source (swiftlang/swift-testing), 2024 | Apple, 1998 lineage (SenTestingKit → XCTest 2013); open-source subset `swift-corelibs-xctest` | JetBrains, part of the Kotlin distribution | JUnit team, 2017 (JUnit 5), JUnit 6.0 on 2025‑09‑30 |
| Evolution process | Swift Evolution, `ST-xxxx` proposals | Closed (Apple release notes) | Kotlin stdlib process (KEEP/YouTrack), ships with each Kotlin release | Public GitHub, release notes per minor |
| Core model | Macros `@Test` / `@Suite`, free functions or members of structs/classes/actors/enums; traits attached to tests | Subclasses of `XCTestCase`, methods prefixed `test`; `XCTestSuite` tree built from the ObjC runtime | Annotations `@Test`, `@Ignore`, `@BeforeTest`, `@AfterTest` + assertion functions; **no runner of its own** — delegates to a platform framework | Annotations on classes/methods; JUnit Platform (launcher, engines) + Jupiter engine + Params + Vintage |
| Discovery | Compile-time metadata emitted by macros; no reflection | ObjC runtime reflection (`testInvocations`, `defaultTestSuite`), Swift mirrors on Linux | Per platform: JUnit/TestNG reflection (JVM), compiler-generated registry (Native, JS, Wasm) | Classpath scanning + reflection via Launcher |
| Primary platforms | macOS, iOS, tvOS, watchOS, visionOS, Linux, Windows (via `swift test`) | Apple platforms (full), Linux (subset) | JVM, Android, JS, Wasm/JS, Wasm/WASI, Native (Apple, Linux, Windows, Android Native) | JVM (any language on the JVM) |
| Async model | Swift concurrency, `async` tests, parallel by default | `async` test methods, `XCTestExpectation` | None in core; `runTest` from kotlinx‑coroutines‑test (`TestResult` return type) | None in core; `@Timeout`, third‑party for coroutines |
| Positioning (2026) | Apple's recommended framework; WWDC26 session "Migrate to Swift Testing" | Kept for UI automation, performance metrics, ObjC exception testing; two‑way interop with Swift Testing (ST‑0021, Xcode 27) | Lowest common denominator for KMP; JetBrains invests in Power‑assert integration (2.4.x) | De‑facto JVM standard; 6.x baseline Java 17, Kotlin 2.1, JSpecify nullability |

---

## 2. Area‑by‑area API comparison

### 2.1 Test declaration and structure

| Capability | Swift Testing | XCTest | kotlin.test | JUnit 5/6 |
|---|---|---|---|---|
| Declare a test | ✅ `@Test` on any function (free or member) | ✅ `func test…()` in an `XCTestCase` subclass | ✅ `@Test` on member functions; top‑level functions on Native/JS only | ✅ `@Test` on methods |
| Suites / grouping | ✅ `@Suite` on struct/class/actor/enum, implicit suite per type | ✅ One suite per subclass; manual `XCTestSuite` composition | ✅ One suite per class (implicit) | ✅ One container per class; `@Suite` via junit‑platform‑suite |
| Nested suites | ✅ nested types | 🟡 via manual `XCTestSuite.addTest` | ❌ | ✅ `@Nested` inner classes (deterministic order in 6.0) |
| Display names | ✅ `@Test("Readable name")`, raw identifiers (`` `my test` ``) | 🟡 method name only | 🟡 backticked function names on JVM only; name = function | ✅ `@DisplayName`, `@DisplayNameGeneration` |
| Tags / categories | ✅ `.tags(.critical)` typed tags | ❌ (test plans only) | ❌ | ✅ `@Tag`, include/exclude filters |
| Bug / issue links | ✅ `.bug("https://…", id:)` | ❌ | ❌ | 🔌 (via `@Tag` or extensions) |
| Disable / ignore | ✅ `.disabled("reason")`, `.enabled(if:)` conditional | ✅ `XCTSkip`, `XCTSkipIf/Unless` at runtime; test plan | 🟡 `@Ignore` unconditional only | ✅ `@Disabled`, `@DisabledIf`, `@EnabledOnOs/Jre/EnvVar/SystemProperty…`, `Assumptions` |
| Inheritance of tests | ✅ | ✅ subclassing | ✅ (JVM), 🟡 Native/JS | ✅ incl. interface default methods |
| Ordering | 🟡 unordered by design (`.serialized` for sequential) | 🟡 alphabetical or randomized (test plan) | ❌ unspecified per platform | ✅ `@TestMethodOrder`, `@TestClassOrder`, `MethodOrderer.Random/OrderAnnotation` |

### 2.2 Lifecycle and fixtures

| Capability | Swift Testing | XCTest | kotlin.test | JUnit 5/6 |
|---|---|---|---|---|
| Per‑test setup/teardown | ✅ `init`/`deinit` of the suite type (fresh instance per test) | ✅ `setUp()`, `setUpWithError()`, `setUp() async throws`, `tearDown…`, `addTeardownBlock` | ✅ `@BeforeTest`, `@AfterTest` | ✅ `@BeforeEach`, `@AfterEach` |
| Per‑suite setup/teardown | 🟡 static `let`/global state; no built‑in hook (scoping traits are the idiom) | ✅ `class func setUp()/tearDown()` | 🟡 `@BeforeClass`/`@AfterClass` on Native and JVM artifacts only, **not in common** | ✅ `@BeforeAll`, `@AfterAll` (`@JvmStatic` or `PER_CLASS` lifecycle) |
| Instance lifecycle control | ✅ always per test | ✅ always per test method | ❌ follows backend | ✅ `@TestInstance(PER_CLASS/PER_METHOD)` |
| Scoped fixtures around a test | ✅ Test scoping traits (`TestScoping`, ST‑0007) | 🟡 `invokeTest()` override, `XCTContext.runActivity` | ❌ | ✅ Extensions (`BeforeEachCallback`, `InvocationInterceptor`), `@RegisterExtension` |
| Resource injection | ❌ (use init) | ❌ | ❌ | ✅ `ParameterResolver`, `TestInfo`, `TestReporter`, `RepetitionInfo`, `@TempDir` |
| Auto‑closing resources | 🟡 `deinit` | ✅ `addTeardownBlock` | ❌ | ✅ `@AutoClose` (5.11) |
| Temporary directories | ❌ | ❌ | ❌ | ✅ `@TempDir` with cleanup modes |

### 2.3 Assertions and expectations

| Capability | Swift Testing | XCTest | kotlin.test | JUnit 5/6 |
|---|---|---|---|---|
| Core assertion style | ✅ `#expect(expr)` macro, one entry point, expression captured and expanded in failure | ✅ ~40 `XCTAssert*` functions | ✅ ~25 functions: `assertEquals/NotEquals/Same/NotSame/True/False/Null/NotNull/Is/IsNot/Contains/ContentEquals/Fails/FailsWith`, `fail`, `expect` | ✅ `Assertions.*` (~30 incl. `assertIterableEquals`, `assertLinesMatch`, `assertArrayEquals`, `assertInstanceOf`) |
| Diagram / sub‑expression values on failure | ✅ built into `#expect` | ❌ | 🔌 Power‑assert compiler plugin (configure `kotlin.test.*` functions explicitly); 2.4.20 added lazy‑message overloads for it | ❌ (🔌 AssertJ/Power‑assert) |
| Lazy failure messages | ✅ `Comment` autoclosure | ✅ `@autoclosure` message | 🟡 2.4.20 `() -> String` overloads, `@ExperimentalKotlinTestApi` | ✅ `Supplier<String>` overloads |
| Stop‑on‑failure variant | ✅ `#require` throws and aborts the test; also unwraps optionals | 🟡 `XCTUnwrap` throws; `continueAfterFailure = false` | 🟡 every assertion throws `AssertionError` (always stop) | 🟡 every assertion throws (always stop) |
| Soft / grouped assertions | ❌ (each `#expect` records; test continues anyway — effectively soft by default) | 🟡 `continueAfterFailure = true` default (records multiple) | ❌ | ✅ `assertAll` |
| Error / exception assertions | ✅ `#expect(throws: T.self)`, returns the error (ST‑0006), `#expect(performing:throws:)` | ✅ `XCTAssertThrowsError`, `XCTAssertNoThrow` | ✅ `assertFailsWith<T>` (returns exception), `assertFails` | ✅ `assertThrows`, `assertThrowsExactly`, `assertDoesNotThrow` |
| Floating point tolerance | 🟡 manual | ✅ `accuracy:` | ✅ `absoluteTolerance` overloads | ✅ `delta` overloads |
| Timeouts in assertions | ❌ (use `.timeLimit` trait) | ✅ via expectations | ❌ | ✅ `assertTimeout`, `assertTimeoutPreemptively` |
| Known/expected failures | ✅ `withKnownIssue { }` (strict/intermittent) | ✅ `XCTExpectFailure(strict:)` | ❌ | ❌ (🔌 extensions) |
| Issue severity | ✅ `Issue.record(_, severity: .warning)` (6.3) | 🟡 `XCTIssue` types only | ❌ | 🟡 `TestReporter.publishEntry` |
| Custom assertion API | ✅ `Issue.record`, `SourceLocation` | ✅ `record(XCTIssue)` | ✅ `Asserter` interface (platform‑global), `asserter` | ✅ `AssertionFailureBuilder`, `OpenTest4J` `AssertionFailedError` with expected/actual |
| Assertion library decoupled from runner | ✅ (Testing module) | ✅ | ✅ (kotlin‑test assertions usable under any JVM runner; `Asserter` swap) | ✅ |

### 2.4 Parameterized and data‑driven testing

| Capability | Swift Testing | XCTest | kotlin.test | JUnit 5/6 |
|---|---|---|---|---|
| Parameterized test functions | ✅ `@Test(arguments: collection)`; each argument = separate test case, independently reportable and re‑runnable | ❌ (loop inside one test, or ObjC `testInvocations` override) | ❌ (open request **KT‑46899**) | ✅ `@ParameterizedTest` + `@ValueSource`, `@EnumSource`, `@CsvSource`, `@CsvFileSource`, `@MethodSource`, `@FieldSource` (5.11), `@NullSource/@EmptySource`, `@ArgumentsSource` |
| Multiple parameters | ✅ two collections → cartesian product; `zip()` for pairs | ❌ | ❌ | ✅ `Arguments.of(...)`, `@CsvSource`, aggregators |
| Parameterized suites / classes | ❌ | 🟡 test plan configurations (env/args/locale) rerun the whole bundle | ❌ | ✅ `@ParameterizedClass` (5.13, experimental in 6.x) with constructor/field injection and `@Before/AfterParameterizedClassInvocation` |
| Per‑argument display names | ✅ argument description; `CustomTestStringConvertible` | ❌ | ❌ | ✅ `name = "{index} {0}"`, `{argumentsWithNames}`, quoting in 6.0 |
| Re‑run a single argument from IDE | ✅ when arguments are `Codable`/`RawRepresentable`/`Identifiable`/`CustomTestArgumentEncodable` | ❌ | ❌ | ✅ (IDE support via unique IDs) |
| Argument conversion | ❌ typed collections already | ❌ | ❌ | ✅ implicit/explicit `ArgumentConverter`, fallback factory methods (`CharSequence` in 6.0) |
| Repeated execution | 🟡 `swift test --maximum-repetitions N --repeat-until fail\|pass` (per test case, ST‑0024, 6.4) | ✅ `-test-iterations`, `-retry-tests-on-failure`, `-run-tests-until-failure`, test plan | 🟡 Native runner `--ktest_repeat=N` (whole binary); nothing in API | ✅ `@RepeatedTest(n, failureThreshold)` |

### 2.5 Dynamic, templated and generated tests

| Capability | Swift Testing | XCTest | kotlin.test | JUnit 5/6 |
|---|---|---|---|---|
| Runtime‑generated tests | 🟡 only via parameterization | ✅ override `class var defaultTestSuite` / `testInvocations` (how this repo injects Kotlin tests) | ❌ | ✅ `@TestFactory` → `DynamicTest`/`DynamicContainer` streams |
| Test templates | 🟡 traits | ❌ | ❌ | ✅ `@TestTemplate` + `TestTemplateInvocationContextProvider`, `@ClassTemplate` (5.13) |
| Custom test engines | ❌ (no public registration ABI; discovery is metadata‑driven) | ✅ XCTest objects can be created programmatically | 🟡 JS: `FrameworkAdapter`/`setAdapter`; Native/JVM: none public | ✅ `TestEngine` SPI, `EngineTestKit` |

### 2.6 Asynchronous and concurrent code

| Capability | Swift Testing | XCTest | kotlin.test | JUnit 5/6 |
|---|---|---|---|---|
| Async test bodies | ✅ `async throws` tests | ✅ `async` test methods | 🟡 `suspend` tests only via `runTest` returning `TestResult` (JS/Wasm promise; Unit elsewhere) | ❌ (🔌 kotlinx‑coroutines‑test `runTest`) |
| Waiting on callbacks | ✅ `confirmation(expectedCount:)`, ranged counts (ST‑0005) | ✅ `XCTestExpectation`, `fulfillment(of:)`, `XCTWaiter`, KVO/notification/predicate expectations | ❌ | ❌ (🔌 Awaitility) |
| Polling until condition | 🟡 pitched (Polling Confirmations), not shipped | ✅ `XCTNSPredicateExpectation`, `waitForExistence` | ❌ | 🔌 Awaitility |
| Virtual time | ❌ (`Test.Clock` for measurement only) | ❌ | 🔌 kotlinx‑coroutines‑test scheduler | 🔌 |
| Parallel execution | ✅ in‑process, parallel by default; `.serialized` to opt out | ✅ process‑level (simulator clones / multiple runners), `-parallel-testing-enabled` | ❌ (Gradle `maxParallelForks` on JVM only) | ✅ `junit.jupiter.execution.parallel.*`, `@Execution`, `@ResourceLock`, `@Isolated` |
| Time limits | ✅ `.timeLimit(.minutes(n))` (minute granularity) | ✅ `executionTimeAllowance`, test plan timeouts | ❌ | ✅ `@Timeout`, thread modes |
| Cancellation | ✅ `Test.cancel()` (6.3), task cancellation propagates | ❌ | ❌ | 🟡 preemptive `@Timeout` interrupt |

### 2.7 Execution control (CLI, filtering, selection)

| Capability | Swift Testing | XCTest | kotlin.test | JUnit 5/6 |
|---|---|---|---|---|
| Filter by name | ✅ `swift test --filter <regex>`, `--skip`; Xcode `-only-testing` | ✅ `-only-testing:`, `-skip-testing:`, test plans | 🟡 Gradle `--tests` → JVM filter / Native `--ktest_gradle_filter`; Native binary: `--ktest_filter`, `--ktest_regex_filter`, `--ktest_negative_regex_filter`; JS via Mocha grep | ✅ Launcher discovery selectors/filters, `--include-classname`, tags |
| Filter by tag | ✅ `--filter` with `tag:` (Xcode test plan tags) | ❌ | ❌ | ✅ `--include-tag`, `--exclude-tag` |
| List tests | ✅ `swift test --list-tests` | ✅ `xcodebuild -enumerate-tests` | 🟡 Native `--ktest_list_tests` | ✅ Launcher / console `--details` |
| Exit codes & CI logging | ✅ | ✅ xcresult | 🟡 Native `--ktest_logger=GTEST\|TEAMCITY\|SIMPLE\|SILENT`, `--ktest_no_exit_code` | ✅ Console launcher, Open Test Reporting |
| Randomize order | 🟡 random by default parallelism | ✅ test plan | ❌ | ✅ `MethodOrderer.Random` + seed |
| Environment / args per run | ✅ env vars, `@Test` conditions | ✅ test plan configurations | 🟡 Gradle `environment` on JVM; `KotlinNativeTest.environment` | ✅ system props / env conditions |

### 2.8 Extensibility and observation

| Capability | Swift Testing | XCTest | kotlin.test | JUnit 5/6 |
|---|---|---|---|---|
| Hooks around tests | ✅ `TestScoping` traits, `TestTrait`/`SuiteTrait` | 🟡 subclassing (`invokeTest`), `XCTestObservation` | ❌ (🔌 Burst `TestInterceptor`) | ✅ Extension model (lifecycle callbacks, `InvocationInterceptor`, `ParameterResolver`, `ExecutionCondition`, `TestWatcher`) |
| Issue transformation | ✅ Issue handling traits (ST‑0011) | ✅ `record(XCTIssue)` override | ❌ | 🟡 `TestExecutionExceptionHandler` |
| Global listeners | ✅ event stream JSON (ABI v0, used by Xcode/VS Code); ST‑0019 adds tag/bug/time‑limit metadata | ✅ `XCTestObservationCenter`, principal class | 🟡 Native internal `TestListener`/`TestLogger` (not public API) | ✅ `TestExecutionListener`, `LauncherSessionListener`, `LauncherInterceptor`, JFR events |
| Attachments | ✅ `Attachment.record` (ST‑0009), images (6.3), conditional saving (ST‑0018, in revision) | ✅ `XCTAttachment` in activities/issues | ❌ | ✅ `TestReporter.publishFile/publishDirectory` (5.12) |
| Configuration files | 🟡 `--configuration-path` JSON (experimental) | ✅ `.xctestplan` | ❌ | ✅ `junit-platform.properties` |

### 2.9 Reporting and IDE/CI integration

| Capability | Swift Testing | XCTest | kotlin.test | JUnit 5/6 |
|---|---|---|---|---|
| Machine‑readable results | ✅ `--xunit-output`, event stream JSON | ✅ `.xcresult` (`xcresulttool`), JUnit XML via third‑party | 🟡 Gradle JUnit XML on JVM; Native/JS via TeamCity messages parsed by Gradle | ✅ JUnit XML (legacy), Open Test Reporting XML/HTML |
| IDE run gutters | ✅ Xcode, VS Code | ✅ Xcode | ✅ JVM (IntelliJ via JUnit); 🟡 Native/JS via Gradle only | ✅ all JVM IDEs |
| Source location of failure | ✅ macro captures location | ✅ `XCTSourceCodeLocation` | 🟡 stack trace only (Native has file:line in debug builds) | ✅ stack trace, IDE navigation |
| Code coverage | ✅ Xcode / `swift test --enable-code-coverage` | ✅ | 🟡 JVM only (Kover/JaCoCo); none for Native/JS | ✅ JaCoCo/Kover |
| Device execution | ✅ via Xcode | ✅ via Xcode | ❌ simulators only (`KotlinNativeSimulatorTest`) | n/a |

### 2.10 Specialized testing techniques

| Technique | Swift Testing | XCTest | kotlin.test | JUnit 5/6 |
|---|---|---|---|---|
| UI automation | ❌ (Apple keeps it in XCTest) | ✅ XCUITest, record/replay (Xcode 26), multi‑configuration replay | ❌ (🔌 Compose Multiplatform `uiTest`, Roborazzi for screenshots) | 🔌 Selenium/Playwright/Espresso |
| Performance / benchmarks | ❌ | ✅ `measure(metrics:)`, `XCTClockMetric/XCTMemoryMetric/XCTCPUMetric/XCTStorageMetric/XCTOSSignpostMetric`, baselines | ❌ (🔌 kotlinx‑benchmark) | 🔌 JMH, JUnit‑Pioneer |
| Crash / exit tests | ✅ `#expect(processExitsWith:)` (ST‑0008, macOS/Linux/Windows only) | ❌ | ❌ | 🔌 System‑Lambda / System Stubs |
| Snapshot / approval tests | 🔌 swift‑snapshot‑testing | 🔌 | 🔌 Roborazzi, Paparazzi, Kotest snapshot | 🔌 ApprovalTests, Spring |
| Property‑based testing | 🔌 (SwiftCheck) | 🔌 | 🔌 Kotest property module | 🔌 jqwik (Platform engine), Kotest |
| Mocking | 🔌 Mockolo etc. | 🔌 | 🔌 Mokkery, Mockative, MocKMP (KMP); MockK (JVM only) | 🔌 Mockito, MockK |
| Architecture rules | ❌ | ❌ | 🔌 Konsist (JVM) | 🔌 ArchUnit, Konsist |
| Objective‑C exception testing | ❌ | ✅ (ObjC tests) | 🟡 `foreignExceptionMode = objc-wrap` in cinterop | n/a |
| Cross‑framework interop | ✅ XCTest asserts usable inside Swift Testing and vice versa (ST‑0021, Xcode 27 modes limited/complete/strict/none) | ✅ same | 🟡 JVM: JUnit annotations usable alongside kotlin‑test‑junit5 | ✅ Vintage engine (deprecated in 6) |

---

## 3. What kotlin.test is missing — consolidated gap table

The table lists capabilities present in at least two of the three other frameworks (or in one of them plus a mature Kotlin third‑party library) that `kotlin.test` does not provide in common code. "Workaround today" is what KMP teams do now. "Priority" is the author's judgement for the KotlinTesting plan (P0 = required for a credible 1.0, P1 = expected of a mature framework, P2 = differentiator).

| # | Missing in kotlin.test (common) | Swift Testing | XCTest | JUnit 5/6 | Workaround today | Priority |
|---|---|---|---|---|---|---|
| 1 | Parameterized tests (per‑argument cases) | ✅ | ❌ | ✅ | Burst compiler plugin; Kotest `withData`; loops inside one test | **P0** |
| 2 | Suite‑level setup/teardown in common code (`@BeforeClass`/`@AfterClass`) | 🟡 | ✅ | ✅ | `lazy` globals; platform source sets | **P0** |
| 3 | Conditional execution / assumptions (`enabled(if:)`, `XCTSkipIf`, `@EnabledOnOs`) | ✅ | ✅ | ✅ | `if (...) return` inside the test (reports a false pass) | **P0** |
| 4 | Suspend / async tests in the core API | ✅ | ✅ | 🔌 | kotlinx‑coroutines‑test `runTest` | **P0** |
| 5 | Timeouts per test / suite | ✅ | ✅ | ✅ | `withTimeout` inside `runTest`; Gradle task timeout | **P0** |
| 6 | Display names, tags, metadata (bug links) | ✅ | 🟡 | ✅ | Naming conventions | **P0** |
| 7 | Filtering by tag / metadata across all targets | ✅ | 🟡 | ✅ | Gradle `--tests` name globs; per‑platform flags | **P0** |
| 8 | Nested suites / hierarchical structure | ✅ | 🟡 | ✅ | Flat classes | **P1** |
| 9 | Dynamic / generated tests | 🟡 | ✅ | ✅ | None | **P1** |
| 10 | Extension / interception model (around‑test hooks, listeners) | ✅ | 🟡 | ✅ | Burst `TestInterceptor`; manual wrappers | **P1** |
| 11 | Soft / grouped assertions (`assertAll`) | 🟡 | 🟡 | ✅ | Kotest `assertSoftly`; AssertK `assertAll` | **P1** |
| 12 | Known‑issue / expected‑failure marking | ✅ | ✅ | ❌ | `@Ignore` (loses signal) | **P1** |
| 13 | Repetition, retries, flaky‑test policy | 🟡 | ✅ | ✅ | Gradle retry plugin (JVM); Native `--ktest_repeat` | **P1** |
| 14 | Parallel execution control (in‑process), resource locks | ✅ | ✅ | ✅ | JVM forks only | **P1** |
| 15 | Attachments (files, images) in reports | ✅ | ✅ | ✅ | Println | **P1** |
| 16 | Public listener / event API for tooling (IDE, CI) | ✅ | ✅ | ✅ | Native internals (`kotlin.native.internal.test`), TeamCity text | **P1** |
| 17 | Rich failure context (expression diagrams, source location) | ✅ | 🟡 | 🟡 | Power‑assert plugin (manual function list) | **P1** |
| 18 | Resource injection / fixtures (`@TempDir`, `TestInfo`, auto‑close) | 🟡 | 🟡 | ✅ | Manual | **P1** |
| 19 | Test ordering control | 🟡 | 🟡 | ✅ | None | **P2** |
| 20 | Issue severity (warnings) | ✅ | ❌ | 🟡 | None | **P2** |
| 21 | Test cancellation at runtime | ✅ | ❌ | 🟡 | Exceptions | **P2** |
| 22 | Exit / crash tests | ✅ | ❌ | 🔌 | None | **P2** |
| 23 | Performance measurement | ❌ | ✅ | 🔌 | kotlinx‑benchmark | **P2** |
| 24 | Device execution for Apple targets (not only simulator) | ✅ | ✅ | n/a | Hand‑written Swift XCTest host (this repo's approach) | **P0 for KotlinTesting** |
| 25 | Xcode‑native reporting (`.xcresult`, test navigator, per‑test re‑run) | ✅ | ✅ | n/a | Same | **P0 for KotlinTesting** |
| 26 | Configuration file / test plans | 🟡 | ✅ | ✅ | Gradle DSL | **P2** |
| 27 | Assertion‑library extensibility beyond one global `Asserter` | ✅ | ✅ | ✅ | Kotest/AssertK | **P2** |

Items that `kotlin.test` does provide and that are competitive: a compact, stable assertion set; `assertFailsWith` returning the exception; reified `assertIs`; tolerance overloads; `assertContains`/`assertContentEquals` for arrays, sequences, ranges, maps and regex; one annotation set that is byte‑for‑byte identical across JVM, JS, Wasm and Native; zero runtime dependencies on Native.

---

## 4. kotlin.test on Kotlin Multiplatform — what you actually get per target

### 4.1 Artifacts

| Artifact | Role |
|---|---|
| `kotlin-test` | Umbrella; Gradle picks the JVM variant from the test task (`useJUnit()` → `kotlin-test-junit`, `useJUnitPlatform()` → `kotlin-test-junit5`, `useTestNG()` → `kotlin-test-testng`) and the platform variant for other targets |
| `kotlin-test-annotations-common` | `expect` declarations: `Test`, `Ignore`, `BeforeTest`, `AfterTest` |
| `kotlin-test-junit`, `kotlin-test-junit5`, `kotlin-test-testng` | `actual typealias` to JUnit4/Jupiter/TestNG annotations + `Asserter` implementation; `BeforeClass`/`AfterClass` typealiases exist here but are not `expect`ed in common |
| `kotlin-test-js`, `kotlin-test-wasm-js`, `kotlin-test-wasm-wasi` | `FrameworkAdapter` bridging to Mocha/Jasmine‑like runners (Node, browser via Karma — Karma is now deprecated in favour of the new browser DSL), own runner for WASI |
| Native | Built into the Kotlin/Native distribution (`kotlin.test` in the stdlib klibs + `kotlin.native.internal.test` runtime); no dependency to declare |

### 4.2 Per‑platform execution backend

| Target | Who runs the tests | How tests are discovered | Filtering | Reporting to Gradle/IDE | Notable limits |
|---|---|---|---|---|---|
| JVM / Android unit tests | JUnit 4, JUnit Platform (Jupiter) or TestNG | Reflection by the delegate framework | Full delegate filtering, `--tests` | JUnit XML, IDE gutters, full IntelliJ support | Only here can you mix in `@ParameterizedTest`, `@Nested`, extensions — not portable |
| Android instrumented | AndroidJUnitRunner | Reflection | `-e class` | Android test reports | Device/emulator required |
| JS (Node, browser) | Mocha (Karma deprecated; new browser DSL) | Compiler‑generated calls into `FrameworkAdapter.suite/test` | Mocha `--grep` via Gradle filter | TeamCity service messages → Gradle XML | Async leaf tests only via `Promise`/`TestResult`; no nested async |
| Wasm/JS | Mocha in Node or browser (d8 has no Mocha) | Same adapter | Same | Same | Same |
| Wasm/WASI | Own minimal runner | Compiler‑generated | Gradle filter | TeamCity messages | Minimal |
| Native: macOS/Linux/Windows host | Compiler‑generated runner (`-tr` / `-generate-test-runner`) in `test.kexe`, Gradle task `KotlinNativeHostTest` | `GeneratedSuites` registry generated by the compiler from `@Test` | `--ktest_filter`, `--ktest_regex_filter`, `--ktest_negative_regex_filter`, `--ktest_gradle_filter` | `--ktest_logger=TEAMCITY` → Gradle XML; loggers GTEST/SIMPLE/SILENT | Internal `kotlin.native.internal.test` API is not public |
| Native: iOS/tvOS/watchOS simulator | Same runner spawned with `xcrun simctl spawn` by `KotlinNativeSimulatorTest` (`device` property) | Same | Same | Same | **No device execution; no Xcode integration; no `UIApplication`/host‑app context; no `.xcresult`** |
| Native: iOS/tvOS/watchOS device | ❌ not supported by Gradle | — | — | — | Requires an XCTest host — the purpose of this repository |
| Native: Android Native | Runner binary; must be pushed with `adb` manually | Same | Same | None out of the box | — |

### 4.3 Common‑code API surface vs platform extras

| Feature | Common | JVM | JS/Wasm | Native |
|---|---|---|---|---|
| `@Test`, `@Ignore`, `@BeforeTest`, `@AfterTest` | ✅ | ✅ | ✅ | ✅ |
| `@BeforeClass`, `@AfterClass` | ❌ | ✅ (JUnit4/5 typealias, needs `@JvmStatic`/`PER_CLASS`) | ❌ | ✅ |
| Top‑level `@Test` functions | ❌ (compiles, but JVM backends ignore them) | ❌ | ✅ | ✅ (`TopLevelSuite`) |
| `suspend` tests | 🟡 via `runTest(): TestResult` | ✅ blocking | ✅ promise | ✅ blocking |
| Custom runner / adapter | ❌ | ✅ (JUnit engines) | ✅ `setAdapter(FrameworkAdapter)` | ❌ (internal) |
| Assertion set | ✅ identical | ✅ | ✅ | ✅ |
| Lazy‑message overloads (2.4.20) | 🟡 experimental | 🟡 | 🟡 | 🟡 |

**Conclusion.** `kotlin.test` guarantees only four annotations and ~25 assertions in common code. Everything that makes a test framework *flexible* — lifecycle beyond per‑test, parametrization, conditional execution, structure, extensibility, parallelism, reporting — is either absent or delegated to a platform runner with non‑portable behaviour. On Apple targets the delegate is a stand‑alone executable, which excludes devices, host‑app context and Xcode tooling.

---

## 5. Other Kotlin testing libraries — what they add over kotlin.test

### 5.1 Frameworks (runner + structure)

| Library | KMP targets | Adds over kotlin.test | Typical use cases | Caveats |
|---|---|---|---|---|
| **Kotest** (6.2.x; 6.0 requires Kotlin 2.2, JDK 11) | JVM, Android, JS, Wasm, Native (Apple, Linux, Windows) | Spec styles (FunSpec, StringSpec, BehaviorSpec, …), nested tests, data‑driven `withData`, property‑based testing, lifecycle listeners & extensions, tags, timeouts, retries, isolation modes, concurrency (`LimitedConcurrency`, dispatcher factory), test clock, assertion library with soft assertions and clues, Power‑assert support (6.0), package‑level config | Full‑featured BDD/spec style suites, property tests, projects wanting one framework for JVM+KMP | Non‑JVM engines are "feature limited": no annotation config, nested tests unsupported on JS, isolation modes unreliable on Native, IDE plugin runs common/native/JS tests via Gradle only; 6.0 replaced compiler plugin with KSP (`kotest-framework-symbol-processor`), removed `@AutoScan` |
| **TestBalloon** (1.0.x, infix) | All Kotlin targets incl. Android device and Robolectric | Compiler‑plugin discovery; DSL `testSuite { test { } }`; suspend tests everywhere, nested suites, dynamic/parameterized tests via plain Kotlin, fixtures, coroutine context inheritance, deep parallelism, `TestConfig` decorator chains; IntelliJ plugin with gutters and structure view; works with Power‑assert and any assertion library | Kotlin‑first DSL testing with identical behaviour on every target; projects hitting Kotest's non‑JVM limits | Artifacts are pinned per Kotlin compiler version (`-K2.4.20` suffix); reached 1.0 in May 2026 |
| **Prepared** (OpenSavvy, 2.6.x) | All targets (through a runner: TestBalloon or Kotest) | "Magicless" DSL: isolated fixtures (`prepared { }`), virtual time control, parameterization, shared values; no runner of its own | Deterministic fixtures/time on top of TestBalloon or Kotest | Depends on a host runner; small community |
| **Burst** (Cash App, 2.14) | All Kotlin targets | Compiler plugin: `@Burst` specializes test classes/functions per `burstValues(...)`, enum and boolean parameters; `TestInterceptor` / `CoroutineTestInterceptor` as a KMP replacement for JUnit rules; works **with** `kotlin.test` | Adding parameterization and around‑test hooks to existing `kotlin.test` suites | Compile‑time expansion only; IDE shows only the default specialization; compiler‑plugin version coupling |
| **Spek** | JVM (+ limited) | Specification DSL | Legacy projects | Unmaintained; avoid for new work |

### 5.2 Coroutines and reactive streams

| Library | Targets | Adds | Use cases |
|---|---|---|---|
| **kotlinx‑coroutines‑test** | All | `runTest`, `TestScope`, `StandardTestDispatcher`/`UnconfinedTestDispatcher`, `TestCoroutineScheduler` virtual time, `advanceUntilIdle`, `runCurrent`, `Dispatchers.setMain` (JVM/Android) | Any suspend code; the de‑facto way to write `suspend` tests with `kotlin.test` |
| **Turbine** (Cash App, 1.2.x) | All | `flow.test { awaitItem() … }`, `turbineScope`, `testIn`, strict event consumption, timeouts | Asserting `Flow`/`StateFlow`/`Channel` emissions deterministically |

### 5.3 Assertions

| Library | Targets | Adds over kotlin.test assertions | Use cases |
|---|---|---|---|
| **Kotest Assertions** (usable without the Kotest runner) | All | 300+ matchers (`shouldBe`, collections, strings, JSON, dates, Ktor, Arrow), `assertSoftly`, `withClue`, `eventually`/`continually`, `shouldThrow`, inspectors | Expressive fluent assertions under any runner incl. kotlin.test/JUnit/XCTest bridge |
| **AssertK** (0.28.x) | All | AssertJ‑style `assertThat(x).isEqualTo()`, `assertAll` soft block, extension‑friendly | Teams coming from AssertJ |
| **Atrium** | JVM, JS (Native partial) | Fluent/infix API with detailed reporting, i18n | Rich reports |
| **Strikt** | JVM | Fluent, chainable `expectThat`, tolerant subject narrowing | JVM‑only |
| **Kluent** | JVM, Android, JS, Native | Infix `shouldBeEqualTo` | Readability |
| **Truthish** | All | Google Truth‑like | Truth users |
| **Power‑assert compiler plugin** (official since 2.0; runtime library and `@PowerAssert` annotation in 2.4.0) | All | Expression diagrams on failure for `kotlin.assert`, configured `kotlin.test.*` and any `@PowerAssert`‑annotated function; `PowerAssertCompilationFilter.TESTS` | Swift‑Testing‑style failure messages without a new assertion library |

### 5.4 Test doubles

| Library | Targets | Mechanism | Notes |
|---|---|---|---|
| **Mokkery** (3.4.x) | JVM, Android, all Native tiers, JS, Wasm/JS, Wasm/WASI | Compiler plugin; `mock<T>()`, `every/everySuspend`, `verify/verifySuspend`, FIR diagnostics | MockK‑like DSL that works in `commonTest`; Kotlin grant winner |
| **Mockative** (3.x) | All | KSP code generation + Gradle plugin | `@Mock` annotated fields |
| **MocKMP** (Kodein) | All | KSP | No relaxed mocks on non‑JVM |
| **MockK** | JVM/Android only | Bytecode instrumentation + reflection | Cannot work on Native/JS (mockk/mockk#58); multiplatform `mockk-common` discontinued |

### 5.5 UI, screenshot, architecture, coverage, performance

| Library | Targets | Adds | Use cases |
|---|---|---|---|
| **Compose Multiplatform `uiTest`** | JVM/Desktop, Android, iOS simulator, Wasm | `runComposeUiTest { setContent {…}; onNodeWithText().performClick() }` semantics‑tree assertions | Shared UI tests in `commonTest` |
| **Roborazzi** (1.7x) | Android (Robolectric), Desktop, iOS Compose | Screenshot/regression testing with shared image pipeline on iOS (1.66+), diff images, Compose preview scanning, AI‑oriented UI tree dumps | Visual regression for Compose Multiplatform |
| **Paparazzi** | Android/JVM | Screenshot rendering without emulator | Android views/Compose |
| **Konsist** | JVM (run from a `jvmTest` source set) | Architecture/structure assertions over Kotlin sources ("ArchUnit for Kotlin"), KMP‑aware | Layering, naming and module rules in CI |
| **Kover** | JVM/Android | Coverage (IntelliJ agent/JaCoCo), KMP JVM source sets | No Native/JS coverage exists in the Kotlin ecosystem |
| **kotlinx‑benchmark** | JVM (JMH), JS, Wasm, Native | Benchmark annotations and Gradle integration | Micro‑benchmarks; counterpart to XCTest `measure` |
| **Ktor `MockEngine`**, SQLDelight in‑memory drivers, **kotlin‑faker** | All | Deterministic HTTP/database/data fixtures | Integration‑style tests in common code |
| **jqwik** | JVM (JUnit Platform engine) | Property‑based testing, shrinking, generators | JVM‑only alternative to Kotest property |

### 5.6 Takeaways for KotlinTesting

* The gaps in §3 are being filled piecemeal by **compiler plugins** (Burst, TestBalloon, Mokkery, Power‑assert) rather than by `kotlin.test` itself — compile‑time metadata is the only discovery mechanism that works on every Kotlin target.
* No library offers an **XCTest execution backend**: Kotest, TestBalloon and Burst all run on Apple targets through the Kotlin/Native stand‑alone runner, so none of them reaches devices, host‑app context or Xcode reports. This is the unique space for KotlinTesting.
* The ecosystem expects a framework to be **assertion‑agnostic** (Kotest assertions, AssertK, Power‑assert must plug in) and **coroutine‑native** (`runTest`, Turbine).

---

## 6. Sources

Swift Testing / XCTest
* [Swift 6.3 released](https://www.swift.org/blog/swift-6.3-released/) · [What's new in Swift: March 2026](https://www.swift.org/blog/whats-new-in-swift-march-2026/) · [What's new in Swift: May 2026](https://www.swift.org/blog/whats-new-in-swift-may-2026/)
* [What's new in Testing, 2025 edition — Rachel Brindle](https://rachelbrindle.com/2025/06/26/whats-new-in-testing-swift-6-2/) · [Swift Testing: the complete guide 6.0 → 6.2](https://www.atelier-socle.com/en/articles/swift-testing-guide)
* [ST-0021 Targeted interoperability between Swift Testing and XCTest](https://forums.swift.org/t/st-0021-targeted-interoperability-between-swift-testing-and-xctest/84965) · [ST-0018 Conditionally saving attachments](https://forums.swift.org/t/st-0018-conditionally-saving-attachments/84051) · [ST-0019 Event stream metadata](https://forums.swift.org/t/st-0019-include-metadata-for-tags-bugs-and-time-limit-traits-in-event-stream/84299) · [ST-0011 Issue handling traits](https://github.com/swiftlang/swift-evolution/blob/main/proposals/testing/0011-issue-handling-traits.md) · [Per-test-case repetition pitch (ST-0024)](https://forums.swift.org/t/pitch-make-test-repetition-per-test-case-instead-of-global/85552) · [Polling confirmations pitch 2](https://forums.swift.org/t/pitch-2-polling-confirmations-in-the-testing-library/81711)
* [WWDC26 Migrate to Swift Testing](https://developer.apple.com/videos/play/wwdc2026/267/) · [WWDC26 What's new in Swift](https://developer.apple.com/videos/play/wwdc2026/262/) · [WWDC25 Record, replay, and review: UI automation with Xcode](https://developer.apple.com/videos/play/wwdc2025/344/) · [Swift Testing vs XCTest: what stays in XCTest](https://blakecrosley.com/blog/swift-testing-vs-xctest)

JUnit
* [JUnit release notes (current)](https://docs.junit.org/current/release-notes/index.html) · [Parameterized classes and tests](https://docs.junit.org/6.0.3/writing-tests/parameterized-classes-and-tests.html) · [`@ParameterizedClass` Javadoc](https://docs.junit.org/6.1.3/api/org.junit.jupiter.params/org/junit/jupiter/params/ParameterizedClass.html) · [JUnit 6: what's new](https://paradigma-digital.medium.com/junit-6-whats-new-in-this-latest-version-1a1487ca0aa4)

kotlin.test and Kotlin
* [kotlin.test API reference](https://kotlinlang.org/api/core/kotlin-test/) · [What's new in Kotlin 2.4.20](https://kotlinlang.org/docs/whatsnew2420.html) · [What's new in Kotlin 2.4.0](https://kotlinlang.org/docs/whatsnew24.html) · [Power-assert compiler plugin](https://kotlinlang.org/docs/power-assert.html) · [Test your multiplatform app](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html) · [Run tests in Kotlin/JS](https://kotlinlang.org/docs/js-running-tests.html) · [Debugging Kotlin/Native tests — Kevin Galligan](https://medium.com/@kpgalligan/debugging-kotlin-native-tests-c73815ab5b5e) · [Kotlin Slack: parameterized tests in kotlin.test (KT-46899)](https://slack-chats.kotlinlang.org/t/9594347/is-there-a-standard-kotlin-only-solutions-for-parameterized-) · [Kotlin Slack: BeforeClass in common](https://slack-chats.kotlinlang.org/t/538092/is-it-possible-to-achieve-beforeclass-behavior-in-android-io)

Kotlin ecosystem
* [Kotest 6.0 release notes](https://kotest.io/docs/next/release6/) · [Kotest project setup](https://kotest.io/docs/framework/project-setup.html) · [Kotest native nested tests #2453](https://github.com/kotest/kotest/issues/2453) · [Kotest isolation on native #3414](https://github.com/kotest/kotest/issues/3414)
* [TestBalloon](https://infix-de.github.io/testBalloon/latest/) · [TestBalloon 1.0.0](https://infix-de.github.io/testBalloon/latest/blog/2026/05/14/testballoon-reaches-flight-level-100/) · [Prepared](https://prepared.opensavvy.dev/) · [Lifting Kotlin testing — Ivan Canet](https://ivan.canet.dev/blog/2025/06/17/state-of-kotlin-tests.html)
* [Burst](https://code.cash.app/burst) · [Burst test interceptors](https://code.cash.app/burst-test-interceptors) · [Burst 2.14.0](https://github.com/cashapp/burst/releases/tag/2.14.0)
* [Mokkery](https://mokkery.dev/) · [MockK Kotlin/Native issue](https://slack-chats.kotlinlang.org/t/14144458/question-can-mockk-be-used-with-kotlin-multiplatform-and-ios) · [Mockative](https://github.com/mockative/mockative)
* [Turbine](https://github.com/cashapp/turbine) · [AssertK](https://github.com/assertk-org/assertk) · [Konsist](https://docs.konsist.lemonappdev.com/) · [Roborazzi releases](https://github.com/takahirom/roborazzi/releases) · [Truthish](https://github.com/varabyte/truthish)
