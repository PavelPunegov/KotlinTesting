# KotlinTesting — development plan towards a mature, feature‑complete multiplatform test library

*Companion to the [framework comparison](testing-frameworks-comparison.md) and [Swift Testing internals](swift-testing-internals.md). Written October 2026 against Kotlin 2.4.20, KSP2, Xcode 27 / Swift 6.4 and JUnit 6.1. Revised after the architecture decisions in §9.*

## 1. Goal and non‑goals

**Goal.** Turn KotlinTesting into a Kotlin Multiplatform test library. Tests are written once in common code with a `kotlin.test`‑compatible annotation model. Each platform runs them with its own native tooling:

* **Kotlin/JVM and Android unit tests: JUnit Jupiter.** The common annotations map directly onto Jupiter annotations and extensions. Gradle `useJUnitPlatform()`, Maven, IntelliJ IDEA, Android Studio and CI tools work unchanged, with no custom engine.
* **Apple targets: XCTest.** This covers iOS, macOS, tvOS and watchOS, on simulators and devices. Tests are packaged as `.xctest` bundles using the test‑bundle output that Kotlin/Native already supports internally. `xcodebuild test`, Xcode's test navigator, test plans, `.xcresult`, device execution and Xcode Cloud then work unchanged. Exporting tests as a framework remains available for non‑standard and manual setups.
* **Other targets: best effort.** For Linux, Windows and Android Native, the same KSP‑generated runner runs as a stand‑alone executable. JS and Wasm keep running the `kotlin.test`‑compatible subset through the existing `kotlin.test` adapters until a later phase.

"Mature and feature complete" is defined in §7 as a checklist derived from the gap table in the comparison (§3 there). In short, it requires:
* parity with the P0/P1 features of Swift Testing and JUnit Jupiter;
* the same observable behaviour on JVM and Apple targets, with Jupiter's semantics as the reference;
* a stable public API;
* tooling integration without hand‑written glue.

**Non‑goals (for 1.0):**
* UI automation; XCUITest and Compose `uiTest` stay separate.
* Performance metrics beyond timing.
* Mocking.
* Competing as an assertion library; we stay assertion‑agnostic.
* A DSL test style.
* Our own JUnit Platform engine; we map onto Jupiter (§9, decision 3).
* A formal compatibility promise across Kotlin, Xcode and JUnit versions (§9, decision 5).
* Swift Testing as an execution backend. Its test content record format is open, but a `'test'` record must produce a Swift `Testing.Test` value, so a backend needs a generated Swift shim. See [Swift Testing internals](swift-testing-internals.md) §7. It is tracked post‑1.0.

## 2. Where the repository stands today

What works:

* **Wrapping tests in XCTest.** `kotlin-test-native-xctest` wraps each Kotlin/Native `TestCase` in an `XCTestCase`:
  * It adds Objective‑C methods at runtime with `class_addMethod` and `NSInvocation`, so XCTest sees a proper selector name per test.
  * It maps `@Ignore` to `XCTSkip` through a native `skipImplementation()` method implementation.
  * It records failures as `XCTIssue`s with a source location and a stack‑trace attachment.
  * It forwards XCTest observation events back to the Kotlin/Native `TestListener`/`TestLogger` chain.
* **The bundle hook is already in place.** `setupXCTestSuite()` is exported as `Konan_create_testSuite` (`@ExportForCppRuntime`). That is the entry point the Kotlin/Native runtime's XCTest launcher calls when the compiler produces a test bundle. So the library is already shaped for decision 4. Only the build wiring and the discovery source change.
* **Framework export works.** `frameworkTesting` exports the tests as a static framework (`KotlinTests`). A Swift test target consumes it by overriding `defaultTestSuite`. This is the path that stays as the manual alternative.
* **Build and CI.** A convention plugin resolves Xcode SDK and framework paths. CI runs `xcodebuild build-for-testing` and `test-without-building` on a simulator.

What blocks maturity:

| Area | Finding | Consequence |
|---|---|---|
| Internal API coupling | The bridge imports `kotlin.native.internal.test.*` with `INVISIBLE_REFERENCE` suppression (`GeneratedSuites`, `TestProcessor`, `TestSettings`, `TestCase`, `TestListener`) | Breaks with any Kotlin/Native runtime refactor. Cannot be published as a stable library. Replaced by the KSP registry (§3). |
| Discovery | Relies on the compiler flag `-tr` (generate test runner) being applied to the consumer's **main** compilation. Tests live in `nativeMain` so they can be exported. | Unconventional layout. No `commonTest` story and no JVM story. |
| Target coverage | The library builds for macOS and iOS. The sample is `iosSimulatorArm64` only. No tvOS/watchOS and no device packaging. | Not usable by real apps without extra work. |
| Glue | Consumers must write a Swift `XCTestCase` subclass that grafts `testSuite().tests` into `defaultTestSuite` | Manual and error‑prone, and hidden from Gradle |
| Features | Only what `kotlin.test` provides: `@Test`, `@Ignore`, `@BeforeTest`, `@AfterTest`, `@BeforeClass`, `@AfterClass`. The observer explicitly does not support repetitions. | None of the gap‑table items |
| Distribution | No Maven publication, bespoke consumer configurations and artifacts, no Gradle plugin, no API dump | Not consumable |
| Quality | No tests of the library itself, no Linux CI job, Kotlin pinned to 2.2.21 while current is 2.4.20, minimal README | Regressions go unnoticed |

## 3. Target architecture

```
                ┌────────────────────────────────────────────────────────────────────┐
                │ kotlintesting-annotations (common, expect declarations)            │
                │ accepted as-is: kotlin.test @Test @Ignore @BeforeTest @AfterTest   │
                │ added:  @BeforeClass @AfterClass (common!) @DisplayName @Tag        │
                │         @Disabled @EnabledOn… @Timeout @Nested @RepeatedTest        │
                │         @ParameterizedTest + @ValueSource @EnumSource @MethodSource │
                │         @TestFactory @KnownIssue …    (names follow JUnit Jupiter)  │
                │ kotlintesting-runtime (common): assume*, assertAll, attach(), Issue │
                └───────────────┬──────────────────────────────────┬─────────────────┘
          JVM / Android unit    │                                  │  Native (Apple first), later JS/Wasm
                                │                                  │
 ┌──────────────────────────────▼──────────────┐   ┌───────────────▼──────────────────────────────────┐
 │ jvm actuals = JUnit Jupiter                  │   │ kotlintesting-ksp (KSP2 processor)                │
 │ • actual typealias → Jupiter annotation      │   │ runs on every ksp<Target>Test compilation          │
 │   where signatures match                     │   │ emits a TestRegistry: suites, tests, lifecycle,   │
 │ • otherwise actual annotation meta-annotated │   │ argument providers, metadata, source locations,   │
 │   with @ExtendWith / @ArgumentsSource /      │   │ factories — plain Kotlin code, no reflection      │
 │   @TestTemplate → kotlintesting-junit        │   └───────────────┬──────────────────────────────────┘
 │   extensions fill the gaps (known issue,     │                   │
 │   platform conditions, attachments…)         │   ┌───────────────▼──────────────────────────────────┐
 │ Discovery & execution: Jupiter engine        │   │ kotlintesting-engine (non-JVM)                    │
 │ (reflection). No KSP needed on the JVM.      │   │ Jupiter-equivalent semantics: lifecycle, params,  │
 │ Reference semantics for all other targets.   │   │ conditions, timeouts, repetition, events          │
 └──────────────────────────────────────────────┘   └───┬───────────────────────────┬──────────────────┘
                                                        │                           │
                                     ┌──────────────────▼─────────────┐  ┌──────────▼───────────────────┐
                                     │ backend-xctest (Apple)          │  │ backend-native (Linux, Win,  │
                                     │ Konan_create_testSuite →        │  │ Android Native): generated   │
                                     │ XCTestSuite/XCTestCase tree     │  │ main(), --ktest_* CLI,       │
                                     │ .xctest bundle (K/N test bundle │  │ TeamCity output for Gradle   │
                                     │ output) · framework export kept │  └──────────────────────────────┘
                                     │ for manual/custom hosts         │
                                     └─────────────────────────────────┘
```

Design rules:

1. **One annotation model, compatible with `kotlin.test`.** Existing `commonTest` code written with `kotlin.test.Test/Ignore/BeforeTest/AfterTest` and `kotlin.test` assertions runs unchanged on every backend.
   * New annotations are a superset of `kotlin.test`. They are named after their JUnit Jupiter counterparts, which makes the JVM mapping trivial and the API familiar.
   * Suite‑level lifecycle keeps the `kotlin.test` names `@BeforeClass`/`@AfterClass`, which `kotlin.test` only offers on JVM and Native. KotlinTesting provides them in common code.
2. **Jupiter is the reference semantics.** On the JVM, Jupiter itself discovers and runs the tests. On other targets, `kotlintesting-engine` reproduces Jupiter's observable behaviour: lifecycle order, instance per test, parameter expansion, display names, skip and abort reasons. A conformance suite runs the same tests on JVM and on an iOS simulator and compares the normalised event streams (§6, Phase 0).
3. **KSP‑generated runners where there is no reflection.** On Native targets, and later JS/Wasm, a KSP2 processor generates a `TestRegistry`. The registry is ordinary Kotlin code that instantiates suites, invokes tests and lifecycle methods, and enumerates arguments. It replaces the compiler's `-tr`/`GeneratedSuites` and removes every dependency on `kotlin.native.internal.test`.
4. **Native packaging: the Kotlin/Native test bundle.** On Apple targets, the default product is the `.xctest` bundle produced by the Kotlin/Native compiler's internal test‑bundle support. Its launcher calls our `Konan_create_testSuite`. Framework export stays a documented manual path.
5. **Assertion‑agnostic.** Any thrown exception is a failure. `AssertionError` subclasses carry expected/actual values where available (OpenTest4J on the JVM, `XCTIssue` details on Apple). Power‑assert, Kotest assertions, AssertK and Turbine must work out of the box.
6. **Coroutine‑native.** `suspend` test and lifecycle methods work everywhere:
   * On the JVM through JUnit 6's built‑in `suspend` support. It requires Kotlin 2.2+ and kotlinx‑coroutines on the classpath.
   * On Native through the generated runner, which wraps calls in `runBlocking`, or in `runTest` when kotlinx‑coroutines‑test is present.
7. **Small, versioned public API.** Use binary‑compatibility‑validator, explicit API mode, and `@ExperimentalKotlinTestingApi` for anything unstable.

### 3.1 How an annotation is realised per platform

`expect`/`actual` annotations allow a different realisation per target while the common source stays the same:

| Common annotation | JVM actual | Native (KSP registry + engine) |
|---|---|---|
| `kotlin.test.Test`, `Ignore`, `BeforeTest`, `AfterTest` | Already typealiased to Jupiter by `kotlin-test-junit5` | Recognised by the KSP processor |
| `@BeforeClass` / `@AfterClass` (new in common) | `actual typealias` → `org.junit.jupiter.api.BeforeAll` / `AfterAll`; functions go in the `companion object` with `@JvmStatic` (usable from common code) | Called around the suite |
| `@DisplayName(value)`, `@Tag(value)`, `@Disabled(value)`, `@Nested`, `@TestFactory` | `actual typealias` to the Jupiter annotation (signatures match) | Metadata in the registry |
| `@RepeatedTest(value, …)` | `actual typealias` | Engine repetition |
| `@ParameterizedTest(name)` | `actual typealias` | Engine expansion |
| `@ValueSource`, `@EnumSource`, `@MethodSource`, `@CsvSource` | `actual typealias` where Java array parameters map exactly. Otherwise an actual annotation meta‑annotated with `@ArgumentsSource(KtProvider::class)`, where the provider reads our annotation (`AnnotationConsumer`). | KSP emits the argument list or the provider call. `@MethodSource` factories live in the companion with `@JvmStatic`. |
| `@Timeout(seconds)` | Actual annotation meta‑annotated with `@ExtendWith(TimeoutBridge::class)`. Jupiter's own `@Timeout` takes a JVM `TimeUnit`, so a typealias is impossible. | Engine watchdog, plus `executionTimeAllowance` on XCTest |
| `@EnabledOn(Platform.IOS, …)`, `@DisabledOn`, `@DisabledIf(Condition::class)` | Actual annotation meta‑annotated with `@ExtendWith(ConditionBridge::class)` (`ExecutionCondition`) | Evaluated before the test. Skips via `XCTSkip`. |
| `@KnownIssue` | `@ExtendWith(KnownIssueExtension::class)` implementing `InvocationInterceptor` + `TestExecutionExceptionHandler` | Engine policy, plus `XCTExpectFailure` on Apple |
| Runtime API (`assume`, `assertAll`, `attach`, `Issue.record`) | `Assumptions`, `assertAll` / `MultipleFailuresError`, `TestReporter.publishFile` via a `ThreadLocal` context set by an auto‑registered extension | Engine context, plus `XCTAttachment` / `XCTIssue` |

KSP diagnostics run on every target. They catch mistakes that would otherwise fail only on one platform: private test functions, parameter count mismatches, `@BeforeClass` outside a companion object, missing `@JvmStatic`. This keeps the shared source valid for both Jupiter and the generated runner.

## 4. Backends in detail

### 4.1 JVM and Android unit tests: mapping onto JUnit Jupiter (`kotlintesting-junit`)

* **No custom engine.** The JVM artifact of `kotlintesting-annotations` provides `actual typealias`es to Jupiter annotations wherever the signatures match. For annotations Jupiter lacks, it provides actual annotations meta‑annotated with Jupiter's composition hooks, which are `@ExtendWith`, `@ArgumentsSource` and `@TestTemplate`. Jupiter supports composed annotations, so discovery, selectors, tag filtering, unique IDs, IDE gutters, re‑running a single parameterized invocation, JUnit XML and Open Test Reporting all come for free.
* **`kotlintesting-junit` holds the extensions:**
  * `TimeoutBridge`
  * `ConditionBridge` for platform and custom conditions
  * `KnownIssueExtension`
  * `TestContextExtension`, which exposes `attach()`/`Issue.record()` to the runtime API through `TestReporter`
  * an argument provider for our source annotations

  They are registered through the meta‑annotations, and globally through `junit-platform.properties` auto‑detection where needed.
* **Gradle plugin.** For JVM and Android unit‑test compilations, the plugin only adds dependencies (`kotlin-test-junit5`, `kotlintesting-junit`, `junit-jupiter-params`) and calls `useJUnitPlatform()`. No KSP runs on the JVM.
* **Accepted trade‑off.** Semantics are Jupiter's by definition, so the non‑JVM engine has to match them, not the other way round. Features Jupiter does not have and that cannot be expressed as an extension are not offered in common code. One example is Swift Testing‑style per‑case parallel display. Such features stay platform‑specific.

### 4.2 Apple: XCTest backend (`backend-xctest`)

* **Bundle as the default product.**
  * The Gradle plugin compiles the target's test compilation (`commonTest` + `appleTest` + `iosTest` …) with the Kotlin/Native compiler's test‑bundle output. This is internal compiler support (an `-produce`‑style test‑bundle output kind, and the `xctest` mode of Kotlin/Native's own test infrastructure), and its exact flags must be confirmed in the Phase 0 spike.
  * The result is an `.xctest` bundle with an `Info.plist` and the runtime's XCTest launcher. The launcher calls `Konan_create_testSuite`, which our library exports today.
  * The plugin adds tasks:
    * `xcTestBundle<Target>` builds the bundle.
    * `xcTest<Target>` runs it on the host, simulator or device. It uses `xcrun xctest` for macOS, `xcrun simctl` for simulators, and `xcodebuild test-without-building` with a generated `.xctestrun` for devices and test plans.
  * An optional host app supports hosted tests that need `UIApplication`, like today's `ensureUIApplication` sample.
* **Framework export, kept for manual setups.** The `binaries.framework` route with the `testSuite()` entry, consumed by a Swift `XCTestCase` that overrides `defaultTestSuite`, stays supported and documented. It covers existing Xcode projects, custom host apps, mixing Kotlin and Swift tests in one target, and setups the bundle task cannot express. Both routes share the same `Konan_create_testSuite`/`setupXCTestSuite` code.
* **Suite tree from the KSP registry.** `setupXCTestSuite()` builds the XCTest structure from the generated `TestRegistry` instead of `GeneratedSuites`/`TestProcessor`:
  * Each Kotlin suite gets its own dynamic `XCTestCase` subclass, so Xcode's navigator shows the `Suite/test` hierarchy and `-only-testing:Bundle/Suite/test` works.
  * `@Nested` classes become nested `XCTestSuite`s.
  * Each parameterized case gets its own selector with an escaped argument name.
  * `@DisplayName` maps to the `name` override.
  * The proven runtime mechanism stays: `class_addMethod`, `NSInvocation` and the native skip method implementation.
* **Lifecycle:**
  * `@BeforeClass/@AfterClass` maps to `XCTestSuite.setUp/tearDown` (already done for the class level).
  * `@BeforeTest/@AfterTest` maps to the instance's `setUp/tearDown`.
* **Outcomes:**
  * Skips use `XCTSkip` via the native method implementation until KT‑43719 (Objective‑C exception import) lands.
  * Known issues map to `XCTExpectFailure`.
  * Attachments map to `XCTAttachment`.
  * Timeouts use `executionTimeAllowance` plus the engine's watchdog.
  * Source locations come from KSP `Location` data for declarations and from stack traces for failures.
* **Reporting.** Xcode produces the `.xcresult`. The observer additionally emits TeamCity and JUnit XML, so Gradle reports and IntelliJ show results when tests are launched from Gradle.
* **Interop.** With Xcode 27's ST‑0021 interop, Swift helpers that use `#expect`, called from Kotlin tests, report into the running `XCTestCase`.

### 4.3 Stand‑alone native runner (`backend-native`)

* For Linux, Windows and Android Native, the plugin builds an executable from the test compilation with a KSP‑generated `main()`. That `main` drives the engine and produces TeamCity output compatible with the existing `--ktest_*` flags (`--ktest_filter`, `--ktest_regex_filter`, `--ktest_logger`, `--ktest_repeat`, `--ktest_list_tests`), so Gradle's test reporting works.
* The same runner is the fallback on Apple targets when no bundle is wanted (`./gradlew iosSimulatorArm64Test`).

### 4.4 JS and Wasm

* **Until Phase 5,** JS and Wasm run the `kotlin.test` subset through the standard `kotlin.test` adapters. The Gradle plugin warns when a common test uses extended annotations, such as `@ParameterizedTest` or `@BeforeClass`, that these targets would otherwise silently ignore.
* **Phase 5 (stretch)** adds the KSP registry with Mocha `describe/it` registration through a `kotlin.test` `FrameworkAdapter`.

## 5. Feature roadmap mapped to the gap table

| Gap # (comparison §3) | Feature | Common API | JVM (Jupiter) | Apple (KSP + engine + XCTest) |
|---|---|---|---|---|
| 1 | Parameterized tests | `@ParameterizedTest` + `@ValueSource`, `@EnumSource`, `@MethodSource`, `@CsvSource`, `@ArgumentsSource(Provider::class)` | Typealias or `@ArgumentsSource` bridge | KSP‑expanded cases, one selector per case |
| 2 | Suite lifecycle in common | `@BeforeClass/@AfterClass` in the companion with `@JvmStatic` | `@BeforeAll/@AfterAll` | `XCTestSuite.setUp/tearDown` |
| 3 | Conditional execution | `@Disabled`, `@EnabledOn/@DisabledOn(Platform…)`, `@DisabledIf(Condition::class)`, `assume(...)` | Typealias / `ExecutionCondition` bridge / `Assumptions` | `XCTSkip` |
| 4 | Suspend tests | `@Test suspend fun` | JUnit 6 native `suspend` support | `runBlocking`/`runTest` in generated code |
| 5 | Timeouts | `@Timeout(seconds)` | `TimeoutBridge` extension | Watchdog + `executionTimeAllowance` |
| 6 | Names, tags, metadata | `@DisplayName`, `@Tag`, `@Bug(url)` | Typealias; `@Bug` → tag + report entry | `name`, attachments |
| 7 | Filtering | Gradle `kotlintesting { filter { includeTags(...) } }` | Jupiter tag expressions | Generated filter + `-only-testing` / `-skip-testing` |
| 8 | Nested suites | `@Nested` inner classes | Typealias | Nested `XCTestSuite` |
| 9 | Dynamic tests | `@TestFactory fun f(): List<DynamicTest>` (common `DynamicTest` type) | Typealias + conversion to Jupiter `DynamicNode` | Selectors created when `testInvocations` runs |
| 10 | Extensions | `TestInterceptor`, `Listener` via `@ExtendWith`‑like common annotation | Adapter to Jupiter `InvocationInterceptor` / `TestWatcher` | Interceptor chain, `invokeTest` |
| 11 | Soft assertions | `assertAll { }` | `MultipleFailuresError` | Multiple `XCTIssue`s |
| 12 | Known issues | `@KnownIssue(strict)`, `withKnownIssue { }` | `KnownIssueExtension` | `XCTExpectFailure` |
| 13 | Repetition / retry | `@RepeatedTest(n, failureThreshold)` | Typealias | Engine repetition; `-test-iterations` compatible |
| 14 | Parallelism | Config only (`kotlintesting { parallel = … }`) | `junit.jupiter.execution.parallel.*` | Serial in process; XCTest clones |
| 15 | Attachments | `attach(name, bytes/text/file)` | `TestReporter.publishFile` | `XCTAttachment` |
| 16 | Events for tools | `TestEvent` listener + JSON writer (shape modelled on Swift Testing ABI v0) | `TestExecutionListener` adapter | `XCTestObservation` |
| 17 | Rich failures | Power‑assert‑ready assertions; declaration locations from KSP | OpenTest4J expected/actual | `XCTSourceCodeLocation` |
| 18 | Fixtures | `TestInfo` parameter, `@TempDir` (JVM + Native file systems) | Typealias / `ParameterResolver` | Engine resolver, teardown blocks |
| 19 | Ordering | `@TestMethodOrder`, `@Order` | Typealias | Ordered suite construction |
| 20 | Severity | `Issue.record(..., WARNING)` | Report entry | Detailed description, no failure |
| 21 | Cancellation | `abort(reason)` | `TestAbortedException` | `XCTSkip` |
| 24, 25 | Devices, `.xcresult` | Gradle `xcTest<Target> { destination = … }` | — | Native |

## 6. Phased delivery

Each phase ends with a tagged release and docs. Durations are estimates for a team of two to three engineers.

### Phase 0 — Foundation and spikes (≈ 6 weeks)

* Upgrade to Kotlin 2.4.20 and KSP2. Run CI on the current Kotlin release only (see §9, decision 5).
* Repository layout: `annotations/`, `runtime/`, `ksp/`, `engine/`, `junit/`, `xctest/`, `native-runner/`, `gradle-plugin/`, `samples/`, `docs/`.
* **Spike A, Kotlin/Native test bundle.** Confirm the compiler flags that produce an `.xctest` bundle for each Apple target, the launcher contract around `Konan_create_testSuite`, and whether `-tr` is implied. Document them in `docs/`.
* **Spike B, KSP on test compilations.**
  * Run the processor on `kspIosSimulatorArm64Test` and `kspMacosArm64Test` over `commonTest` sources.
  * Confirm that it sees `kotlin.test` annotations and our expect annotations.
  * Verify incremental behaviour across `commonTest` and platform test source sets.
  * There is no `kspCommonTestMetadata` (google/ksp#3139), so generation is per target by design.
* **Spike C, Jupiter mapping.** Prove typealiases and meta‑annotated actuals for `@BeforeClass`, `@ParameterizedTest` + `@ValueSource`, `@Timeout` and `@EnabledOn` from `commonTest` on `jvm()`.
* Publishing setup: Maven Central, Gradle plugin portal, binary‑compatibility‑validator, explicit API mode.
* CI: a Linux job for the JVM and the KSP processor; a macOS job for Apple bundles on a simulator matrix. Replace the "default scheme" script with explicit schemes.
* **Conformance harness.** One sample `commonTest` suite runs on the JVM through Jupiter and on an iOS simulator through XCTest. The two event streams are normalised and compared to golden files.
* **Exit:** spikes documented; current functionality builds on 2.4.20; `0.1.0-alpha`.

### Phase 1 — XCTest backend on KSP registry and test bundles (≈ 10 weeks)

* KSP processor MVP:
  * Discovers `kotlin.test` `@Test/@Ignore/@BeforeTest/@AfterTest` and our `@BeforeClass/@AfterClass`.
  * Emits the `TestRegistry` with source locations.
  * Emits diagnostics for private or invalid test functions.
* Engine MVP with Jupiter lifecycle semantics. `setupXCTestSuite()` consumes the registry, which removes **all** `kotlin.native.internal.test` usage.
* One `XCTestCase` subclass per suite, `-only-testing` mapping, and `XCTSkip` for `@Ignore`.
* All Apple targets: `macosX64/Arm64`, `ios*`, `tvos*`, `watchos*`, on simulators and devices.
* Gradle plugin MVP:
  * `kotlintesting { apple { } }` wires KSP onto each Apple test compilation and builds the test bundle (`xcTestBundle<Target>`).
  * `xcTest<Target>` runs it, on a device through `.xctestrun` + `xcodebuild test-without-building`.
  * The `.xcresult` summary and JUnit XML go into Gradle reports.
* Framework export path refactored onto the same registry. Its Swift `defaultTestSuite` glue is documented as the manual setup.
* Docs: "Run Kotlin tests as an XCTest bundle", "Run on a device", "Manual setup with an exported framework".
* **Exit:** `samples/ios` runs `commonTest` tests in an `.xctest` bundle from Gradle and from `xcodebuild`, on a simulator and a device, without Swift glue. `samples/ios-framework` keeps the manual path green. Release `0.2.0`.

### Phase 2 — JVM mapping onto Jupiter (≈ 5 weeks)

* JVM actuals for every annotation that exists so far, plus the `kotlintesting-junit` extensions.
* Gradle plugin JVM/Android wiring: dependencies and `useJUnitPlatform()`.
* Conformance harness green for lifecycle, ignore and class‑level hooks on JVM and iOS.
* **Exit:** the same `commonTest` source set runs on the JVM through Jupiter and on iOS through XCTest with equivalent normalised event streams. Release `0.3.0`.

### Phase 3 — Common feature set v1 (≈ 12 weeks)

Implements the P0 items (gap table 1–7) and the first P1 items (8, 9, 11, 12). Each feature is delivered as a pair: the JVM actual or extension, and the KSP generation plus engine support.

* Parameterized tests, with static sources first and providers second, per‑case display names, and `-only-testing` per case on Xcode.
* `@BeforeClass/@AfterClass`, conditions and assumptions, `@Timeout`, `suspend` tests (JUnit 6 on the JVM, generated `runBlocking`/`runTest` on Native).
* Display names, tags and tag filtering; `@Nested`; `@TestFactory`; `assertAll`; known issues.
* Runtime module: `kotlin.test`‑compatible assertions with lazy messages, ready for `@PowerAssert`.
* **Exit:** checklist items A1–A12 in §7 green on JVM and Apple (simulator and device). Release `0.5.0`.

### Phase 4 — Execution and extensibility (≈ 8 weeks)

* Interceptor and listener SPI (Jupiter adapters on the JVM), ordering, repetition, attachments, issue severity, abort, `TestInfo`/`@TempDir`, and the JSON event stream with a public `TestEvent` API.
* Stand‑alone native runner for Linux, Windows and Android Native on the same registry.
* **Exit:** checklist items B1–B9 green. Release `0.8.0`, the API freeze candidate.

### Phase 5 — Tooling and developer experience (≈ 8 weeks)

* Gradle plugin complete:
  * selection of bundle or stand‑alone runner per target
  * filters
  * device and simulator destinations
  * environment variables
  * mapping of `.xctestplan` tags to our filters
  * report aggregation across targets
  * configuration cache compatibility
* IDE: gutters on the JVM through Jupiter; a run configuration for Apple targets through Gradle.
* Migration guides from `kotlin.test` and from XCTest for iOS developers. A cookbook for Mokkery, Turbine, AssertK and Power‑assert.
* Stretch goal: KSP registry on JS and Wasm.
* **Exit:** `1.0.0-RC`.

### Phase 6 — 1.0 and beyond (≈ 6 weeks to 1.0, then continuous)

* API audit, binary‑compatibility baseline, overhead benchmarks against plain `kotlin.test`, and a review of the Gradle plugin's Xcode invocations.
* Post‑1.0 tracks:
  * a Swift Testing shim backend (a generated Swift `@Test` per Kotlin test)
  * exit tests on macOS and Linux
  * a property‑based testing module
  * JS/Wasm parity
  * a compatibility policy and version matrix, revisiting decision 5

## 7. Definition of done — maturity checklist

**A. Authoring (identical observable behaviour on JVM and Apple)**
* A1 `kotlin.test` annotations and assertions run unchanged
* A2 `@BeforeClass/@AfterClass` in common code
* A3 parameterized tests with per‑case reporting
* A4 dynamic tests
* A5 `suspend` tests
* A6 conditions and assumptions
* A7 timeouts
* A8 display names, tags and bug links
* A9 nested suites
* A10 soft assertions
* A11 known issues
* A12 KSP diagnostics for invalid declarations

**B. Execution**
* B1 filter by name and tag on every backend
* B2 ordering
* B3 repetition
* B4 parallel execution where the platform allows it
* B5 interceptor and listener SPI
* B6 attachments
* B7 issue severity and abort
* B8 JSON event stream, TeamCity and JUnit XML output
* B9 exit codes and CI‑friendly logging

**C. Platform integration**
* C1 Jupiter mapping working with Gradle, Maven, IntelliJ and Android Studio
* C2 Kotlin/Native `.xctest` bundle with `xcodebuild test`, device destinations, `.xcresult`, the navigator hierarchy and `-only-testing` per test and per case
* C3 framework export documented and covered by a sample
* C4 stand‑alone runner for Linux, Windows and Android Native
* C5 Gradle plugin with configuration cache support and aggregated reports

**D. Quality and governance**
* D1 conformance suite comparing JVM and Apple event streams
* D2 binary‑compatibility validation and explicit API mode
* D3 a docs site with guides and migration paths
* D4 sample projects in CI: iOS bundle, iOS framework, macOS, Android, JVM
* D5 release cadence aligned with Kotlin releases

## 8. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| The Kotlin/Native test‑bundle output is internal and undocumented | Flags or launcher contract may change without notice | Spike A documents it. Integration tests on every Kotlin EAP. The framework export path is a ready fallback. Upstream an issue asking JetBrains to make it public. |
| KSP has no `commonTest` metadata processing (google/ksp#3139) | The registry is generated per target. Generated code cannot be referenced from `commonTest`. | By design, only generated platform code references tests, never the reverse. The diagnostics run per target too. |
| Stale KSP outputs when annotations span `commonTest` and platform test source sets (seen in MocKMP) | Missing tests on CI | Conformance tests in CI. Document disabling caching for `ksp*Test` tasks if needed. Report upstream. |
| KSP sees declarations, not bodies, and cannot modify code | No expression‑level source capture | Declaration locations from KSP. Failure locations from stack traces. Power‑assert for expressions. |
| Jupiter mapping constrains the common API | Annotation signatures must fit Jupiter (`value` parameters, Java arrays). JVM‑only semantics, such as static `@BeforeAll`, leak into common rules. | Typealias only where signatures match exactly, meta‑annotated actuals otherwise. KSP diagnostics enforce `companion` + `@JvmStatic`. Accept Jupiter as the reference semantics. |
| The Native engine drifts from Jupiter behaviour | Same test behaves differently on JVM and iOS | The conformance suite is a release gate. Each new feature lands with a JVM/Apple conformance case. |
| Objective‑C runtime tricks (`class_addMethod`, method implementation blocks, `NSInvocation`) | Future Xcode versions could reject dynamically created test classes | Tests on every Xcode beta. Alternative: generate Objective‑C stubs per test at build time. |
| Kotlin/Native cannot catch Objective‑C exceptions (KT‑43719), so skipping relies on a workaround | Skips may misbehave | Keep the native method implementation. Follow KT‑43719. |
| Apple de‑emphasises XCTest in favour of Swift Testing | The backend could become legacy | XCTest remains the interop target (ST‑0021) and Xcode still runs it alongside Swift Testing. The shim backend is planned post‑1.0. |
| Threading constraints in XCTest versus coroutine tests | Deadlocks or flaky tests | The generated runner runs on the XCTest thread. A `Dispatchers.Main` test dispatcher and documented rules. |
| No compatibility matrix (decision 5) | Users on older Kotlin or Xcode may break | State the tested versions in each release note. Revisit after 1.0. |

## 9. Decisions

Decided in October 2026:

1. **Annotation model, compatible with `kotlin.test`.**
   * `kotlin.test` annotations and assertions are accepted unchanged.
   * New annotations extend them and are named after JUnit Jupiter.
   * No DSL for 1.0.
   * *Consequence:* existing `commonTest` code migrates with zero changes, and §3.1 defines each annotation's realisation per platform.
2. **KSP generates the test runners for annotated classes and methods.** The KSP2 processor runs on each target's test compilation and emits the `TestRegistry` and, for stand‑alone runners, `main()`. There is no Kotlin compiler plugin.
   * *Consequences:* KSP is decoupled from compiler internals, which removes the main maintenance risk of a compiler plugin. Generation is per target because there is no `commonTest` metadata step. Declaration‑level information only (no body rewriting). Not needed on the JVM, where Jupiter discovers tests by reflection.
3. **JVM: map onto JUnit Jupiter, no own engine.**
   * Typealiases where signatures match, meta‑annotated actuals plus `kotlintesting-junit` extensions otherwise.
   * *Consequences:* full Gradle/IDE/report support at minimal cost, and JUnit 6's native `suspend` support comes for free. Jupiter becomes the reference semantics, which the Native engine must match. Features are limited to what Jupiter or its extensions can express.
4. **Apple: `.xctest` bundles produced by Kotlin/Native's internal test‑bundle support are the default.** Framework export is kept for additional non‑standard and manual setups.
   * *Consequences:* no Swift glue in the default path. `Konan_create_testSuite` stays the entry point for both routes. The internal compiler support is a tracked risk (§8).
5. **Compatibility promise: not needed at the moment.**
   * Build and test against the current Kotlin, Xcode and JUnit releases. List the tested versions in release notes. Revisit after 1.0.

## 10. Immediate next steps (first two sprints)

1. Bump Kotlin to 2.4.20, add KSP2, and fix the build. Add Linux and macOS CI jobs with explicit schemes.
2. Spike A: build an `.xctest` bundle for `iosSimulatorArm64` with the Kotlin/Native test‑bundle output, using today's `kotlin-test-native-xctest`. Run it with `xcrun simctl`/`xcodebuild` and document the flags and launcher contract.
3. Spike B: a KSP processor that discovers `kotlin.test.@Test` in `commonTest` on `kspIosSimulatorArm64Test` and generates a minimal `TestRegistry`.
4. Port `setupXCTestSuite()` to the registry and delete the `kotlin.native.internal.test` imports. Generate one `XCTestCase` subclass per suite.
5. Spike C: common `@BeforeClass` and `@ParameterizedTest` + `@ValueSource` with JVM typealiases to Jupiter. Start the conformance harness with these two cases on JVM and iOS.
